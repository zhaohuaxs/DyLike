package me.lingci.dy.player.ui.compose.settings

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.migrate.MigrationChecker
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.dm.view.common.DmInitializer
import java.io.File

/**
 * 设置的唯一状态源。
 *
 * 每个二级页只调用与自己相关的那一个 `applyXxx`，写入时按字段 diff，
 * 不会因为点一下开关就把几十个 SP 键全写一遍。
 *
 * 实例以 **Activity** 为 owner 创建（见 [settingsViewModel]），
 * 「我的」与各二级页读到的是同一个对象，改完返回上一页不会出现旧值。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val sp = SpUtil(app)

    private val _state = MutableStateFlow(
        SettingsState(
            player = readPlayer(),
            // 这三项不在 readKernel/readDanmaku 里：它们复用旧 SP 键（sortRender / dmCurrentFont / dmFilter），
            // 在这里补齐，避免为了三个字段去改已经稳定的读取函数
            kernel = readKernel().copy(shortFastRender = sp.sortRender),
            danmaku = readDanmaku().copy(
                fontPath = sp.dmCurrentFont.orEmpty(),
                filterKeywords = parseFilterKeywords(sp.dmFilter.orEmpty())
            ),
            lab = readLab()
        )
    )

    val state: StateFlow<SettingsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val pending = runCatching { MigrationChecker.isMigrationPending() }.getOrDefault(false)
            update { it.copy(lab = it.lab.copy(migrationPending = pending)) }
        }
    }

    // ---------- 播放与资源库 ----------

    fun applyPlayer(updated: PlayerSettingsState) {
        val current = _state.value.player
        if (updated == current) return

        if (updated.longVideoMode != current.longVideoMode) sp.longVideoMode = updated.longVideoMode
        if (updated.videoDetailMode != current.videoDetailMode) sp.videoDetailMode = updated.videoDetailMode
        if (updated.newHome != current.newHome) sp.newHome = updated.newHome
        if (updated.autoNext != current.autoNext) sp.autoNext = updated.autoNext
        if (updated.loopList != current.loopList) sp.loopList = updated.loopList
        if (updated.longVideoPip != current.longVideoPip) sp.longVideoPip = updated.longVideoPip
        if (updated.longVideoBackgroundPlay != current.longVideoBackgroundPlay) {
            sp.longVideoBackgroundPlay = updated.longVideoBackgroundPlay
        }
        if (updated.audioFadeEnabled != current.audioFadeEnabled) sp.audioFadeEnabled = updated.audioFadeEnabled
        if (updated.audioFadeInDuration != current.audioFadeInDuration) {
            sp.audioFadeInDuration = updated.audioFadeInDuration
        }
        if (updated.audioFadeOutDuration != current.audioFadeOutDuration) {
            sp.audioFadeOutDuration = updated.audioFadeOutDuration
        }
        if (updated.shortRandom != current.shortRandom) sp.shortRandom = updated.shortRandom
        if (updated.coverRatio != current.coverRatio) sp.coverRatio = updated.coverRatio

        update { it.copy(player = updated) }
    }

    // ---------- 内核 ----------

    fun applyKernel(updated: KernelSettingsState) {
        val current = _state.value.kernel
        if (updated == current) return

        if (updated.longVideoCore != current.longVideoCore) sp.dyPlayerCore = updated.longVideoCore
        if (updated.shortVideoCore != current.shortVideoCore) sp.shortDyPlayerCore = updated.shortVideoCore
        if (updated.exoNetwork != current.exoNetwork) {
            sp.useOkhttp = updated.exoNetwork == ExoNetworkRoute.OkHttp
        }
        if (updated.exoRenderSurface != current.exoRenderSurface) sp.surfaceRender = updated.exoRenderSurface
        if (updated.exoSharpen != current.exoSharpen) sp.labMpvSuperResolution = updated.exoSharpen
        if (updated.exoSharpenStrength != current.exoSharpenStrength) {
            sp.labSuperResolutionStrength = updated.exoSharpenStrength
        }
        if (updated.mpvOutput != current.mpvOutput) sp.mpvVideoOutput = updated.mpvOutput.value
        if (updated.mpvHwdec != current.mpvHwdec) sp.mpvHwdec = updated.mpvHwdec.value
        if (updated.mpvSequentialRead != current.mpvSequentialRead) {
            sp.labMpvSequentialRead = updated.mpvSequentialRead
        }
        if (updated.mpvSpecialRender != current.mpvSpecialRender) {
            sp.labMpvSpecialRender = updated.mpvSpecialRender
        }
        if (updated.skipTlsVerify != current.skipTlsVerify) {
            // key 名沿用 labMpvSkipTlsVerify：MpvMediaPlayer / ExoMediaSourceHelper 都按这个字符串读，
            // 改名会让已经打开的开关失效，而且 MPV 那边是跨模块字符串约定。
            sp.labMpvSkipTlsVerify = updated.skipTlsVerify
        }
        if (updated.shortFastRender != current.shortFastRender) sp.sortRender = updated.shortFastRender

        update { it.copy(kernel = updated) }
    }

    // ---------- 弹幕 ----------

    fun applyDanmaku(updated: DanmakuSettingsState) {
        val current = _state.value.danmaku
        if (updated == current) return

        if (updated.fontPath != current.fontPath) sp.dmCurrentFont = updated.fontPath
        if (updated.filterKeywords != current.filterKeywords) {
            sp.dmFilter = updated.filterKeywords.joinToString(DmInitializer.FILTER_SEPARATOR)
        }
        if (updated.gradient != current.gradient) sp.dmGradientMode = updated.gradient
        if (updated.gradientRatio != current.gradientRatio) sp.dmGradientRatio = updated.gradientRatio
        if (updated.keepTextColor != current.keepTextColor) {
            sp.dmGradientWithTextColor = updated.keepTextColor
        }
        if (updated.strokeMultipleMode != current.strokeMultipleMode) {
            sp.dmStrokeMultipleMode = updated.strokeMultipleMode
        }
        if (updated.strokeMultiple != current.strokeMultiple) sp.dmStrokeMultiple = updated.strokeMultiple
        if (updated.mergeMode != current.mergeMode) sp.dmMergeMode = updated.mergeMode
        if (updated.mergeShow != current.mergeShow) sp.dmMergeShow = updated.mergeShow
        if (updated.mergeTop != current.mergeTop) sp.dmMergeTop = updated.mergeTop
        if (updated.showTime != current.showTime) sp.dmShowTime = updated.showTime
        if (updated.showFps != current.showFps) sp.showDmFps = updated.showFps
        if (updated.fontMode != current.fontMode) sp.dmFontMode = updated.fontMode
        if (updated.filterMode != current.filterMode) sp.dmFilterMode = updated.filterMode

        update { it.copy(danmaku = updated) }
    }

    // ---------- 实验室 ----------

    fun applyLab(updated: LabSettingsState) {
        val current = _state.value.lab
        if (updated == current) return

        if (updated.longVideoPortrait != current.longVideoPortrait) {
            sp.labLongVideoPortrait = updated.longVideoPortrait
        }
        if (updated.autoPlayOnLaunch != current.autoPlayOnLaunch) {
            sp.autoPlayOnLaunch = updated.autoPlayOnLaunch
        }
        if (updated.shortAutoLoadProgress != current.shortAutoLoadProgress) {
            sp.shortAutoLoadProgress = updated.shortAutoLoadProgress
        }
        if (updated.debugMode != current.debugMode) sp.debugMode = updated.debugMode

        update { it.copy(lab = updated) }
    }

    // ---------- 读取 ----------

    /**
     * 导入弹幕字体：把选中的字体复制进应用私有目录后再写偏好。
     *
     * 旧页面在 Fragment 里做同样的事（AppFile.buildCustom("font", name)），
     * 搬到 ViewModel 是为了让 Compose 页面只负责拿路径，不持 IO 逻辑。
     * 选完字体顺手打开字体开关，否则用户选完看不到任何变化。
     */
    fun applyDanmakuFont(sourcePath: String) {
        if (sourcePath.isBlank()) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val source = File(sourcePath)
                    if (!source.exists()) return@runCatching null
                    val target = AppFile(app).buildCustom("font", source.name)
                    if (!target.exists()) source.copyTo(target)
                    target
                }.getOrNull()
            } ?: return@launch

            sp.dmCurrentFont = copied.path
            sp.dmFontMode = true
            update {
                it.copy(
                    danmaku = it.danmaku.copy(
                        fontPath = copied.path,
                        fontName = copied.nameWithoutExtension,
                        fontMode = true
                    )
                )
            }
        }
    }

    private fun parseFilterKeywords(raw: String): List<String> =
        raw.split(DmInitializer.FILTER_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun readPlayer() = PlayerSettingsState(
        longVideoMode = sp.longVideoMode,
        videoDetailMode = sp.videoDetailMode,
        newHome = sp.newHome,
        autoNext = sp.autoNext,
        loopList = sp.loopList,
        longVideoPip = sp.longVideoPip,
        longVideoBackgroundPlay = sp.longVideoBackgroundPlay,
        audioFadeEnabled = sp.audioFadeEnabled,
        audioFadeInDuration = sp.audioFadeInDuration,
        audioFadeOutDuration = sp.audioFadeOutDuration,
        shortRandom = sp.shortRandom,
        coverRatio = sp.coverRatio ?: DEFAULT_COVER_RATIO
    )

    private fun readKernel() = KernelSettingsState(
        longVideoCore = DyPlayerCore.fromValue(sp.videoPlayerCore),
        shortVideoCore = sp.shortDyPlayerCore,
        exoNetwork = if (sp.useOkhttp) ExoNetworkRoute.OkHttp else ExoNetworkRoute.System,
        exoRenderSurface = sp.surfaceRender,
        exoSharpen = sp.labMpvSuperResolution,
        exoSharpenStrength = sp.labSuperResolutionStrength,
        mpvOutput = MpvVideoOutput.fromValue(sp.mpvVideoOutput),
        mpvHwdec = MpvHwdec.fromValue(sp.mpvHwdec),
        mpvSequentialRead = sp.labMpvSequentialRead,
        mpvSpecialRender = sp.labMpvSpecialRender,
        skipTlsVerify = sp.labMpvSkipTlsVerify
    )

    private fun readDanmaku() = DanmakuSettingsState(
        gradient = sp.dmGradientMode,
        gradientRatio = sp.dmGradientRatio,
        keepTextColor = sp.dmGradientWithTextColor,
        strokeMultipleMode = sp.dmStrokeMultipleMode,
        strokeMultiple = sp.dmStrokeMultiple,
        mergeMode = sp.dmMergeMode,
        mergeShow = sp.dmMergeShow,
        mergeTop = sp.dmMergeTop,
        showTime = sp.dmShowTime,
        showFps = sp.showDmFps,
        fontMode = sp.dmFontMode,
        fontName = fontDisplayName(sp.dmCurrentFont),
        filterMode = sp.dmFilterMode
    )

    private fun readLab() = LabSettingsState(
        longVideoPortrait = sp.labLongVideoPortrait,
        autoPlayOnLaunch = sp.autoPlayOnLaunch,
        shortAutoLoadProgress = sp.shortAutoLoadProgress,
        debugMode = sp.debugMode
    )

    private fun fontDisplayName(path: String?): String {
        val name = path?.takeIf { it.isNotBlank() }?.let { File(it).nameWithoutExtension }.orEmpty()
        return name
    }

    private inline fun update(block: (SettingsState) -> SettingsState) {
        _state.value = block(_state.value)
    }

    private companion object {
        const val DEFAULT_COVER_RATIO = "3:4"
    }
}

/**
 * 取 Activity 作用域的共享实例。
 *
 * 不能用 `viewModel()` 的默认 owner——在 NavHost 里那是当前目的地的 NavBackStackEntry，
 * 每个二级页各一个实例，等于没共享，改完返回上一页还是旧值。
 */
@Composable
fun settingsViewModel(): SettingsViewModel {
    val activity = LocalContext.current.findComponentActivity()
    return if (activity != null) {
        viewModel(viewModelStoreOwner = activity)
    } else {
        // 预览等没有 Activity 的场景退回默认 owner，保证 @Preview 可用
        viewModel()
    }
}

private fun Context.findComponentActivity(): ComponentActivity? {
    var ctx: Context? = this
    while (ctx != null) {
        if (ctx is ComponentActivity) return ctx
        ctx = (ctx as? ContextWrapper)?.baseContext
    }
    return null
}
