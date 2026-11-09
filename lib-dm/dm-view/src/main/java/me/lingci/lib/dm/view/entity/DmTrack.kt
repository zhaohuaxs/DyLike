package me.lingci.lib.dm.view.entity

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.InternalSerializationApi
import java.io.File
import kotlinx.serialization.Serializable

/**
 *   @author : happyc
 *   time    : 2025/02/25
 *   desc    :
 *   version : 1.0
 */
@OptIn(InternalSerializationApi::class)
@Parcelize
@Serializable
data class DmTrack(
    var title: String = "",
    var path: String = "",
    // 单位 ms
    var offset: Long = 0,
    // view操作
    var selected: Boolean = false,
    var checked: Boolean = false,
    var showAction: Boolean = false,
    // 分割相关
    var cutStart: Int = 0,
    var cutEnd: Int = 0,
    var cutDuration: Int = 0,
    var cutMode: Int = 0,
    var mineType: String = "",
    var password: String = "",
    // 压缩包内条目信息：新数据写入，旧 JSON 缺省时为空/-1（不破坏反序列化）
    var archiveEntryName: String = "",
    // 条目原始路径名的十六进制（ISO-8859-1 字节），用于跨 TextFixer 版本稳定匹配
    var archiveEntryRaw: String = "",
    // 条目在压缩包中的索引，-1 表示未知
    var archiveEntryIndex: Int = -1,
) : Parcelable {

    constructor(file: File) : this(
        title = file.name,
        path = file.path
    )

    constructor(path: String) : this(
        File(path)
    )

    /** 压缩包扩展名（小写）；非压缩包返回空串 */
    private fun pathExtension(): String = path.substringAfterLast('.', "").lowercase()

    /** mineType 归一化为小写，兼容旧数据中的大小写差异 */
    fun normalizedMineType(): String = mineType.trim().lowercase()

    /** 是否为压缩包轨道：mineType 覆盖 zip/7z/rar，缺失时回落到路径扩展名 */
    fun isArchiveTrack(): Boolean {
        val type = normalizedMineType()
        if (type == "zip" || type == "7z" || type == "rar") return true
        val ext = pathExtension()
        return ext == "zip" || ext == "7z" || ext == "rar"
    }

    /**
     * 解析应打开的包内条目名。
     * 旧数据没有 archiveEntryName 时把 title 当作条目名（选择面板旧版本如此存储）；
     * title 等于包文件名时说明这是“整包绑定”，无法推断条目，返回空串交给上层提示重选。
     */
    fun resolvedArchiveEntryName(): String {
        if (!isArchiveTrack()) return ""
        if (archiveEntryName.isNotBlank()) return archiveEntryName.trim()
        if (title.isBlank()) return ""
        val archiveName = File(path).name
        if (title.equals(archiveName, ignoreCase = true)) return ""
        // 旧数据兼容：title 即包内条目名
        return title.trim()
    }

    /** 旧数据是否为“只知道包名、不知道具体条目”的不可修复状态 */
    fun isUnresolvedLegacyArchive(): Boolean {
        return isArchiveTrack() && resolvedArchiveEntryName().isBlank()
    }

}

data class DmTrackConf(
    var trackMode: DmTrackMode = DmTrackMode.SINGLE_SWITCH,
    var dmTrack: DmTrack = DmTrack()
)

enum class DmTrackMode {

    SINGLE_SWITCH,
    MULTI_MERGE

}