package me.lingci.dy.player.ui.compose.playlist

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.Playlist
import me.lingci.dy.player.room.entity.PlaylistItemCount
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil

/** 列表项：不吃 [me.lingci.dy.player.room.entity.Playlist] 实体，只留渲染需要的字段。 */
@Immutable
data class PlaylistUiModel(
    val id: String,
    val title: String,
    val itemCount: Int,
    val playMode: Int,
    val isAutoPlay: Boolean,
    /** 排序用时间戳：排序在内存里做，不为了切换顺序再查一次库。 */
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

enum class PlaylistPhase { Loading, Content, Empty }

@Immutable
data class PlaylistUiState(
    val phase: PlaylistPhase = PlaylistPhase.Loading,
    val items: List<PlaylistUiModel> = emptyList(),
    /** 当前生效的排序：SortBar 直接读它，页面不另存状态。 */
    val sortField: PlaylistSortField = PlaylistSortField.Updated,
    val sortAscending: Boolean = false
)

/**
 * 一次性提示：ViewModel 不持有 Context，也不拿得到资源 id，
 * 这里只表达「哪条提示」，文案由 Route 用 `stringResource` 解析。
 */
enum class PlaylistMessage {
    Created, Renamed, Deleted, ModeUpdated, AutoPlaySet, AutoPlayCleared
}

sealed interface PlaylistEvent {
    data class Message(val message: PlaylistMessage) : PlaylistEvent
}

/**
 * 播放列表页 ViewModel。
 *
 * 列表数据 = playlist 主表 Flow + 条目数聚合 Flow + 自动播放偏好（单一状态源，
 * 由本 ViewModel 写 SP 并回灌，页面不再各持一份 `remember`）。
 */
class PlaylistViewModel(app: Application) : AndroidViewModel(app) {

    private val playlistDao = DatabaseManager.playlistDao
    private val itemDao = DatabaseManager.playlistItemDao
    private val sp = SpUtil(app)

    private val autoPlayId = MutableStateFlow(sp.autoPlayPlaylistId)

    /** 排序偏好（`sort.playlists`）：默认更新时间倒序，与旧版 `ORDER BY updated_at DESC` 一致。 */
    private val sortSpec = MutableStateFlow(sp.readPlaylistSort())

    private val _events = Channel<PlaylistEvent>(Channel.BUFFERED)
    val events: Flow<PlaylistEvent> = _events.receiveAsFlow()

    val state: StateFlow<PlaylistUiState> = combine(
        playlistDao.observeAll(),
        itemDao.observeItemCounts(),
        autoPlayId,
        sortSpec
    ) { playlists, counts, auto, sort -> PlaylistsSnapshot(playlists, counts, auto, sort) }
        .map { snapshot ->
            val countById = snapshot.counts.associate { it.playlistId to it.itemCount }
            val field = snapshot.sort.modeOr(PlaylistSortField.Updated)
            val comparator: Comparator<PlaylistUiModel> = when (field) {
                PlaylistSortField.Updated -> compareBy { it.updatedAt }
                PlaylistSortField.Created -> compareBy { it.createdAt }
                PlaylistSortField.Name -> compareBy { it.title.lowercase() }
            }
            val items = snapshot.playlists.map { playlist ->
                PlaylistUiModel(
                    id = playlist.id,
                    title = playlist.title,
                    itemCount = countById[playlist.id] ?: 0,
                    playMode = playlist.playMode,
                    isAutoPlay = playlist.id == snapshot.auto,
                    createdAt = playlist.createdAt,
                    updatedAt = playlist.updatedAt
                )
            }.let { list ->
                if (snapshot.sort.ascending) list.sortedWith(comparator)
                else list.sortedWith(comparator.reversed())
            }
            PlaylistUiState(
                phase = if (items.isEmpty()) PlaylistPhase.Empty else PlaylistPhase.Content,
                items = items,
                sortField = field,
                sortAscending = snapshot.sort.ascending
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PlaylistUiState(
                sortField = sortSpec.value.modeOr(PlaylistSortField.Updated),
                sortAscending = sortSpec.value.ascending
            )
        )

    /** 切换排序字段：升降序沿用当前方向，写 SP 后数据流重排，不再查库。 */
    fun setSortField(field: PlaylistSortField) {
        if (field == sortSpec.value.modeOr(PlaylistSortField.Updated)) return
        applySort(SortSpec(field.name, sortSpec.value.ascending))
    }

    fun toggleSortOrder() {
        applySort(sortSpec.value.copy(ascending = !sortSpec.value.ascending))
    }

    private fun applySort(spec: SortSpec) {
        sp.writePlaylistSort(spec.modeOr(PlaylistSortField.Updated), spec.ascending)
        sortSpec.value = spec
    }

    private data class PlaylistsSnapshot(
        val playlists: List<Playlist>,
        val counts: List<PlaylistItemCount>,
        /** 启动自动播放目标 id；未设置时为 null */
        val auto: String?,
        val sort: SortSpec
    )

    fun create(name: String, playMode: Int) {
        viewModelScope.launch {
            runCatching {
                LibraryCompat.createPlaylist(name, playMode)
                _events.send(PlaylistEvent.Message(PlaylistMessage.Created))
            }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            runCatching {
                LibraryCompat.renamePlaylist(id, name)
                _events.send(PlaylistEvent.Message(PlaylistMessage.Renamed))
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching {
                LibraryCompat.deletePlaylist(id)
                // 删除的列表若正被设为启动自动播放，同步清空偏好，否则下次启动会指向已删除的 id
                if (sp.autoPlayPlaylistId == id) {
                    sp.autoPlayPlaylistId = ""
                    autoPlayId.value = ""
                }
                _events.send(PlaylistEvent.Message(PlaylistMessage.Deleted))
            }
        }
    }

    fun setPlayMode(id: String, mode: Int) {
        viewModelScope.launch {
            runCatching {
                LibraryCompat.updatePlaylistMode(id, mode)
                _events.send(PlaylistEvent.Message(PlaylistMessage.ModeUpdated))
            }
        }
    }

    /** 切换「启动自动播放」：媒体库与播放列表互斥，设置其一必须清掉另一个 */
    fun toggleAutoPlay(playlist: PlaylistUiModel) {
        viewModelScope.launch {
            runCatching {
                if (playlist.isAutoPlay) {
                    sp.autoPlayPlaylistId = ""
                    autoPlayId.value = ""
                    _events.send(PlaylistEvent.Message(PlaylistMessage.AutoPlayCleared))
                } else {
                    sp.autoPlayPlaylistId = playlist.id
                    autoPlayId.value = playlist.id
                    sp.autoPlayMediaId = ""
                    _events.send(PlaylistEvent.Message(PlaylistMessage.AutoPlaySet))
                }
            }
        }
    }
}
