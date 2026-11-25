package me.lingci.dy.player.ui.compose.detail

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.compose.components.ThumbCache
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.CoverHelper
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.MediaManger
import me.lingci.dy.player.util.SeriesKey
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.decodeHtmlEntities
import me.lingci.dy.player.util.thumb.RemoteThumbSource
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.isVideo
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 卡片渲染需要的全部数据，**不含 Room 实体**。
 *
 * 缩略图路径、宽高比、修改时间都在 ViewModel 的 IO 线程算好：
 * 卡片组合期只读字段，不碰磁盘、不算 md5，首帧就是最终尺寸，
 * 瀑布流不会因为图片晚到 / 比例修正而整体重排。
 */
@Immutable
data class ShortCardUiModel(
    val id: String,
    val title: String,
    val thumbPath: String?,
    val aspectRatio: Float
)

/**
 * 一条可直接渲染的展示项。
 *
 * [playIndex] 是该项在 [DetailUiState.playList] 中的下标：卡片按页增量加载后，
 * 可见位置和播放顺序不再一致，起播必须按这个下标取，不能用列表位置。
 */
@Immutable
data class DetailEntryUi(
    val card: ShortCardUiModel,
    val playIndex: Int,
    val lastPlay: Boolean
)

/** 季 Tab 的一项：只含渲染所需字段，不含 Room 实体。 */
@Immutable
data class SeasonUi(
    val mediaId: String,
    val label: String,
    val selected: Boolean
)

enum class DetailPhase { Loading, Content, Error }

/** 一次性事件：滚动位置属于 Screen 的本地状态，不塞进 UiState。 */
sealed interface DetailEvent {
    /** 目标项已经在 [DetailUiState.entries] 里，可以滚过去了 */
    data class ScrollToEntry(val index: Int) : DetailEvent
}

data class DetailUiState(
    val phase: DetailPhase = DetailPhase.Loading,
    val media: MediaData? = null,
    /** 封面已在读库时解析好：组件不再需要实体 + 资源列表才能画一张图 */
    val cover: MediaUiModel? = null,
    val shortMode: Boolean = false,
    val opOffset: Int = 0,
    val edOffset: Int = 0,
    val query: String = "",
    /** 当前生效的排序：SortBar 直接显示这两项，页面不再各存一份。 */
    val sortField: DetailSortField = DetailSortField.Default,
    val sortAscending: Boolean = true,
    /** 只包含「卡片已解析」的前 N 项：1000 个视频也不会在首帧全量读磁盘 */
    val entries: List<DetailEntryUi> = emptyList(),
    /** 过滤 + 排序后的完整播放顺序，供起播与「下一个」使用 */
    val playList: List<VideoData> = emptyList(),
    /** 当前过滤条件下的总条数（不是已加载条数） */
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    /** 「上次播放」在 [entries] / [playList] 里的下标；没有记录或被搜索过滤掉时为 null */
    val lastPlayedIndex: Int? = null,
    /** 正在补页以便跳到 [lastPlayedIndex]：按钮据此显示进度并防重复点击 */
    val jumpingToLastPlayed: Boolean = false,
    /** 本地媒体库缺少存储权限：空列表不是「没有视频」，而是根本读不到 */
    val needsStoragePermission: Boolean = false,
    /** 同剧全部季（≥2 项时显示季 Tab） */
    val seasons: List<SeasonUi> = emptyList(),
    /** 当前季名，供播放页季末提示 */
    val seasonLabel: String = "",
    /** 换季重建列表期间保留旧内容，只显示顶部细进度条，避免整屏闪白 */
    val switchingSeason: Boolean = false,
    /** 回前台重读当前媒体库；旧内容保持可见 */
    val refreshing: Boolean = false
)

private class StoragePermissionRequiredException : Exception()

