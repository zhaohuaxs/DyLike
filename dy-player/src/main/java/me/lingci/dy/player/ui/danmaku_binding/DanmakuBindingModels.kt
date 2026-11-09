package me.lingci.dy.player.ui.danmaku_binding

import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.PlayInfo
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.util.ZipXmlLoader
import java.io.File

data class DanmakuBindingMediaItem(
    val videoData: VideoData,
    var originalInfo: PlayInfo? = null,
    var originalTracks: MutableList<DmTrack> = mutableListOf(),
    // 主弹幕 key（条目级）：普通文件为归一化路径，压缩包为 路径+条目 key
    var originalLastTrackKey: String = "",
    var workingTracks: MutableList<DmTrack> = mutableListOf(),
    var lastTrackKey: String = "",
    var selected: Boolean = false,
    var matchHint: String = ""
) {

    fun boundCount(): Int {
        return workingTracks.size
    }

    fun primaryTrack(): DmTrack? {
        // 不可修复的旧压缩包轨道（title 只是包名）不能作为主弹幕
        return workingTracks.firstOrNull {
            it.bindingKey() == lastTrackKey && !it.isUnresolvedLegacyArchive()
        }
            ?: workingTracks.firstOrNull { it.selected && !it.isUnresolvedLegacyArchive() }
            ?: workingTracks.firstOrNull { !it.isUnresolvedLegacyArchive() }
    }

    fun hasTrack(key: String): Boolean {
        return workingTracks.any { it.bindingKey() == key }
    }

    fun isPrimary(key: String): Boolean {
        return primaryTrack()?.bindingKey() == key
    }

    fun hasChanges(): Boolean {
        return originalLastTrackKey != lastTrackKey ||
                originalTracks.bindingSignature() != workingTracks.bindingSignature()
    }
}

data class DanmakuBindingFileItem(
    val fileEntity: FileEntity,
    val score: Int = 0,
    val ownerTitle: String = "",
    val boundToSelected: Boolean = false,
    val primaryForSelected: Boolean = false
)

fun DmTrack.bindingCopy(): DmTrack {
    return copy(checked = false, showAction = false)
}

fun DmTrack.displayNameWithoutExtension(): String {
    return title.ifBlank { path.substringAfterLast('/') }
}

fun FileEntity.displayNameWithoutExtension(): String {
    return name.ifBlank { title }
}

fun String.removeExtensionForDisplay(): String {
    val value = trim()
    val index = value.lastIndexOf('.')
    return if (index > 0) value.take(index) else value
}

fun DmTrack.isUsableBindingTrack(): Boolean {
    if (path.isBlank()) {
        return false
    }
    return path.startsWith("http") || File(path).exists()
}

fun List<DmTrack>.bindingSignature(): List<String> {
    return map { track ->
        listOf(
            // bindingKey 已包含压缩包条目信息，避免同一包不同条目被判为同一条
            track.bindingKey(),
            track.title.trim(),
            track.offset.toString(),
            track.mineType.trim(),
            track.password.trim(),
            track.selected.toString()
        ).joinToString("|")
    }.sorted()
}

/**
 * 条目级唯一 key：
 * - 普通文件：归一化路径（与原 path 判重行为一致）
 * - 压缩包：路径 + 条目名 + 原始字节 key + 索引，避免同一包内多个弹幕文件被合并
 */
fun DmTrack.bindingKey(): String {
    val base = path.normalizeBindingPath()
    if (!isArchiveTrack()) return base
    val entryName = resolvedArchiveEntryName().normalizeBindingPath()
    return "$base|$entryName|${archiveEntryRaw.trim().lowercase()}|$archiveEntryIndex"
}

/** 文件列表项的 key（压缩包整包被禁止绑定，因此只按路径即可） */
fun FileEntity.bindingKey(): String {
    return path.normalizeBindingPath()
}

/** 是否为压缩包文件（zip/7z/rar） */
fun FileEntity.isArchiveFile(): Boolean {
    return ZipXmlLoader.archiveMimeType(path).isNotBlank()
}

fun String.samePath(other: String): Boolean {
    return normalizeBindingPath() == other.normalizeBindingPath()
}

fun String.normalizeBindingPath(): String {
    return trim().replace('\\', '/').lowercase()
}
