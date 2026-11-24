package me.lingci.dy.player.ui.compose.history

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.HistoryGroupSummary
import me.lingci.dy.player.room.entity.PlayHistory
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.CoverHelper
import me.lingci.dy.player.util.SpUtil

/**
 * 历史页条目：分组项（媒体库 / 播放列表）与单文件项在 UI 上渲染差异很大，
 * 用 sealed 表达，避免「一半字段为 null」的可空组合。
 *
 * 全部字段在 ViewModel 侧算好：分组项的封面已解析成「路径 + 认证头」，
 * 单文件项的缩略图路径已按 md5 换算，Composable 不再读库、不碰磁盘。
 */
@Immutable
sealed interface HistoryItemUi {

    /** 列表稳定 key（分组项 = 来源 + key，单文件项 = 来源 + 视频 id） */
    val key: String
    val title: String
    val lastPlayedAt: Long
    val progress: Long
    val duration: Long

    @Immutable
    data class Group(
        override val key: String,
        val sourceType: PlaySource,
        val sourceKey: String,
        /** 跳详情页用；来源已删除时仍保留解析结果，由 [exists] 决定是否可点 */
        val mediaId: String,
        override val title: String,
        val epCount: Int,
        override val lastPlayedAt: Long,
        override val progress: Long,
        override val duration: Long,
        val coverPath: String?,
        val coverHeaders: Map<String, String>,
        /** 对应的媒体 / 播放列表是否还在库内（标题显示「已删除」，不可跳转） */
        val exists: Boolean
    ) : HistoryItemUi

    @Immutable
    data class Single(
        override val key: String,
        val videoId: String,
        val sourceKey: String,
        override val title: String,
        val thumbPath: String?,
        override val lastPlayedAt: Long,
        override val progress: Long,
        override val duration: Long
    ) : HistoryItemUi
}

enum class HistoryPhase { Loading, Content, Empty, Error }

@Immutable
data class HistoryUiState(
    val phase: HistoryPhase = HistoryPhase.Loading,
    val items: List<HistoryItemUi> = emptyList(),
    /** 当前生效的排序：SortBar 直接读它，页面不另存状态。 */
    val sortField: HistorySortField = HistorySortField.Time,
    val sortAscending: Boolean = false
)

/** 一次性事件：起播需要完整 VideoData，放在事件里不污染状态。 */
sealed interface HistoryEvent {
    data class PlaySingle(val video: VideoData) : HistoryEvent
}

