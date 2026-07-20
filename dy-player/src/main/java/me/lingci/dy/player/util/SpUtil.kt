package me.lingci.dy.player.util

import android.content.Context
import android.preference.PreferenceManager
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.ShortTitleStrategy
import me.lingci.lib.base.util.SpBase

/**
 * SharedPreferences
 */
open class SpUtil(context: Context) : SpBase(context) {

    private val preferences = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /**
     * New persisted player core id. Reading performs the one-time migration from videoPlayerExo;
     * runtime code should use this or dyPlayerCore and should not read the legacy boolean directly.
     */
    var videoPlayerCore: Int
        get() {
            if (!preferences.contains(KEY_VIDEO_PLAYER_CORE)) {
                // One-time migration from the old boolean: true stays Exo, false becomes MPV because
                // dy-player no longer ships IJK.
                val migratedCore = if (preferences.contains(KEY_VIDEO_PLAYER_EXO)) {
                    DyPlayerCore.fromLegacyExo(preferences.getBoolean(KEY_VIDEO_PLAYER_EXO, true))
                } else {
                    DyPlayerCore.EXO
                }
                preferences.edit().putInt(KEY_VIDEO_PLAYER_CORE, migratedCore.value).apply()
            }
            return DyPlayerCore.fromValue(
                preferences.getInt(KEY_VIDEO_PLAYER_CORE, DyPlayerCore.EXO.value)
            ).value
        }
        set(value) {
            // Normalize unknown persisted/restored values back to a supported enum.
            preferences.edit()
                .putInt(KEY_VIDEO_PLAYER_CORE, DyPlayerCore.fromValue(value).value)
                .apply()
        }

    var dyPlayerCore: DyPlayerCore
        get() = DyPlayerCore.fromValue(videoPlayerCore)
        set(value) {
            videoPlayerCore = value.value
        }

    var isFirst by SPManager.boolean(true)

    var dataSchemaVersion by SPManager.int(0)

    var sourceJson by SPManager.string("[]")

    var mediaJson by SPManager.string("[]")

    var historyJson by SPManager.string("[]")

    var likeJson by SPManager.string("[]")

    var playlistJson by SPManager.string("[]")

    var dayStr by SPManager.string("")

    var videoDetailMode by SPManager.boolean(false)

    var longVideoMode by SPManager.boolean(true)

    var shortRandom by SPManager.boolean(true)

    var dmGradientMode by SPManager.boolean(false)

    var dmGradientRatio by SPManager.int(40)

    var dmGradientWithTextColor by SPManager.boolean(false)

    var dmStrokeMultipleMode by SPManager.boolean(false)

    var dmStrokeMultiple by SPManager.float(1.2f)

    var dmFontMode by SPManager.boolean(false)

    var dmCurrentFont by SPManager.string("")

    var firstScanMovie by SPManager.boolean(true)

    // Generic render preferences are non-MPV only; DyPlayerCoreRegistry installs MPV's required
    // Surface renderer regardless of these persisted switches.
    var surfaceRender by SPManager.boolean(false)

    var dmMergeMode by SPManager.boolean(false)

    var dmShowTime by SPManager.boolean(false)

    var dmMergeTop by SPManager.int(9)

    var dmMergeShow by SPManager.int(9)

    var dmFilterMode by SPManager.boolean(false)

    var dmFilter by SPManager.string("")

    var browserUsedAll by SPManager.boolean(false)

    var browserSort by SPManager.int(-1)

    var browserShowHide by SPManager.boolean(false)

    var newHome by SPManager.boolean(false)

    var sortRender by SPManager.boolean(false)

    // Exo-only HTTP stack option. It remains persisted when MPV is selected but MPV does not use it.
    var useOkhttp by SPManager.boolean(false)

    var autoNext by SPManager.boolean(true)

    var loopList by SPManager.boolean(true)

    var showShortTitle by SPManager.boolean(true)

    // 短视频标题策略：RAW(0)原始显示 / SPLIT_ALL(1)分隔符换行 / FIRST_LINE(2)首行提取 / REGEX_FIRST(3)正则首行+分隔符
    var shortTitleStrategy by SPManager.int(ShortTitleStrategy.RAW.value)

    // 短视频标题分隔符（单字符，默认 "-"；非法时格式化器回退为 "-"）
    var shortTitleDelimiter by SPManager.string("-")

    // 短视频标题首行提取正则（仅 REGEX_FIRST 模式用；空则回退为 FIRST_LINE 语义）
    var shortTitleRegex by SPManager.string("")

    // 短视频标题最大行数（超过则末行合并剩余；0 表示不限制）
    var shortTitleMaxLines by SPManager.int(0)

    var showShortLike by SPManager.boolean(true)

    var showShortComment by SPManager.boolean(true)

    var showShortSeekbar by SPManager.boolean(true)

    var showShortPager by SPManager.boolean(true)

    var shortPlayNext by SPManager.boolean(false)

    var showSysBar by SPManager.boolean(true)

    var shortLifeSpeed by SPManager.float(0.5f)

    var shortRightSpeed by SPManager.float(2.0f)

    var showShortMore by SPManager.boolean(true)

    var shortVideoPlayerCore by SPManager.int(DyPlayerCore.EXO.value)

    var shortDyPlayerCore: DyPlayerCore
        get() = DyPlayerCore.fromValue(shortVideoPlayerCore)
        set(value) { shortVideoPlayerCore = value.value }

    // 音频渐入渐出设置
    var audioFadeEnabled by SPManager.boolean(false)

    var audioFadeInDuration by SPManager.int(1000)

    var audioFadeOutDuration by SPManager.int(1000)

    var coverRatio by SPManager.string("3:4")

    var mediaShuffleJson by SPManager.string("{}")

    var labSurfaceRgba by SPManager.boolean(false)

    var labSurfaceZOrder by SPManager.boolean(false)

    var labMpvSpecialRender by SPManager.boolean(true)

    // 长视频竖屏播放：开启后长视频默认竖屏，点击旋转按钮可切换横屏全屏
    var labLongVideoPortrait by SPManager.boolean(false)

    // 长视频画中画：开启后按 Home 键自动进入 PiP 小窗（需系统支持）
    var longVideoPip by SPManager.boolean(true)

    // 实验室开关：App 启动时自动播放指定媒体库的上次视频
    var autoPlayOnLaunch by SPManager.boolean(false)

    // 实验室开关：短视频自动加载播放进度（开启时恢复上次进度，关闭时每次从头播放）
    var shortAutoLoadProgress by SPManager.boolean(true)

    // 设为启动自动播放的媒体库 id（空字符串表示未设置；单选，仅保留一个）
    var autoPlayMediaId by SPManager.string("")

    // 设为启动自动播放的播放列表 id（空字符串表示未设置；与 autoPlayMediaId 互斥，仅保留一个）
    var autoPlayPlaylistId by SPManager.string("")

    private companion object {
        private const val KEY_VIDEO_PLAYER_CORE = "videoPlayerCore"
        // Kept only for migration and old backup restore. New runtime reads videoPlayerCore.
        private const val KEY_VIDEO_PLAYER_EXO = "videoPlayerExo"
    }

}
