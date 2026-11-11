package me.lingci.dy.player.ui.compose.settings

import androidx.compose.runtime.Immutable
import me.lingci.dy.player.core.DyPlayerCore

/**
 * 播放与资源库偏好。
 *
 * 一份状态，两处入口：设置里的「播放与进度」「资源库与显示」与「我的」页共用同一次写入，
 * 所以字段名直接对齐 [me.lingci.dy.player.util.SpUtil] 的属性，默认值必须与 SP 声明一致，
 * 否则首帧会短暂显示错误状态。
 */
@Immutable
data class PlayerSettingsState(
    val longVideoMode: Boolean = true,
    val videoDetailMode: Boolean = false,
    val autoNext: Boolean = true,
    val loopList: Boolean = true,
    val longVideoPip: Boolean = true,
    val longVideoBackgroundPlay: Boolean = false,
    val audioFadeEnabled: Boolean = false,
    val audioFadeInDuration: Int = 1000,
    val audioFadeOutDuration: Int = 1000,
    val shortRandom: Boolean = true,
    val coverRatio: String = "3:4"
)

/** Exo 网络路线：系统默认数据源 vs OkHttp（旧 useOkhttp 布尔开关的语义化表达） */
enum class ExoNetworkRoute { System, OkHttp }

/** MPV 视频输出（vo）：gpu 为旧管线，gpu-next 为新管线 */
enum class MpvVideoOutput(val value: String) {
    Gpu("gpu"),
    GpuNext("gpu-next");

    companion object {
        fun fromValue(raw: String?): MpvVideoOutput = entries.firstOrNull { it.value == raw } ?: Gpu
    }
}

/** MPV 硬解方式，对应 hwdec 选项；Auto 保留 MPV 的「先硬解失败回退软解」语义 */
enum class MpvHwdec(val value: String) {
    Auto("auto"),
    MediaCodec("mediacodec"),
    MediaCodecCopy("mediacodec-copy"),
    Off("no");

    companion object {
        fun fromValue(raw: String?): MpvHwdec = entries.firstOrNull { it.value == raw } ?: Auto
    }
}

/**
 * 内核偏好。内核是「枚举」不是「开关」：Exo / MPV 各自带一组专属选项，
 * 页面用按钮组切换后只显示当前内核那一组（见 KernelSettingsScreen）。
 */
@Immutable
data class KernelSettingsState(
    val longVideoCore: DyPlayerCore = DyPlayerCore.EXO,
    val shortVideoCore: DyPlayerCore = DyPlayerCore.EXO,
    /** Exo 专属 */
    val exoNetwork: ExoNetworkRoute = ExoNetworkRoute.System,
    val exoRenderSurface: Boolean = false,
    val exoSharpen: Boolean = false,
    val exoSharpenStrength: Float = 1f,
    /** MPV 专属 */
    val mpvOutput: MpvVideoOutput = MpvVideoOutput.Gpu,
    val mpvHwdec: MpvHwdec = MpvHwdec.Auto,
    val mpvSequentialRead: Boolean = true,
    val mpvSpecialRender: Boolean = true,
    /** 短视频专属：快速滑动时复用 Surface 的渲染管线（旧 sortRender），仅 Exo 生效 */
    val shortFastRender: Boolean = false,
    /**
     * 跳过 TLS 证书校验（自签 / 证书链异常的站点）。
     *
     * 两个内核共用：MPV 走 `tls-verify=no`；Exo 只有 OkHttp 数据源能关校验，
     * 走 System（DefaultHttpDataSource）时不生效。落盘 key 沿用 `labMpvSkipTlsVerify`。
     */
    val skipTlsVerify: Boolean = false
)

@Immutable
data class DanmakuSettingsState(
    val gradient: Boolean = false,
    val gradientRatio: Int = 40,
    val keepTextColor: Boolean = false,
    val strokeMultipleMode: Boolean = false,
    val strokeMultiple: Float = 1.2f,
    val mergeMode: Boolean = false,
    /** 相同弹幕达到多少条才折叠成 xN（旧 DmMergeOptionDialog 的下限 2、上限 20） */
    val mergeShow: Int = 9,
    /** 达到多少条时把 xN 弹幕转到顶部固定（旧弹窗下限 5、上限 50） */
    val mergeTop: Int = 9,
    val showTime: Boolean = false,
    val showFps: Boolean = false,
    val fontMode: Boolean = false,
    val fontName: String = "",
    /** 已导入字体的绝对路径，空串表示用系统默认字 */
    val fontPath: String = "",
    val filterMode: Boolean = false,
    /** 过滤关键词，落盘时用 ",," 连接（与旧 DmFilterDialog 一致，弹幕链路无需改动） */
    val filterKeywords: List<String> = emptyList()
)

@Immutable
data class LabSettingsState(
    val longVideoPortrait: Boolean = false,
    val autoPlayOnLaunch: Boolean = false,
    val shortAutoLoadProgress: Boolean = true,
    val debugMode: Boolean = false,
    /** 旧数据迁移入口只在未迁移时出现，由 ViewModel 异步查询 */
    val migrationPending: Boolean = false
)

@Immutable
data class SettingsState(
    val player: PlayerSettingsState = PlayerSettingsState(),
    val kernel: KernelSettingsState = KernelSettingsState(),
    val danmaku: DanmakuSettingsState = DanmakuSettingsState(),
    val lab: LabSettingsState = LabSettingsState()
)
