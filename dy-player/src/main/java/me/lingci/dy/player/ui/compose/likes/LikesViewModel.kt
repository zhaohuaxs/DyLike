package me.lingci.dy.player.ui.compose.likes

import android.app.Application
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.decodeHtmlEntities

enum class LikesPhase { Loading, Content, Empty }

/**
 * 「我的喜欢」里的一张卡。
 *
 * [thumbPath] 已在 ViewModel 的 IO 段解析好：缩略图文件存在就用它，否则退回 `preview` 地址。
 * 之前这段判断写在 Composable 里，等于每次重组都做一次 `File.exists()`。
 */
@Immutable
data class LikeItemUi(
    /** 列表稳定 key，同时是缩略图文件名（VideoData.md5()） */
    val key: String,
    val title: String,
    val thumbPath: String?
)

@Immutable
data class LikesUiState(
    val phase: LikesPhase = LikesPhase.Loading,
    val items: List<LikeItemUi> = emptyList(),
    /** 当前生效的排序：SortBar 直接读它，页面不另存状态。 */
    val sortField: LikesSortField = LikesSortField.Liked,
    val sortAscending: Boolean = false
)

/**
 * 起播请求。
 *
 * 播放层契约是 `startPlayback(List<VideoData>)`，所以 [VideoData] 只能以一次性事件的形态出现，
 * 不进 [LikesUiState] —— 与历史页 `HistoryEvent.PlaySingle` 同一套做法。
 */
sealed interface LikesEvent {
    data class Play(val videos: List<VideoData>, val index: Int) : LikesEvent

    /** 一次性提示：ViewModel 只持有资源 id，文案由 Route 解析 */
    data class Message(@param:StringRes val textRes: Int) : LikesEvent
}

class LikesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(LikesUiState())
    val state: StateFlow<LikesUiState> = _state.asStateFlow()

    private val _events = Channel<LikesEvent>(Channel.BUFFERED)
    val events: Flow<LikesEvent> = _events.receiveAsFlow()

    /** 起播用的原始列表；只在这里流转，不暴露给 UI。 */
    private var videos: List<VideoData> = emptyList()

    /** 收藏表返回的原始顺序（收藏时间倒序）：`Liked` 的正序只是把它反一下，不另外查时间戳。 */
    private var rawVideos: List<VideoData> = emptyList()

    private val sp = SpUtil(app)

    private val sortSpec = MutableStateFlow(sp.readLikesSort())

    init {
        viewModelScope.launch {
            DatabaseManager.favoriteDao.observeFavoriteVideos().collect { list ->
                rawVideos = list
                rebuild()
            }
        }
    }

    /** 切换排序字段：升降序沿用当前方向，写 SP 后立即重排，不重新查库。 */
    fun setSortField(field: LikesSortField) {
        if (field == sortSpec.value.modeOr(LikesSortField.Liked)) return
        applySort(SortSpec(field.name, sortSpec.value.ascending))
    }

    fun toggleSortOrder() {
        applySort(sortSpec.value.copy(ascending = !sortSpec.value.ascending))
    }

    private fun applySort(spec: SortSpec) {
        sp.writeLikesSort(spec.modeOr(LikesSortField.Liked), spec.ascending)
        sortSpec.value = spec
        viewModelScope.launch { rebuild() }
    }

    /**
     * 重排并重建卡片。
     *
     * [videos] 必须与 [items] 同序：起播靠下标定位，两者顺序不一致就会播到别的视频。
     * 缩略图路径换算留在 IO 线程，排序只碰内存里的标题。
     */
    private suspend fun rebuild() {
        val sort = sortSpec.value
        val field = sort.modeOr(LikesSortField.Liked)
        val sorted = when (field) {
            LikesSortField.Liked -> if (sort.ascending) rawVideos.reversed() else rawVideos
            LikesSortField.Name -> {
                val byName = rawVideos.sortedBy { it.name.decodeHtmlEntities().lowercase() }
                if (sort.ascending) byName else byName.reversed()
            }
        }
        videos = sorted
        val items = withContext(Dispatchers.IO) { sorted.map { it.toItemUi() } }
        _state.value = LikesUiState(
            phase = if (items.isEmpty()) LikesPhase.Empty else LikesPhase.Content,
            items = items,
            sortField = field,
            sortAscending = sort.ascending
        )
    }

    /** 点某张卡起播：下标在 UI 侧产出，整份列表由这里补齐。 */
    fun play(index: Int) {
        val list = videos
        if (index !in list.indices) return
        viewModelScope.launch { _events.send(LikesEvent.Play(list, index)) }
    }

    /**
     * 取消喜欢：按稳定 key 找到视频，只删 favorite 表的那一行（按 video_id 唯一键），
     * 与 View 侧 `MediaDetailActivity.removeFromLike` 一致。
     *
     * 列表由 `observeFavoriteVideos()` 的 Flow 自动重发，这里不手改 state，
     * 避免内存列表和数据库结果打架；传 key 而非下标，删除/刷新后不会串位。
     */
    fun unlike(key: String) {
        val video = videos.firstOrNull { it.md5() == key } ?: return
        viewModelScope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { DatabaseManager.favoriteDao.deleteByVideoId(video.beanId()) }.isSuccess
            }
            _events.send(
                LikesEvent.Message(
                    if (removed) R.string.hint_removed_from_like else R.string.likes_remove_failed
                )
            )
        }
    }

    private fun VideoData.toItemUi(): LikeItemUi {
        val thumb = AppUtil.buildThumbFile(getApplication(), md5())
        val path = when {
            thumb.exists() -> thumb.absolutePath
            preview.isNotBlank() -> preview
            else -> null
        }
        return LikeItemUi(
            key = md5(),
            title = name.decodeHtmlEntities(),
            thumbPath = path
        )
    }
}
