package me.lingci.dy.player.util

import me.lingci.dy.player.entity.MediaLibType
import me.lingci.lib.base.util.CodeUtil

/**
 * 多季剧集的目录识别与稳定分组键。
 *
 * 设计约束：
 * - 不改 `media_data.path` 语义，仍是「一个入库目录」；
 * - 同剧多季通过 `series_key` 在 UI 层聚合成一张卡片；
 * - 剧集根库（根下是季子目录）由详情页下钻，不依赖分组键；
 * - 纯函数、不依赖 Context，便于单测。
 *
 * 实测目录形态（moto 真机 2026-11-15）：
 * - `剧集/S01`、`剧集/S02`：纯季目录，一季一库；
 * - `动画/【我推的孩子】/1.第一季`：中文季名 + 序号前缀，用户按「主目录」入库；
 * - `Movies/我推的孩子S03`：剧名内嵌季号，平铺在 Movies 下。
 */
object SeriesKey {

    private val CN_DIGIT = mapOf(
        '零' to 0, '一' to 1, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
    )

    private val SEASON_TOKEN = "s\\d{1,2}|season\\s*\\d{1,2}|第\\s*[0-9一二三四五六七八九十]{1,3}\\s*季"

    /** 目录名整体就是一个季标识。 */
    private val PURE_SEASON = listOf(
        Regex("^s\\d{1,2}$", RegexOption.IGNORE_CASE),
        Regex("^season\\s*\\d{1,2}$", RegexOption.IGNORE_CASE),
        Regex("^第\\s*[0-9一二三四五六七八九十]{1,3}\\s*季$"),
        Regex("^s\\d{1,2}e\\d{1,4}$", RegexOption.IGNORE_CASE),
        // `1.第一季` / `02 - S02` / `3_第三季`
        Regex("^\\d{1,2}\\s*[.\\-_]\\s*(?:$SEASON_TOKEN)$", RegexOption.IGNORE_CASE)
    )

    /** 剧名 + 季号：`我推的孩子S03`、`药屋少女的呢喃S02`、`Fate strange Fake S01`。 */
    private val EMBEDDED_SEASON = Regex(
        "^(.+?)[\\s._\\-]*($SEASON_TOKEN)$",
        RegexOption.IGNORE_CASE
    )

    private val NUMBER = Regex("\\d{1,2}")
    private val INDEX_PREFIX = Regex("^(\\d{1,2})\\s*[.\\-_]")
    private val CN_SEASON = Regex("第\\s*([0-9一二三四五六七八九十]{1,3})\\s*季")

    /** 路径末段目录名（兼容本地绝对路径与 WebDAV/SMB 的 URL 路径）。 */
    fun lastSegment(path: String): String = path.trim().trimEnd('/').substringAfterLast('/')

    /** 父目录路径：季目录的上一层，即「剧集根」。 */
    fun parentPath(path: String): String {
        val normalized = path.trim().trimEnd('/')
        val index = normalized.lastIndexOf('/')
        return if (index > 0) normalized.substring(0, index) else ""
    }

    /** 目录名是否为「纯季标识」（不含剧名）。 */
    fun isPureSeasonFolder(name: String): Boolean =
        PURE_SEASON.any { it.matches(name.trim()) }

    /** 目录名是否为「剧名 + 季号」。 */
    fun isEmbeddedSeasonFolder(name: String): Boolean =
        !isPureSeasonFolder(name) && EMBEDDED_SEASON.matchEntire(name.trim()) != null

    /** 是否为一个可识别的季目录名。 */
    fun isSeasonFolder(name: String): Boolean =
        isPureSeasonFolder(name) || isEmbeddedSeasonFolder(name)

    /**
     * 季名：纯季目录返回目录名；剧名内嵌季号时只返回季号部分；否则空串。
     * 例：`1.第一季` → `1.第一季`，`我推的孩子S03` → `S03`。
     */
    fun seasonLabelOf(path: String): String {
        val name = lastSegment(path).trim()
        if (isPureSeasonFolder(name)) return name
        return EMBEDDED_SEASON.matchEntire(name)?.groupValues?.getOrNull(2)?.trim().orEmpty()
    }

    /** 剧集标题：纯季目录取父目录名，内嵌季号则剥离季后缀。 */
    fun seriesTitleOf(path: String): String {
        val name = lastSegment(path).trim()
        if (isPureSeasonFolder(name)) return lastSegment(parentPath(path))
        val prefix = EMBEDDED_SEASON.matchEntire(name)?.groupValues?.getOrNull(1)?.trim()
        return prefix?.trimEnd('.', '-', '_', ' ')?.takeIf { it.isNotBlank() }
            ?: lastSegment(parentPath(path))
    }

    /**
     * 稳定分组键：同一「剧集根 + 类型 + 存储源」下的所有季共享一个 key。
     * 剧名内嵌季号时把剧名并入 root，避免 `Movies` 下所有剧被误归为一部。
     * 返回空串表示不属于多季剧集，UI 按普通媒体库展示。
     */
    fun seriesKeyOf(path: String, type: MediaLibType, storageId: String?): String {
        if (seasonLabelOf(path).isEmpty()) return ""
        val parent = parentPath(path)
        if (parent.isEmpty()) return ""
        val name = lastSegment(path).trim()
        val root = if (isEmbeddedSeasonFolder(name)) "$parent/${seriesTitleOf(path)}" else parent
        return CodeUtil.md5("series|${type.name}|${storageId.orEmpty()}|$root")
    }

    /** 季序号，用于季 Tab 的自然排序；无法解析时返回 [Int.MAX_VALUE]。 */
    fun seasonNumberOf(label: String): Int {
        val trimmed = label.trim()
        INDEX_PREFIX.find(trimmed)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        CN_SEASON.find(trimmed)?.groupValues?.getOrNull(1)?.let { token ->
            parseNumber(token)?.let { return it }
        }
        NUMBER.find(trimmed)?.value?.toIntOrNull()?.let { return it }
        return Int.MAX_VALUE
    }

    /** 阿拉伯数字或中文数字（含「十一」「二十」）。 */
    private fun parseNumber(token: String): Int? {
        token.toIntOrNull()?.let { return it }
        if (token.isEmpty()) return null
        if ('十' !in token) {
            return CN_DIGIT[token.last()]?.let { digit ->
                token.dropLast(1).fold(0) { acc, ch -> acc * 10 + (CN_DIGIT[ch] ?: return null) } * 10 + digit
            }
        }
        val parts = token.split('十')
        val tens = when (val head = parts[0]) {
            "" -> 1
            else -> CN_DIGIT[head.singleOrNull() ?: return null] ?: return null
        }
        val ones = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?.let { CN_DIGIT[it.singleOrNull() ?: return null] ?: return null } ?: 0
        return tens * 10 + ones
    }
}