/**
 * 历史页 ViewModel。
 *
 * 数据来源是三条 Flow：分组聚合、单文件历史、单文件视频实体。合并后按 id 关联，
 * 一次补全媒体快照（封面 / 标题），不再对每条记录各查一次库。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val historyDao = DatabaseManager.playHistoryDao

    private val sp = SpUtil(app)

    /** 排序偏好（`sort.history`）：默认时间倒序，与旧版「最近看的在最上面」一致。 */
    private val sortSpec = MutableStateFlow(sp.readHistorySort())

    /** 播放形态偏好：读一次即固定，改变它需要重启进程（与旧入口行为一致） */
    val shortVideoMode: Boolean = !SpUtil(app).longVideoMode

    private val _events = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = _events.receiveAsFlow()

    val state: StateFlow<HistoryUiState> = combine(
        historyDao.observeGroupedHistory(),
        historyDao.observeStandaloneHistory(),
        DatabaseManager.videoDataDao.observeStandaloneHistoryVideos(),
        sortSpec
    ) { groups, standalone, videos, sort -> Snapshot(groups, standalone, videos, sort) }
        .mapLatest { snapshot ->
            withContext(Dispatchers.IO) {
                buildState(snapshot.groups, snapshot.standalone, snapshot.videos, snapshot.sort)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(
                sortField = sortSpec.value.modeOr(HistorySortField.Time),
                sortAscending = sortSpec.value.ascending
            )
        )

    /** 切换排序字段：升降序沿用当前方向，写 SP 后立即重排（不需要重新查库）。 */
    fun setSortField(field: HistorySortField) {
        if (field == sortSpec.value.modeOr(HistorySortField.Time)) return
        applySort(SortSpec(field.name, sortSpec.value.ascending))
    }

    fun toggleSortOrder() {
        applySort(sortSpec.value.copy(ascending = !sortSpec.value.ascending))
    }

    private fun applySort(spec: SortSpec) {
        sp.writeHistorySort(spec.modeOr(HistorySortField.Time), spec.ascending)
        sortSpec.value = spec
    }

    fun deleteItem(item: HistoryItemUi) {
        viewModelScope.launch {
            runCatching {
                when (item) {
                    is HistoryItemUi.Group ->
                        historyDao.deleteBySource(item.sourceType, item.sourceKey)

                    is HistoryItemUi.Single ->
                        historyDao.deleteByVideoAndSource(item.videoId, item.sourceKey)
                }
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch { runCatching { historyDao.deleteAll() } }
    }

    fun playSingle(item: HistoryItemUi.Single) {
        viewModelScope.launch {
            val video = withContext(Dispatchers.IO) {
                DatabaseManager.videoDataDao.getById(item.videoId)
            } ?: VideoData(id = item.videoId, videoUrl = "", name = item.title)
            _events.send(HistoryEvent.PlaySingle(video))
        }
    }

    private suspend fun buildState(
        groups: List<HistoryGroupSummary>,
        standalone: List<PlayHistory>,
        videos: List<VideoData>,
        sort: SortSpec
    ): HistoryUiState {
        val mediaById = DatabaseManager.mediaDataDao.list().associateBy { it.id }
        val sources = DatabaseManager.sourceDataDao.list()
        val videoById = videos.associateBy { it.id }

        val groupItems = groups.map { group ->
            val source = PlaySource.fromValue(group.sourceType)
            val (title, exists) = when (source) {
                PlaySource.LIBRARY -> {
                    val mediaId = group.sourceKey.removePrefix(LIBRARY_PREFIX)
                    val media = if (mediaId != group.sourceKey) mediaById[mediaId] else null
                    (media?.title ?: DELETED_TITLE) to (media != null)
                }

                PlaySource.PLAYLIST -> {
                    val hasPlaylist = group.sourceKey.startsWith(PLAYLIST_PREFIX) &&
                        group.playlistTitle != null
                    (group.playlistTitle ?: DELETED_TITLE) to hasPlaylist
                }

                PlaySource.STANDALONE -> DELETED_TITLE to false
            }
            val mediaId = when (source) {
                PlaySource.LIBRARY -> group.sourceKey.removePrefix(LIBRARY_PREFIX)
                PlaySource.PLAYLIST -> group.sourceKey.removePrefix(PLAYLIST_PREFIX)
                PlaySource.STANDALONE -> ""
            }
            val cover = when (source) {
                PlaySource.LIBRARY -> mediaById[mediaId]
                else -> null
            }?.let { CoverHelper.resolveCover(it, sources) }

            HistoryItemUi.Group(
                key = "${group.sourceType}|${group.sourceKey}",
                sourceType = source,
                sourceKey = group.sourceKey,
                mediaId = mediaId,
                title = title,
                epCount = group.epCount,
                lastPlayedAt = group.lastPlayedAt,
                progress = group.progress,
                duration = group.duration,
                coverPath = cover?.path,
                coverHeaders = cover?.headers ?: emptyMap(),
                exists = exists
            )
        }

        val singleItems = standalone.map { history ->
            val video = videoById[history.videoId]
            HistoryItemUi.Single(
                key = "${PlaySource.STANDALONE.value}|${history.sourceKey}|${history.videoId}",
                videoId = history.videoId,
                sourceKey = history.sourceKey,
                title = video?.name ?: DELETED_TITLE,
                // 缩略图路径在这里换算：md5 + 目录拼接属于 IO，不该每次重组重算
                thumbPath = video?.let { AppUtil.buildThumbFile(getApplication(), it.md5()).absolutePath },
                lastPlayedAt = history.updatedAt,
                progress = history.progress,
                duration = history.duration
            )
        }

        // 集合行与单文件行混排：先合并再整体排序，否则「按时间」会被分组顺序打断
        val merged = groupItems + singleItems
        val sorted = when (sort.modeOr(HistorySortField.Time)) {
            HistorySortField.Time -> {
                val comparator = compareBy<HistoryItemUi> { it.lastPlayedAt }
                if (sort.ascending) merged.sortedWith(comparator) else merged.sortedWith(comparator.reversed())
            }
            HistorySortField.Name -> {
                val comparator = compareBy<HistoryItemUi> { it.title.lowercase() }
                if (sort.ascending) merged.sortedWith(comparator) else merged.sortedWith(comparator.reversed())
            }
        }
        return HistoryUiState(
            phase = if (sorted.isEmpty()) HistoryPhase.Empty else HistoryPhase.Content,
            items = sorted,
            sortField = sort.modeOr(HistorySortField.Time),
            sortAscending = sort.ascending
        )
    }

    private data class Snapshot(
        val groups: List<HistoryGroupSummary>,
        val standalone: List<PlayHistory>,
        val videos: List<VideoData>,
        val sort: SortSpec
    )

    private companion object {
        const val LIBRARY_PREFIX = "lib:"
        const val PLAYLIST_PREFIX = "pl:"
        const val DELETED_TITLE = "已删除"
    }
}
