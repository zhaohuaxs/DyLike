package me.lingci.dy.player.ui.compose.migration

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.migrate.MigrationExecutor
import me.lingci.dy.player.migrate.MigrationResult
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log

/**
 * 七项旧数据规模。预统计与迁移结果共用同一形态，页面切换到结果态时列不变、只换数字。
 */
@Immutable
data class MigrationCounts(
    val sources: Int = 0,
    val media: Int = 0,
    val videos: Int = 0,
    val history: Int = 0,
    val favorites: Int = 0,
    val playlists: Int = 0,
    val playlistItems: Int = 0,
    val shuffles: Int = 0
) {
    val isEmpty: Boolean
        get() = sources == 0 && media == 0 && videos == 0 && history == 0 &&
                favorites == 0 && playlists == 0 && playlistItems == 0 && shuffles == 0
}

enum class MigrationPhase { Loading, Prepared, Running, Done, Failed }

/** 迁移成功后展示的统计（[MigrationResult] 的可变字段在此收敛为不可变快照） */
@Immutable
data class MigrationOutcome(
    val counts: MigrationCounts,
    val skippedShuffles: Int = 0,
    val skippedExisting: Int = 0,
    val hasVirtualMedia: Boolean = false,
    val elapsedMs: Long = 0
)

/**
 * 失败阶段决定「重试」重跑哪一步：预统计失败重读 SP，执行失败重跑迁移。
 * 两者不能混为一谈——预统计都没读出来时直接执行迁移只会掩盖问题。
 */
@Immutable
data class MigrationError(
    val duringPreparation: Boolean,
    val detail: String
)

@Immutable
data class MigrationUiState(
    val phase: MigrationPhase = MigrationPhase.Loading,
    val preview: MigrationCounts = MigrationCounts(),
    val outcome: MigrationOutcome? = null,
    val error: MigrationError? = null
) {
    val running: Boolean get() = phase == MigrationPhase.Running

    /** 统计卡片数据源：完成后展示实际写入数，其余阶段展示预统计 */
    val shownCounts: MigrationCounts get() = outcome?.counts ?: preview
}

/**
 * SP → Room 迁移页 ViewModel。
 *
 * 执行逻辑整体复用 [MigrationExecutor]（单事务、失败整体回滚、标记写入 app_meta），
 * 这里只负责三件事：进页面预统计旧数据规模、执行迁移、把结果摊成页面状态。
 */
class MigrationViewModel(app: Application) : AndroidViewModel(app) {

    private val sp: SpUtil by lazy { SpUtil(getApplication()) }

    private val _state = MutableStateFlow(MigrationUiState())
    val state: StateFlow<MigrationUiState> = _state.asStateFlow()

    init {
        prepare()
    }

    /** 预统计：只解析 SP JSON，不写库、不触发迁移 */
    fun prepare() {
        if (_state.value.phase == MigrationPhase.Running) return
        _state.value = MigrationUiState(phase = MigrationPhase.Loading)
        viewModelScope.launch {
            val counts = runCatching {
                withContext(Dispatchers.IO) { readCounts() }
            }.getOrElse { error ->
                Log.d(this@MigrationViewModel, "migration preview failed", error)
                _state.value = MigrationUiState(
                    phase = MigrationPhase.Failed,
                    error = MigrationError(duringPreparation = true, detail = error.message.orEmpty())
                )
                return@launch
            }
            _state.value = MigrationUiState(phase = MigrationPhase.Prepared, preview = counts)
        }
    }

    /** 开始迁移（单事务，任一步失败整体回滚，不留半迁移数据） */
    fun start() {
        if (_state.value.phase == MigrationPhase.Running) return
        val preview = _state.value.preview
        _state.value = _state.value.copy(phase = MigrationPhase.Running, error = null)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    MigrationExecutor.perform(getApplication(), SpUtil(getApplication()))
                }
            }.onSuccess { result ->
                _state.value = MigrationUiState(
                    phase = MigrationPhase.Done,
                    preview = preview,
                    outcome = result.toOutcome()
                )
            }.onFailure { error ->
                Log.d(this@MigrationViewModel, "migration failed", error)
                _state.value = _state.value.copy(
                    phase = MigrationPhase.Failed,
                    error = MigrationError(duringPreparation = false, detail = error.message.orEmpty())
                )
            }
        }
    }

    fun retry() {
        val error = _state.value.error
        // 预统计阶段失败时 SP 内容没读到，重跑迁移没有意义
        if (error?.duringPreparation == true) prepare() else start()
    }

    private fun readCounts(): MigrationCounts {
        // SPManager.string 委托返回可空 String?，需 orEmpty() 后再解析
        val sources = JsonUtil.toList<SourceData>(sp.sourceJson.orEmpty())
        val media = JsonUtil.toList<MediaData>(sp.mediaJson.orEmpty())
        val history = JsonUtil.toList<VideoData>(sp.historyJson.orEmpty())
        val likes = JsonUtil.toList<VideoData>(sp.likeJson.orEmpty())
        val playlists = JsonUtil.toList<MediaData>(sp.playlistJson.orEmpty())
        val shuffleMap = runCatching {
            JsonUtil.toEntity<Map<String, MediaShuffleState>>(sp.mediaShuffleJson.orEmpty())
        }.getOrDefault(emptyMap())
        return MigrationCounts(
            sources = sources.size,
            media = media.count { it.type.isLibraryType },
            // 视频总数含三个来源：媒体库 items / 历史 / 收藏 / 播放列表 items
            videos = media.sumOf { it.items.size } + history.size +
                    likes.size + playlists.sumOf { it.items.size },
            history = history.size,
            favorites = likes.size,
            playlists = playlists.size,
            playlistItems = playlists.sumOf { it.items.size },
            shuffles = shuffleMap.size
        )
    }

    private fun MigrationResult.toOutcome() = MigrationOutcome(
        counts = MigrationCounts(
            sources = sources,
            media = media,
            videos = videos,
            history = history,
            favorites = favorites,
            playlists = playlists,
            playlistItems = playlistItems,
            shuffles = shuffles
        ),
        skippedShuffles = skippedShuffles,
        skippedExisting = skippedExisting,
        hasVirtualMedia = virtualMedia,
        elapsedMs = elapsedMs
    )
}
