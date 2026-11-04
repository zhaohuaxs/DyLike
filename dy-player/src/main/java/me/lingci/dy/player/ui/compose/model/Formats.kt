package me.lingci.dy.player.ui.compose.model

import java.util.Locale

/**
 * 列表里的时间 / 体积格式化。
 *
 * 这三份实现原本分散在 `HistoryScreen.formatTime`、`LibraryScreen.formatDuration`、
 * `BrowserScreen.formatFileSize`，进位规则各写一遍；合并后固定 `Locale.US`——
 * 否则在阿拉伯语等区域会拿到本地数字，进度条旁边的 `01:23` 会变成另一套字形。
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%02d:%02d", m, s)
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        String.format(Locale.US, "%.2f %s", size, units[unitIndex])
    }
}
