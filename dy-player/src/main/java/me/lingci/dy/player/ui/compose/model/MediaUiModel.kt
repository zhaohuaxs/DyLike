package me.lingci.dy.player.ui.compose.model

import androidx.compose.runtime.Immutable
import me.lingci.dy.player.entity.MediaLibType

/**
 * 卡片用的媒体视图模型。
 *
 * 这是「UI 需要什么」而不是「数据库存了什么」：封面在 ViewModel 里已经过
 * `CoverHelper.resolveCover` 解析成「路径 + 认证头」，组件不必再持有 Room 实体
 * 与整份资源列表（规范 §5.4「组件不查数据」、§11 P2.1）。
 *
 * 标 `@Immutable` 是这张卡能被跳过的前提：`MediaData` 是可变的 Room 实体，
 * 编译器无法证明它不会变，于是列表里任何一次重组都会连带重组所有卡片。
 */
@Immutable
data class MediaUiModel(
    val id: String,
    val title: String,
    val type: MediaLibType,
    /** 已解析的封面：本地为文件路径，WebDAV 为 http 地址；null 表示没有可用封面 */
    val coverPath: String? = null,
    /** 取 HTTP 封面时随请求带上的认证头 */
    val coverHeaders: Map<String, String> = emptyMap()
)

/**
 * 媒体类型的中文名。
 *
 * 筛选条与卡片角标共用同一份映射：之前筛选按 `mediaTypeName`、卡片按 `source`
 * 各写一遍，两边口径不一致时会出现「筛得出来的卡片角标写着别的类型」。
 * 待 §11 P3.6 做文案迁移时，这里换成 `@StringRes`。
 */
fun mediaTypeLabel(type: MediaLibType): String = when (type) {
    MediaLibType.LOCAL -> "本地"
    MediaLibType.ONLINE -> "在线"
    MediaLibType.WEBDAV -> "WebDAV"
    MediaLibType.SMB -> "SMB"
    else -> "媒体"
}
