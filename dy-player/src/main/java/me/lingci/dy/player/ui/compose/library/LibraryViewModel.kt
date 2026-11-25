package me.lingci.dy.player.ui.compose.library

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
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
import me.lingci.dy.player.room.entity.LibraryCardRow
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.ui.compose.sort.SortScope
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.compose.sort.writeSort
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.CoverHelper
import me.lingci.dy.player.util.CoverProcessor
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SeriesKey
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.decodeHtmlEntities
import me.lingci.lib.base.util.PermissionUtil

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

enum class LibraryPhase { Loading, Content, Empty, Error }

/** 一次性提示：只回传计数，文案由路由层解析，ViewModel 不碰 Context。 */
sealed interface LibraryEvent {
    /** 批量删除结束；[partial]/[failed] 对应未完整删除的卡片，仍然保持选中 */
    data class BatchDeleted(val deleted: Int, val failed: Int, val partial: Int = 0) : LibraryEvent
    data object AutoPlaySet : LibraryEvent
    data object AutoPlayCleared : LibraryEvent
}

internal data class SelectionState(
    val active: Boolean = false,
    val ids: Set<String> = emptySet()
) {
    fun prunedTo(media: List<MediaUiModel>): SelectionState =
        copy(ids = pruneSelection(ids, media.map { it.cardKey }))

    fun prunedTo(media: List<MediaUiModel>, phase: LibraryPhase, deleting: Boolean): SelectionState =
        if (deleting || phase == LibraryPhase.Loading || phase == LibraryPhase.Error) {
            this
        } else {
            prunedTo(media)
        }

    /** 删除期间新增的选择保留；已删除项移出，失败项是否保留取决于用户当时是否仍勾选。 */
    fun afterDelete(attempted: Set<String>, failed: Set<String>): SelectionState {
        if (!active) return this
        val remaining = ids - (attempted - failed)
        return if (remaining.isEmpty()) SelectionState() else copy(ids = remaining)
    }
}

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
    val hasMore: Boolean = true,
    val query: String = "",
    val filter: MediaLibType? = null,
    val totalCount: Int = 0,
    val availableTypes: List<MediaLibType> = emptyList(),
    /** 当前生效的排序字段与方向：SortBar 据此显示选中项，页面不另存一份状态。 */
    val sortField: LibrarySortField = LibrarySortField.LastPlayed,
    val sortAscending: Boolean = false,
    /** 封面宽高比：设置页改值后由 [LibraryViewModel.refreshCoverAspect] 灌回，网格不再写死 */
    val coverAspect: Float = 0.75f,
    /** 批量选择模式：集合存稳定卡片键，代表季切换不会丢选中 */
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    /** 批量删除进行中：禁用重复确认，并避免加载态提前剪掉失败项 */
    val deleting: Boolean = false,
    /** 启动自动播放目标；媒体库与播放列表互斥 */
    val autoPlayMediaId: String = ""
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
    private val sp = SpUtil(app)
    private val pageSize = MutableStateFlow(MediaDataDao.PAGE_SIZE)

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    /** 封面比例单独成流：设置页改完返回时刷新它，不必重跑数据库查询 */
    private val coverAspectFlow = MutableStateFlow(coverAspectOf(sp.coverRatio))

    /** 批量选择只是 UI 状态，单独成流，勾选/取消不会触发数据库重查 */
    private val selectionFlow = MutableStateFlow(SelectionState())
    private val deleteInProgress = MutableStateFlow(false)
    private val autoPlayMediaIdFlow = MutableStateFlow(sp.autoPlayMediaId.orEmpty())

    /** 排序偏好：改一次写一次 SP，并把新值灌回数据流重查（排序已下推到 SQL）。 */
    private val sortSpec = MutableStateFlow(sp.readLibrarySort())

    /** 权限是查询的输入：未授权直接给空表，授权后重订阅，不靠重建 UI 树刷新 */
    private val storageAccessFlow = MutableStateFlow(PermissionUtil.hasStorageAccess(app))
    private val queryFlow = MutableStateFlow("")
    private val typeFilterFlow = MutableStateFlow<MediaLibType?>(null)
    private val retryFlow = MutableStateFlow(0)

    private data class QueryFilters(val query: String, val type: MediaLibType?)
    private val filtersFlow = combine(queryFlow, typeFilterFlow, retryFlow) { query, type, _ ->
        QueryFilters(query, type)
    }

    private data class QueryInput(
        val sort: SortSpec,
        val pageSize: Int,
        val access: Boolean,
        val filters: QueryFilters
    )

    private sealed interface LibraryRows {
        data object Loading : LibraryRows
        data class Ready(val rows: List<LibraryCardRow>) : LibraryRows
        data object Failed : LibraryRows
    }

    private val mediaFlow = combine(
        sortSpec,
        pageSize,
        storageAccessFlow,
        filtersFlow
    ) { sort, size, access, filters ->
        QueryInput(sort, size, access, filters)
    }.flatMapLatest { input ->
        flow<LibraryRows> {
            emit(LibraryRows.Loading)
            if (!input.access) {
                emit(LibraryRows.Ready(emptyList()))
            } else {
                emitAll(
                    mediaDao.observeLibraryCardsRaw(
                        libraryPageQuery(
                            field = input.sort.modeOr(LibrarySortField.LastPlayed),
                            ascending = input.sort.ascending,
                            limit = input.pageSize + 1,
                            offset = 0,
                            query = input.filters.query,
                            type = input.filters.type
                        )
                    ).map { rows -> LibraryRows.Ready(rows) }
                )
            }
        }.catch { cause ->
            if (cause is CancellationException) throw cause
            emit(LibraryRows.Failed)
        }
    }.shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        replay = 1
    )

    val state: StateFlow<LibraryUiState> = combine(
        mediaFlow,
        DatabaseManager.sourceDataDao.observeAll(),
        DatabaseManager.playHistoryDao.observeResumeRows(RESUME_LIMIT),
        pageSize,
        sortSpec
    ) { media, sources, resume, size, sort ->
        Snapshot(media, sources, resume, size, sort)
    }
        .mapLatest { snapshot -> withContext(Dispatchers.IO) { buildState(snapshot) } }
        .combine(mediaDao.observeCount()) { ui, count ->
            ui.copy(totalCount = if (storageAccessFlow.value) count else 0)
        }
        .combine(mediaDao.observeLibraryTypes()) { ui, types ->
            ui.copy(
                availableTypes = if (storageAccessFlow.value) {
                    types.filter { it != MediaLibType.DEFAULT }
                } else {
                    emptyList()
                }
            )
        }
        .combine(queryFlow) { ui, query -> ui.copy(query = query) }
        .combine(typeFilterFlow) { ui, type -> ui.copy(filter = type) }
        .combine(coverAspectFlow) { ui, aspect -> ui.copy(coverAspect = aspect) }
        .combine(selectionFlow) { ui, selection -> ui to selection }
        .combine(deleteInProgress) { (ui, selection), deleting ->
            val pruned = selection.prunedTo(ui.media, ui.phase, deleting)
            if (pruned != selection) {
                selectionFlow.update { current ->
                    current.prunedTo(ui.media, ui.phase, deleting)
                }
            }
            ui.copy(
                selectionMode = pruned.active,
                selectedIds = pruned.ids,
                deleting = deleting
            )
        }
        .combine(autoPlayMediaIdFlow) { ui, mediaId ->
            ui.copy(autoPlayMediaId = mediaId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LibraryUiState(
                sortField = sortSpec.value.modeOr(LibrarySortField.LastPlayed),
                sortAscending = sortSpec.value.ascending,
                coverAspect = coverAspectFlow.value,
                autoPlayMediaId = autoPlayMediaIdFlow.value
            )
        )

    fun setQuery(query: String) {
        if (queryFlow.value == query) return
        queryFlow.value = query
        pageSize.value = MediaDataDao.PAGE_SIZE
    }

    fun setTypeFilter(type: MediaLibType?) {
        if (typeFilterFlow.value == type) return
        typeFilterFlow.value = type
        pageSize.value = MediaDataDao.PAGE_SIZE
    }

    fun clearFilters() {
        queryFlow.value = ""
        typeFilterFlow.value = null
        pageSize.value = MediaDataDao.PAGE_SIZE
    }

    fun retry() {
        retryFlow.update { it + 1 }
    }

    /** 设置页可能改完比例就返回：重读 SP；值没变时 StateFlow 不发射，不会触发重组 */
    fun refreshCoverAspect() {
        coverAspectFlow.value = coverAspectOf(sp.coverRatio)
    }

    fun refreshAutoPlay() {
        autoPlayMediaIdFlow.value = sp.autoPlayMediaId.orEmpty()
    }

    fun toggleAutoPlay(mediaId: String) {
        if (mediaId.isBlank()) return
        val clearing = sp.autoPlayMediaId == mediaId
        sp.autoPlayMediaId = if (clearing) "" else mediaId
        if (!clearing) sp.autoPlayPlaylistId = ""
        autoPlayMediaIdFlow.value = sp.autoPlayMediaId.orEmpty()
        viewModelScope.launch {
            _events.send(if (clearing) LibraryEvent.AutoPlayCleared else LibraryEvent.AutoPlaySet)
        }
    }

    /** 从系统设置返回等场景：权限可能变了，重订阅查询；值没变时不重查 */
    fun refreshStorageAccess() {
        storageAccessFlow.value = PermissionUtil.hasStorageAccess(getApplication())
    }

    /** 从长按菜单进入批量选择，并把当前卡片作为第一个选中项 */
    fun startSelection(cardKey: String) {
        selectionFlow.value = SelectionState(active = true, ids = setOf(cardKey))
    }

    fun toggleSelection(cardKey: String) {
        selectionFlow.update { current ->
            if (!current.active) {
                current
            } else {
                current.copy(
                    ids = if (cardKey in current.ids) current.ids - cardKey else current.ids + cardKey
                )
            }
        }
    }

    fun clearSelection() {
        selectionFlow.value = SelectionState()
    }

    /**
     * 批量删除选中的媒体库卡片。
     *
     * 多季卡片先展开成全部季再逐条删除；这里刻意不做整批回滚——已删掉的库连带的分集、历史、收藏
     * 都已经清掉了，回滚等于把删成功的又吐回来。失败卡片保持选中，用户可以立刻重试。
     *
     * 删除期间允许继续勾选，但用快照差集更新选择，避免完成时把用户刚选的新项覆盖掉。
     */
    fun deleteSelected(cardKeys: Collection<String>) {
        val attempted = cardKeys.toSet()
        if (attempted.isEmpty() || !deleteInProgress.compareAndSet(false, true)) return
        val cardsByKey = state.value.media.associateBy { it.cardKey }
        viewModelScope.launch {
            try {
                val failed = mutableSetOf<String>()
                val incomplete = mutableSetOf<String>()
                var deleted = 0
                var partial = 0
                attempted.forEach { cardKey ->
                    val card = cardsByKey[cardKey]
                    if (card == null) {
                        failed += cardKey
                        incomplete += cardKey
                        return@forEach
                    }
                    val mediaIds = try {
                        LibraryCompat.mediaIdsForDeletion(card.id, card.seriesKey)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Exception) {
                        failed += cardKey
                        incomplete += cardKey
                        return@forEach
                    }
                    var deletedSeasons = 0
                    mediaIds.forEach { id ->
                        if (LibraryCompat.deleteMedia(id, sp)) deletedSeasons++
                    }
                    when {
                        deletedSeasons == mediaIds.size -> deleted++
                        deletedSeasons > 0 -> {
                            partial++
                            incomplete += cardKey
                        }
                        else -> {
                            failed += cardKey
                            incomplete += cardKey
                        }
                    }
                }
                selectionFlow.update { it.afterDelete(attempted, incomplete) }
                _events.send(
                    LibraryEvent.BatchDeleted(deleted = deleted, failed = failed.size, partial = partial)
                )
            } finally {
                autoPlayMediaIdFlow.value = sp.autoPlayMediaId.orEmpty()
                deleteInProgress.value = false
            }
        }
    }

    /** 与设置页 SegmentedRow 的取值一一对应；历史脏值统一按竖版 3:4 兜底 */
    private fun coverAspectOf(value: String?): Float = when (value) {
        "1:1" -> 1f
        "4:3" -> 4f / 3f
        else -> 3f / 4f
    }

    /**
     * 切换排序字段。升降序沿用当前值：用户从「最近观看降序」切到「名称」时，
     * 期望的通常是「名称降序」这一脉相承的方向，而不是被打回升序。
     */
    fun setSortField(field: LibrarySortField) {
        val current = sortSpec.value
        if (current.modeOr(LibrarySortField.LastPlayed) == field) return
        applySort(SortSpec(field.name, current.ascending))
    }

    fun toggleSortOrder() {
        applySort(sortSpec.value.copy(ascending = !sortSpec.value.ascending))
    }

    private fun applySort(spec: SortSpec) {
        sp.writeSort(SortScope.Library, spec)
        sortSpec.value = spec
    }

    init {
        // 只补「还没有封面」的条目：DEFAULT 是允许自动匹配的初始态，AUTO 表示已匹配过，
        // 两者 showFile 都为空时都要补；CUSTOM 是用户自选，不参与
        viewModelScope.launch {
            mediaFlow
                .map { state ->
                    val rows = (state as? LibraryRows.Ready)?.rows.orEmpty()
                    rows.map { it.media }.filter {
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
        // SQL 已按完整组选出代表季；探针行只用于判断 hasMore，不进入 UI。
        val rows = (snapshot.media as? LibraryRows.Ready)?.rows.orEmpty()
        val items = rows.take(snapshot.pageSize).map { row ->
            val media = row.media
            val seriesTitle = if (row.seasonCount > 1) {
                SeriesKey.seriesTitleOf(media.path)
            } else {
                ""
            }
            media.toCard(
                sources = snapshot.sources,
                seriesTitle = seriesTitle,
                seasonCount = row.seasonCount.takeIf { it > 1 } ?: 0
            )
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
            phase = when (snapshot.media) {
                LibraryRows.Loading -> LibraryPhase.Loading
                LibraryRows.Failed -> LibraryPhase.Error
                is LibraryRows.Ready -> if (items.isEmpty()) LibraryPhase.Empty else LibraryPhase.Content
            },
            media = items,
            resumeItems = resumeItems,
            hasMore = snapshot.media is LibraryRows.Ready && snapshot.media.rows.size > snapshot.pageSize,
            sortField = snapshot.sort.modeOr(LibrarySortField.LastPlayed),
            sortAscending = snapshot.sort.ascending
        )
    }

    private data class Snapshot(
        val media: LibraryRows,
        val sources: List<SourceData>,
        val resume: List<me.lingci.dy.player.room.entity.ResumeRow>,
        val pageSize: Int,
        val sort: SortSpec
    )

    private companion object {
        const val RESUME_LIMIT = 4
        const val LIBRARY_PREFIX = "lib:"
    }
}

/**
 * 选中集合滤波：只保留仍出现在 [visible] 里的稳定卡片键。
 *
 * 一个都没被剔除时原样返回同一个实例，避免每次数据刷新都让 `LibraryUiState` 变一个新对象
 * 而引起整列重组。
 */
internal fun pruneSelection(ids: Set<String>, visible: Collection<String>): Set<String> {
    if (ids.isEmpty()) return ids
    val loaded = visible.toHashSet()
    val kept = ids.filterTo(LinkedHashSet(ids.size)) { it in loaded }
    return if (kept.size == ids.size) ids else kept
}
