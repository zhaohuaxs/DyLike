package me.lingci.dy.player.ui.compose.library

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.dao.MediaDataDao
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.CoverHelper
import me.lingci.dy.player.util.CoverProcessor
import me.lingci.dy.player.util.SeriesKey
import me.lingci.dy.player.util.decodeHtmlEntities

/** 「继续观看」一项：文本与封面路径都在 ViewModel 里算好，卡片只做渲染。 */
@Immutable
data class ResumeItemUi(
    val mediaId: String,
    val mediaTitle: String,
    val videoName: String,
    val progress: Float,
    val remainingMs: Long,
    val coverPath: String?
)

enum class LibraryPhase { Loading, Content, Empty }

/**
 * 首页状态。
 *
 * [media] 已是 UI 模型：标题解码、类型、封面（路径 + 认证头）都在 ViewModel 里算好，
 * 列表只做渲染，Room 实体不再渗出到组件层（规范 §11 P2.1）。
 */
@Immutable
data class LibraryUiState(
    val phase: LibraryPhase = LibraryPhase.Loading,
    val media: List<MediaUiModel> = emptyList(),
    val resumeItems: List<ResumeItemUi> = emptyList(),
    val hasMore: Boolean = true
)

/**
 * 媒体库首页 ViewModel。
 *
 * 三件事从 Composable 收回：
 * - 分页查询：`pageSize` 是数据流的输入（`flatMapLatest`），不是 UI 里自增的 `mutableStateOf`；
 * - 「继续观看」：一条联查替代「逐条 getById」，缩略图路径在 IO 线程换算；
 * - 封面补全：以前挂在 `LaunchedEffect` 上（会随重组反复触发），现在只有列表数据变化才跑，
 *   补完的媒体 `showFile` 非空，集合自然收敛为空，不会自激。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val mediaDao = DatabaseManager.mediaDataDao
    private val pageSize = MutableStateFlow(MediaDataDao.PAGE_SIZE)

    private val mediaFlow = pageSize.flatMapLatest { mediaDao.observeLibraryPage(it, 0) }

    val state: StateFlow<LibraryUiState> = combine(
        mediaFlow,
        DatabaseManager.sourceDataDao.observeAll(),
        DatabaseManager.playHistoryDao.observeResumeRows(RESUME_LIMIT),
        pageSize
    ) { media, sources, resume, size ->
        Snapshot(media, sources, resume, size)
    }
        .mapLatest { snapshot -> withContext(Dispatchers.IO) { buildState(snapshot) } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState()
        )

    init {
        // 只补「还没有封面」的条目：DEFAULT 是允许自动匹配的初始态，AUTO 表示已匹配过，
        // 两者 showFile 都为空时都要补；CUSTOM 是用户自选，不参与
        viewModelScope.launch {
            mediaFlow
                .map { list ->
                    list.filter {
                        it.type == MediaLibType.LOCAL &&
                            it.coverType != CoverType.CUSTOM &&
                            it.showFile.isBlank()
                    }
                }
                .distinctUntilChanged()
                .collect { pending ->
                    if (pending.isNotEmpty()) {
                        CoverProcessor.processCovers(getApplication(), pending)
                    }
                }
        }
    }

    /**
     * 滚动到底时扩容一页。
     *
     * 不需要额外的「正在加载」闸门：`pageSize` 是 state 的输入，扩容后 state 会立刻
     * 用「旧列表 + 新 pageSize」重发一次，此时 `hasMore` 变 false，页面的触发条件自然失效，
     * 直到新一页数据回来才恢复。
     */
    fun loadMore() = pageSize.update { it + MediaDataDao.PAGE_SIZE }

    private fun MediaData.toCard(
        sources: List<SourceData>,
        seriesTitle: String = "",
        seasonCount: Int = 0
    ): MediaUiModel {
        val candidate = CoverHelper.resolveCover(this, sources)
        return MediaUiModel(
            id = id,
            title = seriesTitle.ifBlank { title.decodeHtmlEntities() },
            type = type,
            coverPath = candidate?.path,
            coverHeaders = candidate?.headers ?: emptyMap(),
            seriesKey = seriesKey,
            seasonCount = seasonCount
        )
    }

    private fun buildState(snapshot: Snapshot): LibraryUiState {
        // 封面解析放在这里（已经在 IO 线程）：WebDAV 要按资源算认证头，
        // 交给组件就等于让每张卡片各自去猜，且实体一变整列都要重组
        // 一剧一卡：同一 series_key 的多季聚合为一张卡片，代表季优先取「上次播放」所在季。
        // path 语义不变，底层仍是一季一行；只有当前页内的行参与聚合（PAGE_SIZE=200）。
        val items = snapshot.media
            // v1 升级上来的老行 series_key 为空（迁移回填只覆盖当时能识别的季目录名），
            // 这里按 path 现算兜底，保证老数据也能聚合成一剧一卡。
            .groupBy { media ->
                SeriesKey.seriesKeyOf(media.path, media.type, media.storageId)
                    .ifBlank { media.seriesKey }
            }
            .flatMap { (key, group) ->
                if (key.isBlank() || group.size < 2) {
                    group.map { media -> media.toCard(snapshot.sources) }
                } else {
                    val ordered = group.sortedWith(
                        compareBy<MediaData> { SeriesKey.seasonNumberOf(SeriesKey.seasonLabelOf(it.path)) }
                            .thenBy { it.title }
                    )
                    val representative = ordered.firstOrNull { it.playLast.isNotBlank() } ?: ordered.first()
                    listOf(
                        representative.toCard(
                            sources = snapshot.sources,
                            seriesTitle = SeriesKey.seriesTitleOf(representative.path)
                                .ifBlank { representative.title.decodeHtmlEntities() },
                            seasonCount = ordered.size
                        )
                    )
                }
            }
        // 一库一张卡：SQL 侧已按 (updated_at, id) 定位每组唯一一行，这里再去一次重
        // 作为 UI 契约的兜底——mediaId 同时是 LazyRow 的稳定 key，重复即崩
        // （`Key "..." was already used`，历史时间戳并列时最容易触发）。
        val resumeItems = snapshot.resume.distinctBy { it.sourceKey }.map { row ->
            val mediaId = row.sourceKey.removePrefix(LIBRARY_PREFIX)
            val thumbFile = AppUtil.buildThumbFile(getApplication(), row.videoId)
            val progress = if (row.duration > 0) {
                (row.progress.toFloat() / row.duration).coerceIn(0f, 1f)
            } else {
                0f
            }
            ResumeItemUi(
                mediaId = mediaId,
                mediaTitle = row.mediaTitle.decodeHtmlEntities(),
                videoName = row.videoName.decodeHtmlEntities(),
                progress = progress,
                remainingMs = (row.duration - row.progress).coerceAtLeast(0),
                coverPath = if (thumbFile.exists()) thumbFile.absolutePath else null
            )
        }
        return LibraryUiState(
            phase = when {
                items.isEmpty() -> LibraryPhase.Empty
                else -> LibraryPhase.Content
            },
            media = items,
            resumeItems = resumeItems,
            hasMore = snapshot.media.size >= snapshot.pageSize
        )
    }

    private data class Snapshot(
        val media: List<MediaData>,
        val sources: List<SourceData>,
        val resume: List<me.lingci.dy.player.room.entity.ResumeRow>,
        val pageSize: Int
    )

    private companion object {
        const val RESUME_LIMIT = 4
        const val LIBRARY_PREFIX = "lib:"
    }
}