class DetailViewModel(private val app: Application) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val _events = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = _events.receiveAsFlow()

    /** 全量视频 + 预先算好的 key / 标题：过滤、排序只碰内存，不再逐项 md5、逐项解 HTML 实体 */
    private var rows: List<VideoRow> = emptyList()

    /** 过滤 + 排序后的播放顺序，分页从这里切片 */
    private var ordered: List<VideoRow> = emptyList()

    private val sp = SpUtil(app)

    /**
     * 排序偏好（全局一套，`sort.detail`）。
     *
     * 放 ViewModel 字段而不是 UiState 的唯一来源：`load()` 会整体换新 state，
     * 排序若只存在 state 里就会在每次进页面时被打回默认。
     */
    private var sortSpec: SortSpec = sp.readDetailSort()

    /** 解析结果缓存：排序 / 搜索来回切换时不重复读盘，也不重复算比例 */
    private val cardCache = ConcurrentHashMap<String, ShortCardUiModel>()
    private val modifiedTimes = ConcurrentHashMap<String, Long>()

    /** 缩略图目录只解析一次：`externalCacheDir` 是 Binder 调用，按项取在千级列表里代价可观 */
    private val thumbDir: File by lazy {
        AppUtil.buildThumbFile(app, "probe").parentFile ?: File(app.cacheDir, ".thumb")
    }

    private var loadedEnd = 0

    /** 列表被整体重建（换库 / 搜索 / 排序）时自增，用于丢弃过期的分页结果 */
    private var generation = 0

    private var loadJob: Job? = null
    private var refreshJob: Job? = null
    private var pageJob: Job? = null
    private var queryJob: Job? = null
    private var jumpJob: Job? = null
    private var loadedId: String? = null

    /**
     * 剧集根库（根目录下是季子目录）的分组缓存。
     * 跨季切换直接复用，避免每次点 Tab 都重新 PROPFIND / 遍历磁盘；换库按 mediaId 自动失效。
     */
    private var seasonGroupsCache: Pair<String, List<SeasonGroup>>? = null
    private var activeSeasonDir: String = ""

    /**
     * 缩略图生成请求：只保留最新一批。
     *
     * 旧实现进详情页就对整个列表抽帧，1000 条视频等于上千次 MediaMetadataRetriever，
     * 几十秒内把磁盘和 CPU 占满——瀑布流再怎么优化也会掉帧。这里改成「为当前已加载的页生成」，
     * 用户快速滑过时旧请求会被新请求覆盖，不会积压。
     */
    private val thumbQueue = Channel<List<VideoData>>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (batch in thumbQueue) {
                var generated = 0
                for (video in batch) {
                    // HLS/DASH 清单抽不出帧；其余（本地 / WebDAV / SMB）统一走抽帧，
                    // 远程库的列表缩略图因此不再依赖先播放一次。
                    if (RemoteThumbSource.isManifest(video.videoUrl)) continue
                    MediaManger.scanVideoThumb(app, listOf(video))
                    generated++
                }
                if (generated > 0) refreshThumbPaths(batch)
            }
        }
    }

    /**
     * [keepContent] 用于换季：不把整页推回 Loading，旧列表继续可见，
     * 只由 [DetailUiState.switchingSeason] 驱动顶部细进度条；数据就绪后整体替换。
     */
    fun load(mediaId: String, force: Boolean = false, keepContent: Boolean = false) {
        if (!force && loadedId == mediaId && _state.value.phase == DetailPhase.Content) return
        loadedId = mediaId
        cancelJobs()
        generation++
        loadJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            if (keepContent) {
                _state.value = _state.value.copy(switchingSeason = true, refreshing = false)
            } else {
                _state.value = DetailUiState(phase = DetailPhase.Loading)
            }
            val readStart = SystemClock.elapsedRealtime()
            val base = try {
                withContext(Dispatchers.IO) { readBase(mediaId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StoragePermissionRequiredException) {
                _state.value = if (keepContent) {
                    _state.value.copy(switchingSeason = false, refreshing = false)
                } else {
                    DetailUiState(phase = DetailPhase.Error, needsStoragePermission = true)
                }
                return@launch
            } catch (t: Throwable) {
                null
            }
            val baseCost = SystemClock.elapsedRealtime() - readStart
            if (base == null) {
                _state.value = if (keepContent) {
                    _state.value.copy(switchingSeason = false, refreshing = false)
                } else {
                    DetailUiState(phase = DetailPhase.Error)
                }
                return@launch
            }
            rows = base.rows
            cardCache.clear()
            modifiedTimes.clear()
            // 排序偏好是跨页面记忆的：进页面就按上次选的顺序排，而不是先按默认顺序闪一下再排
            val sort = sortSpec
            if (sort.modeOr(DetailSortField.Default) == DetailSortField.Modified) ensureModifiedTimes()
            ordered = withContext(Dispatchers.Default) { filterAndSort(rows, "", sort) }

            // 首屏只解析一屏多一点：短视频库常常上千条，全量解析等于每次进页面都做一次全盘 IO
            val first = resolvePage(ordered, 0, FIRST_PAGE)
            loadedEnd = first.size
            _state.value = DetailUiState(
                phase = DetailPhase.Content,
                media = base.media,
                cover = base.cover,
                shortMode = base.shortMode,
                opOffset = base.media.opOffset,
                edOffset = base.media.edOffset,
                sortField = sort.modeOr(DetailSortField.Default),
                sortAscending = sort.ascending,
                entries = first,
                playList = ordered.map { it.video },
                totalCount = ordered.size,
                hasMore = loadedEnd < ordered.size,
                lastPlayedIndex = lastPlayedIndexOf(ordered),
                seasons = base.seasons,
                seasonLabel = base.seasonLabel
            )
            requestThumbs(ordered, 0, loadedEnd)
            // 验证用：1000 条视频时「元数据」随条数线性增长、「首屏卡片」恒等于一页的量
            Log.d(
                TAG,
                "首屏就绪 videos=${rows.size} 已解析=${first.size} 元数据=${baseCost}ms " +
                    "首屏卡片=${SystemClock.elapsedRealtime() - readStart - baseCost}ms"
            )
        }
    }

    fun refresh() {
        val mediaId = loadedId ?: return
        val current = _state.value
        if (current.phase != DetailPhase.Content || refreshJob?.isActive == true) return
        cancelJobs()
        val gen = ++generation
        seasonGroupsCache = null
        modifiedTimes.clear()
        refreshJob = viewModelScope.launch {
            _state.value = current.copy(refreshing = true)
            val base = try {
                withContext(Dispatchers.IO) { readBase(mediaId) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (base == null || gen != generation || loadedId != mediaId) {
                _state.value = _state.value.copy(refreshing = false)
                return@launch
            }
            val latest = _state.value
            if (latest.phase != DetailPhase.Content || gen != generation || loadedId != mediaId) return@launch
            queryJob?.cancel()
            pageJob?.cancel()
            jumpJob?.cancel()
            rows = base.rows
            cardCache.clear()
            val sort = sortSpec
            if (sort.modeOr(DetailSortField.Default) == DetailSortField.Modified) ensureModifiedTimes()
            ordered = withContext(Dispatchers.Default) { filterAndSort(rows, latest.query, sort) }
            val resolveCount = minOf(maxOf(FIRST_PAGE, latest.entries.size), ordered.size)
            val resolved = resolvePage(ordered, 0, resolveCount)
            loadedEnd = resolved.size
            _state.value = latest.copy(
                media = base.media,
                cover = base.cover,
                shortMode = base.shortMode,
                opOffset = base.media.opOffset,
                edOffset = base.media.edOffset,
                entries = resolved,
                playList = ordered.map { it.video },
                totalCount = ordered.size,
                hasMore = loadedEnd < ordered.size,
                lastPlayedIndex = lastPlayedIndexOf(ordered),
                seasons = base.seasons,
                seasonLabel = base.seasonLabel,
                jumpingToLastPlayed = false,
                switchingSeason = false,
                refreshing = false
            )
            requestThumbs(ordered, 0, loadedEnd)
        }
    }

    /** 季 Tab 点击：根库切季只换缓存里的分组；一季一库则切到对应 mediaId 重读。 */
    fun selectSeason(key: String) {
        if (key.isBlank()) return
        val groups = seasonGroupsCache?.second.orEmpty()
        val isSeasonDir = groups.any { it.path == key }
        if (isSeasonDir && key == activeSeasonDir) return
        if (!isSeasonDir && key == loadedId) return
        // 乐观更新：Tab 立刻高亮、季名立刻刷新；旧列表保留到新数据就绪，不闪全屏 Loading
        val current = _state.value
        val seasons = current.seasons.map { it.copy(selected = it.mediaId == key) }
        _state.value = current.copy(
            seasons = seasons,
            seasonLabel = seasons.firstOrNull { it.selected }?.label.orEmpty(),
            switchingSeason = true
        )
        if (isSeasonDir) {
            activeSeasonDir = key
            load(loadedId ?: return, force = true, keepContent = true)
        } else {
            activeSeasonDir = ""
            seasonGroupsCache = null
            load(key, force = true, keepContent = true)
        }
    }

    /** 滚动到底部附近时续一页；重复调用会被 pageJob 挡掉。 */
    fun loadMore() {
        val current = _state.value
        if (current.phase != DetailPhase.Content || !current.hasMore || current.refreshing) return
        // 定位跳转正在补页，此时再续页会出现两条流程同时改 entries / loadedEnd
        if (current.jumpingToLastPlayed) return
        if (pageJob?.isActive == true) return
        val start = loadedEnd
        val gen = generation
        pageJob = viewModelScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val page = resolvePage(ordered, start, PAGE_SIZE)
            if (gen != generation) return@launch
            if (page.isEmpty()) {
                _state.value = _state.value.copy(hasMore = false)
                return@launch
            }
            loadedEnd = start + page.size
            _state.value = _state.value.copy(
                entries = _state.value.entries + page,
                hasMore = loadedEnd < ordered.size
            )
            Log.d(
                TAG,
                "续页 ${start}..${loadedEnd - 1} 耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
            requestThumbs(ordered, start, loadedEnd)
        }
    }

    /**
     * 跳到「上次播放」。
     *
     * 瀑布流只能滚到已经存在于 [DetailUiState.entries] 里的项，而目标常常在几百项之后，
     * 所以先把中间缺的页补进来，**最后一次推状态**再让 Screen 滚过去——否则会边加载边滚、
     * 视口反复跳。千级列表补页在百毫秒量级（每项只做 stat + 读图片头），比让用户自己滑几百屏划算。
     */
    fun jumpToLastPlayed() {
        val current = _state.value
        if (current.phase != DetailPhase.Content || current.jumpingToLastPlayed || current.refreshing) return
        val target = current.lastPlayedIndex ?: return
        // 目标已经在列表里：直接滚，不用补页
        if (target < loadedEnd) {
            _events.trySend(DetailEvent.ScrollToEntry(target))
            return
        }
        val gen = generation
        val alreadyLoaded = current.entries.size
        pageJob?.cancel()
        jumpJob = viewModelScope.launch {
            _state.value = _state.value.copy(jumpingToLastPlayed = true)
            val startedAt = SystemClock.elapsedRealtime()
            val merged = _state.value.entries.toMutableList()
            var end = loadedEnd
            while (end <= target) {
                val page = resolvePage(ordered, end, PAGE_SIZE)
                if (page.isEmpty()) break
                merged += page
                end += page.size
            }
            if (gen != generation) return@launch
            loadedEnd = end
            _state.value = _state.value.copy(
                entries = merged,
                hasMore = loadedEnd < ordered.size,
                jumpingToLastPlayed = false
            )
            // 只为目标附近补缩略图：中间几百项都抽帧会把 CPU 占满，而队列是 CONFLATED 本来也只留最后一批
            requestThumbs(
                ordered,
                (target - FIRST_PAGE).coerceAtLeast(0),
                minOf(target + FIRST_PAGE, loadedEnd)
            )
            _events.trySend(DetailEvent.ScrollToEntry(target))
            Log.d(
                TAG,
                "定位上次播放 index=$target 补页=${merged.size - alreadyLoaded} " +
                    "耗时=${SystemClock.elapsedRealtime() - startedAt}ms"
            )
        }
    }

    /** 「上次播放」在给定顺序里的下标；被搜索过滤掉或没有记录时返回 null。 */
    private fun lastPlayedIndexOf(source: List<VideoRow>): Int? =
        source.indexOfFirst { it.video.lastPlay }.takeIf { it >= 0 }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        // 输入框立即回显，列表重建做防抖：连打十几个字只重建一次
        _state.value = _state.value.copy(query = query)
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            refresh(query = query)
        }
    }

    /** 切换排序字段：升降序沿用当前方向（从「名称升序」切到「修改时间」不重置方向）。 */
    fun setSortField(field: DetailSortField) {
        if (field == sortSpec.modeOr(DetailSortField.Default)) return
        applySort(SortSpec(field.name, sortSpec.ascending))
    }

    fun toggleSortOrder() {
        applySort(sortSpec.copy(ascending = !sortSpec.ascending))
    }

    /** 排序偏好落 SP 后立即重排：写盘与刷新都不需要用户再点一次。 */
    private fun applySort(spec: SortSpec) {
        sortSpec = spec
        sp.writeDetailSort(spec.modeOr(DetailSortField.Default), spec.ascending)
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            refresh(_state.value.query)
            // 换顺序后回到列表顶部：停在原来的滚动偏移上会看到「新顺序的中间一段」，像是排序没生效
            _events.send(DetailEvent.ScrollToEntry(0))
        }
    }

    /** 片头片尾偏移：先更新 UI 状态再落库，避免要等 IO 才刷新头部。 */
    fun updateOpEd(op: Int, ed: Int) {
        val media = _state.value.media ?: return
        media.opOffset = op
        media.edOffset = ed
        _state.value = _state.value.copy(opOffset = op, edOffset = ed)
        viewModelScope.launch(Dispatchers.IO) {
            MediaHelper.updateMedia(SpUtil(app), media)
        }
    }

    /**
     * 搜索 / 排序后的列表：先换顺序，再从第 0 页重新解析。
     * 已解析过的卡片从 [cardCache] 直接命中，只有新进入范围的项目才读盘。
     */
    private suspend fun refresh(query: String) {
        if (_state.value.phase != DetailPhase.Content) return
        generation++
        pageJob?.cancel()
        jumpJob?.cancel()
        val sort = sortSpec
        // 按修改时间排序才需要逐项 stat，默认顺序与名称排序完全不碰磁盘
        if (sort.modeOr(DetailSortField.Default) == DetailSortField.Modified) ensureModifiedTimes()

        val sorted = withContext(Dispatchers.Default) { filterAndSort(rows, query, sort) }
        ordered = sorted
        val first = resolvePage(ordered, 0, FIRST_PAGE)
        loadedEnd = first.size
        _state.value = _state.value.copy(
            query = query,
            sortField = sort.modeOr(DetailSortField.Default),
            sortAscending = sort.ascending,
            entries = first,
            playList = ordered.map { it.video },
            totalCount = ordered.size,
            hasMore = loadedEnd < ordered.size,
            lastPlayedIndex = lastPlayedIndexOf(ordered),
            jumpingToLastPlayed = false
        )
        requestThumbs(ordered, 0, loadedEnd)
    }

    private fun requestThumbs(source: List<VideoRow>, from: Int, end: Int) {
        if (from >= end || end > source.size) return
        thumbQueue.trySend(source.subList(from, end).map { it.video })
    }

    /**
     * 缩略图生成完成后把路径补回卡片。
     *
     * 只换图片、不动 [ShortCardUiModel.aspectRatio]：可见卡片的高度保持不变，瀑布流不会重排；
     * 下次进页面时卡片会重新解析，届时才用上真实比例。
     */
    private suspend fun refreshThumbPaths(batch: List<VideoData>) {
        val gen = generation
        val updated = withContext(Dispatchers.IO) {
            batch.mapNotNull { video ->
                val key = video.md5()
                val old = cardCache[key] ?: return@mapNotNull null
                val file = File(thumbDir, "$key.${AppUtil.THUMB_TYPE}")
                if (!file.exists()) return@mapNotNull null
                val path = file.absolutePath
                if (path == old.thumbPath) return@mapNotNull null
                old.copy(thumbPath = path)
            }
        }
        if (updated.isEmpty() || gen != generation) return
        updated.forEach { cardCache[it.id] = it }
        val byId = updated.associateBy { it.id }
        val entries = _state.value.entries.map { entry ->
            byId[entry.card.id]?.let { entry.copy(card = it) } ?: entry
        }
        _state.value = _state.value.copy(entries = entries)
    }
    private fun filterAndSort(source: List<VideoRow>, query: String, sort: SortSpec): List<VideoRow> {
        val keyword = query.trim()
        val filtered = if (keyword.isEmpty()) {
            source
        } else {
            source.filter { it.title.contains(keyword, ignoreCase = true) }
        }
        return when (sort.modeOr(DetailSortField.Default)) {
            // 默认顺序 = 集合自身的顺序：播放列表是「加入顺序 / 父目录自然序」，
            // 喜欢是收藏时间、历史是播放时间，本地/远端库是目录枚举顺序。这里不重排。
            DetailSortField.Default -> filtered
            DetailSortField.Name -> if (sort.ascending) {
                filtered.sortedBy { it.sortTitle }
            } else {
                filtered.sortedByDescending { it.sortTitle }
            }
            DetailSortField.Modified -> {
                val comparator = compareBy<VideoRow> { modifiedTimes[it.key] ?: 0L }
                filtered.sortedWith(if (sort.ascending) comparator else comparator.reversed())
            }
        }
    }

    /**
     * 批量解析一个区间的卡片。
     *
     * 每项要做的事只有「拼缩略图路径 + exists + 读图片头拿比例」，单次几十微秒，
     * 但乘以 1000 就是可感知的等待；按页解析、页内 4 路并发，单页稳定在十几毫秒量级。
     */
    private suspend fun resolvePage(
        source: List<VideoRow>,
        from: Int,
        size: Int
    ): List<DetailEntryUi> {
        if (from >= source.size || size <= 0) return emptyList()
        val end = minOf(from + size, source.size)
        val permits = Semaphore(PARALLEL_READS)
        return coroutineScope {
            (from until end).map { index ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        val row = source[index]
                        DetailEntryUi(
                            card = cardOf(row),
                            playIndex = index,
                            lastPlay = row.video.lastPlay
                        )
                    }
                }
            }.awaitAll()
        }
    }

    private fun cardOf(row: VideoRow): ShortCardUiModel {
        cardCache[row.key]?.let { return it }
        // 目录只取一次：externalCacheDir 是 Binder 调用，按项取在千级列表里非常可观
        val thumbFile = File(thumbDir, "${row.key}.${AppUtil.THUMB_TYPE}")
        val thumbPath = when {
            thumbFile.exists() -> thumbFile.absolutePath
            row.video.preview.isNotBlank() -> row.video.preview
            else -> null
        }
        val card = ShortCardUiModel(
            id = row.key,
            title = row.title,
            thumbPath = thumbPath,
            aspectRatio = ThumbCache.aspectRatio(thumbPath)
        )
        cardCache[row.key] = card
        return card
    }

    /** 按时间排序前一次性把全量 mtime 读进内存（IO + 4 路并发），排序本身只读 Map。 */
    private suspend fun ensureModifiedTimes() = withContext(Dispatchers.IO) {
        val missing = rows.filter { !modifiedTimes.containsKey(it.key) }
        if (missing.isEmpty()) return@withContext
        val permits = Semaphore(PARALLEL_READS)
        coroutineScope {
            missing.forEach { row ->
                launch { permits.withPermit { modifiedTimes[row.key] = row.video.modifiedTime() } }
            }
        }
    }

    /**
     * 一次 IO 读完「媒体 + 剧集 + 每项的 key/标题」，只有元数据就绪才推 Content。
     * 卡片（缩略图 / 比例）留给 [resolvePage] 分批做，因此这一步与视频数量近乎无关。
     */
    private suspend fun readBase(mediaId: String): DetailPayload? {
        val startedAt = SystemClock.elapsedRealtime()
        val media = DatabaseManager.mediaDataDao.getById(mediaId)
            // 播放列表只落在 playlist 表，media_data 里没有对应行。
            // 与 View 侧 PlaylistActivity.buildPlaylistMediaData 一致，按 playlist 还原临时 MediaData。
            ?: readPlaylistAsMedia(mediaId)
            ?: return null
        val sp = SpUtil(app)
        val shortMode = when {
            media.playMode == 1 -> true
            media.type == MediaLibType.LIKE -> true
            media.playMode == 0 -> !sp.longVideoMode
            else -> false
        }
        val listStart = SystemClock.elapsedRealtime()
        // 用户也可能通过「文件浏览 → 主目录」把剧集根直接加进媒体库：根下只有季子目录、
        // 没有视频。此时逐季下钻，季 Tab 由子目录构造，播放队列天然等于当前季；
        // 普通单级库 / 一季一库仍走原逻辑。
        val groups = seasonGroupsCache?.takeIf { it.first == mediaId }?.second
            ?: loadSeasonGroups(media).also { seasonGroupsCache = mediaId to it }
        val activeDir = activeSeasonDir.takeIf { dir -> groups.any { it.path == dir } }
            ?: groups.firstOrNull()?.path.orEmpty()
        activeSeasonDir = activeDir
        val videos = if (groups.isNotEmpty()) {
            groups.first { it.path == activeDir }.videos
        } else {
            loadVideos(media)
        }
        if (media.playLast.isNotBlank()) {
            videos.forEach { it.lastPlay = media.playLast == it.videoUrl }
        }
        val rowsStart = SystemClock.elapsedRealtime()
        val rows = videos.map { video ->
            // 存量 name 带 `&#58;` 等实体，在这里解码一次，列表只拿干净文本
            VideoRow(video = video, title = video.name.decodeHtmlEntities())
        }
        Log.d(
            TAG,
            "元数据拆分 videos=${videos.size} 查库=${listStart - startedAt}ms " +
                "取列表=${rowsStart - listStart}ms 建行=${SystemClock.elapsedRealtime() - rowsStart}ms"
        )
        // 封面解析并进这次 IO：WebDAV 需要按资源算认证头，放到组件里会让每帧都重算。
        // 即使没有可用封面也建模型：标题/类型本来就在里面，组件不必再拿实体
        val candidate = CoverHelper.resolveCover(media, DatabaseManager.sourceDataDao.list())
        val cover = MediaUiModel(
            id = media.id,
            title = media.title.decodeHtmlEntities(),
            type = media.type,
            coverPath = candidate?.path,
            coverHeaders = candidate?.headers ?: emptyMap()
        )
        // 季 Tab 来源二选一：根库下钻出的季子目录 > 同 series_key 的一季一库集合。
        // 老库（v1 升级上来）series_key 可能为空，这里按 path 现算兜底，避免升级后聚合失效。
        val runtimeSeriesKey = SeriesKey.seriesKeyOf(media.path, media.type, media.storageId)
            .ifBlank { media.seriesKey }
        val seasonLabel = if (groups.isNotEmpty()) {
            groups.first { it.path == activeDir }.label
        } else {
            SeriesKey.seasonLabelOf(media.path)
        }
        val seasons = when {
            groups.isNotEmpty() -> groups.sortedWith(
                compareBy<SeasonGroup> { SeriesKey.seasonNumberOf(it.label) }.thenBy { it.label }
            ).map { group ->
                SeasonUi(
                    mediaId = group.path,
                    label = group.label,
                    selected = group.path == activeDir
                )
            }
            runtimeSeriesKey.isBlank() -> emptyList()
            else -> DatabaseManager.mediaDataDao.listLibrary()
                // 不能只按 series_key 查库：旧版扫描 / 文件浏览「添加媒体库」曾直调 insert
                // 绕过 LibraryCompat 打标，这些行 series_key 恒为空串，SQL 查不到。
                // 这里按 path 现算兜底，与首页一剧一卡的 groupBy 口径保持一致。
                .filter { candidate ->
                    SeriesKey.seriesKeyOf(candidate.path, candidate.type, candidate.storageId)
                        .ifBlank { candidate.seriesKey } == runtimeSeriesKey
                }
                .sortedWith(
                    compareBy<MediaData> { SeriesKey.seasonNumberOf(SeriesKey.seasonLabelOf(it.path)) }
                        .thenBy { it.title }
                )
                .map { season ->
                    SeasonUi(
                        mediaId = season.id,
                        label = SeriesKey.seasonLabelOf(season.path)
                            .ifBlank { season.title.decodeHtmlEntities() },
                        selected = season.id == media.id
                    )
                }
        }
        return DetailPayload(media, rows, shortMode, cover, seasons, seasonLabel)
    }

    /**
     * 由 playlist 主表 + playlist_item/video_data 还原详情页所需的 PLAYLIST 类型 [MediaData]。
     *
     * Compose 的播放列表页与历史 PLAYLIST 分组都只把 playlistId 交给 `detail/{mediaId}` 路由，
     * 而 View 侧是先把 Playlist 实体拼成 MediaData 再传进 MediaDetailActivity 的。
     * 缺了这一步，`mediaDataDao.getById` 必然查空，详情页直接落到 Error 态。
     *
     * [MediaData.items] 是 @Ignore 字段，这里直接填好，[loadVideos] 的 PLAYLIST 分支不再二次查库。
     * 该对象只存在于内存：不要拿它去 `MediaHelper.updateMedia` 落库。
     */
    private suspend fun readPlaylistAsMedia(playlistId: String): MediaData? {
        val startedAt = SystemClock.elapsedRealtime()
        val playlist = DatabaseManager.playlistDao.getById(playlistId) ?: return null
        // 一条 JOIN 取回全部视频，替代「1 次全量 playlist_item + N 次 video_data 单条查」的 N+1
        val items = DatabaseManager.playlistItemDao.listVideosByPlaylist(playlistId)
        Log.d(TAG, "片单取数 items=${items.size} cost=${SystemClock.elapsedRealtime() - startedAt}ms")
        return MediaData(
            id = playlist.id,
            title = playlist.title,
            type = MediaLibType.PLAYLIST,
            storageType = StorageType.LOCAL_STORAGE,
            playMode = playlist.playMode,
            items = items.toMutableList()
        )
    }

    private suspend fun loadVideos(media: MediaData): List<VideoData> {
        return when (media.type) {
            MediaLibType.HISTORY -> DatabaseManager.playHistoryDao.listHistoryVideos()
            MediaLibType.LIKE -> DatabaseManager.favoriteDao.listFavoriteVideos()
            MediaLibType.PLAYLIST -> {
                val items = if (media.items.isNotEmpty()) media.items
                else DatabaseManager.playlistItemDao.listVideosByPlaylist(media.id)
                LibraryCompat.sortByParentPath(items.toMutableList())
            }
            MediaLibType.DEFAULT, MediaLibType.LOCAL -> {
                // 权限被拒时 File.listFiles 返回 null，不能当成「空媒体库」推给 UI
                if (!PermissionUtil.hasStorageAccess(app)) {
                    throw StoragePermissionRequiredException()
                }
                val path = media.path.ifBlank { FileOperator.movieFolder.path }
                FileOperator.getSortedFiles(File(path), FileOperator.VIDEO_EXTENSIONS).map { VideoData(it) }
            }
            // 浏览器“添加到媒体库”只落 MediaData，不会把远端目录扫进 video_data；
            // 这里按绑定资源库实时列目录，与旧版 MediaDetailActivity.handleRemoteStorage 一致。
            MediaLibType.WEBDAV, MediaLibType.SMB -> loadRemoteVideos(media)
            else -> DatabaseManager.videoDataDao.listByMedia(media.id)
        }
    }

    private suspend fun loadRemoteVideos(media: MediaData): List<VideoData> {
        val sources = DatabaseManager.sourceDataDao.list()
        val storageId = LibraryCompat.effectiveStorageId(media, sources) ?: return emptyList()
        val source = sources.firstOrNull { it.id == storageId } ?: return emptyList()
        val storage = source.toStorage() ?: return emptyList()
        return try {
            if (!storage.testConnect()) return emptyList()
            val videos = mutableListOf<VideoData>()
            storage.listFile(media.path, false).collect { item ->
                if (item.isFile && item.name.isVideo()) {
                    videos += VideoData().apply {
                        name = item.name
                        videoUrl = storage.fullPath(item.path)
                        putToken(storage.getToken())
                        type = source.storageType()
                        parentPath = item.path.substringBeforeLast("/").substringAfterLast("/")
                        mediaId = media.id
                        // 远端没有本地文件可 stat，「按修改时间排序」只能靠列目录带回的时间戳
                        lastModified = item.lastModified
                        id = md5()
                    }
                }
            }
            videos
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "加载远端媒体库失败: ${media.title} ${e.message}")
            emptyList()
        } finally {
            storage.release()
        }
    }

    /**
     * 剧集根库：根目录只有季子目录时逐季下钻。
     * 返回空列表表示「普通单级库」，由 [loadVideos] 按原逻辑处理。
     */
    private suspend fun loadSeasonGroups(media: MediaData): List<SeasonGroup> = when (media.type) {
        MediaLibType.DEFAULT, MediaLibType.LOCAL -> loadLocalSeasonGroups(media)
        MediaLibType.WEBDAV, MediaLibType.SMB -> loadRemoteSeasonGroups(media)
        else -> emptyList()
    }

    private fun loadLocalSeasonGroups(media: MediaData): List<SeasonGroup> {
        if (!PermissionUtil.hasStorageAccess(app)) return emptyList()
        val root = File(media.path.ifBlank { FileOperator.movieFolder.path })
        val children = root.listFiles()?.toList() ?: return emptyList()
        // 根下直接有视频 → 普通单级库，不要下钻（避免把散落子目录误判成季）
        if (children.any { it.isFile && it.name.isVideo() }) return emptyList()
        val dirs = children.filter { it.isDirectory && !it.name.startsWith(".") }
        if (dirs.isEmpty()) return emptyList()
        // 单季新番也是「主目录 + 一个 Season 子目录」，只要求有子目录即可下钻；
        // 过滤掉不含视频的子目录（字幕 / 花絮 / 空文件夹），避免把整库判成空。
        return dirs.map { dir ->
            SeasonGroup(
                label = dir.name,
                path = dir.path,
                videos = FileOperator.getSortedFiles(dir, FileOperator.VIDEO_EXTENSIONS)
                    .map { VideoData(it) }
            )
        }.filter { it.videos.isNotEmpty() }
    }

    private suspend fun loadRemoteSeasonGroups(media: MediaData): List<SeasonGroup> {
        val sources = DatabaseManager.sourceDataDao.list()
        val storageId = LibraryCompat.effectiveStorageId(media, sources) ?: return emptyList()
        val source = sources.firstOrNull { it.id == storageId } ?: return emptyList()
        val storage = source.toStorage() ?: return emptyList()
        return try {
            if (!storage.testConnect()) return emptyList()
            val rootEntries = storage.listFile(media.path, false).toList()
            if (rootEntries.any { it.isFile && it.name.isVideo() }) return emptyList()
            val dirs = rootEntries.filter {
                !it.isFile && it.name.isNotBlank() && !it.name.startsWith(".") && !it.returnParent
            }
            if (dirs.isEmpty()) return emptyList()
            // 单季新番同样只有一个 Season 子目录；只保留真正含视频的分组
            dirs.map { dir ->
                val videos = storage.listFile(dir.path, false).toList()
                    .filter { it.isFile && it.name.isVideo() }
                    .map { item ->
                        VideoData().apply {
                            name = item.name
                            videoUrl = storage.fullPath(item.path)
                            putToken(storage.getToken())
                            type = source.storageType()
                            parentPath = item.path.substringBeforeLast("/").substringAfterLast("/")
                            mediaId = media.id
                            id = md5()
                        }
                    }
                SeasonGroup(label = dir.name, path = dir.path, videos = videos)
            }.filter { it.videos.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "剧集根逐季列目录失败: ${media.title} ${e.message}")
            emptyList()
        } finally {
            storage.release()
        }
    }

    /** 根目录的一个季子目录：只保留渲染与起播需要的字段。 */
    private class SeasonGroup(
        val label: String,
        val path: String,
        val videos: List<VideoData>
    )

    private fun cancelJobs() {
        loadJob?.cancel()
        refreshJob?.cancel()
        pageJob?.cancel()
        queryJob?.cancel()
        jumpJob?.cancel()
    }

    /** 渲染行：HTML 实体解码、排序用的小写标题提前算好；md5 留到真正要用（解析卡片 / 时间排序）时才算。 */
    private class VideoRow(val video: VideoData, val title: String) {
        val sortTitle: String = title.lowercase()

        /** 缩略图文件名 / 缓存 key：上千条视频时全量 md5 是纯浪费，按页惰性算即可 */
        val key: String by lazy { video.md5() }
    }

    private class DetailPayload(
        val media: MediaData,
        val rows: List<VideoRow>,
        val shortMode: Boolean,
        val cover: MediaUiModel,
        val seasons: List<SeasonUi>,
        val seasonLabel: String
    )

    /**
     * 排序用的修改时间。
     *
     * 远端条目（WebDAV/SMB/在线）没有可 stat 的本地文件，时间来自列目录时的 [VideoData.lastModified]；
     * 早期实现只判断本地存储、远端恒返回 0，导致「按修改时间排序」在远端库上等于没排。
     */
    private fun VideoData.modifiedTime(): Long = when {
        lastModified > 0L -> lastModified
        type == StorageType.LOCAL_STORAGE -> File(videoUrl).lastModified()
        else -> 0L
    }

    companion object {
        private const val TAG = "DetailViewModel"

        /** 首屏一页：3 列手机约 4~5 屏，足够撑住第一次滑动 */
        private const val FIRST_PAGE = 24

        /** 续页：一次解析 72 条，约等于 6 屏，滑到底之前必然追上 */
        private const val PAGE_SIZE = 72

        /** 页内并发：4 路足够喂满闪存，又不至于把 IO 队列打满拖慢首屏 */
        private const val PARALLEL_READS = 4

        private const val SEARCH_DEBOUNCE_MS = 250L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("DetailViewModel 需要 Application 来计算缩略图路径")
                DetailViewModel(app)
            }
        }
    }
}
