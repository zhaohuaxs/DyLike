package me.lingci.dy.player.ui.compose.sort

import me.lingci.dy.player.util.SpUtil

/**
 * 列表排序的作用域。
 *
 * 键取「页面」而不是「页面实例」：换媒体库、换目录、换季都会沿用同一份偏好。
 * 需求是「所有列表都要有排序控件，且各页面独立记忆」——按 mediaId 记会让用户每进一个库
 * 就得重新选一次排序，页内换季也会把偏好打回默认。
 */
enum class SortScope(val key: String) {
    Library("library"),
    Detail("detail"),
    History("history"),
    Likes("likes"),
    Playlists("playlists")
}

/**
 * 一条排序偏好：[mode] 是排序字段枚举的 `name`，[ascending] 是升降序。
 *
 * 存枚举名而不是序号：枚举增删成员时序号会整体漂移，跨版本还原备份后就会排到别的字段上。
 * 解析不出来的名字统一回退到调用方给的默认值，与浏览页 `browserSortKey` 的兜底口径一致。
 *
 * 序列化形如 `Name|asc`，落在 `sort.<scope>` **单个**键里，不再一页两键。
 */
data class SortSpec(val mode: String, val ascending: Boolean) {

    inline fun <reified E : Enum<E>> modeOr(fallback: E): E =
        enumValues<E>().firstOrNull { it.name == mode } ?: fallback
}

private const val KEY_PREFIX = "sort."
private const val SPEC_SEPARATOR = "|"
private const val DIR_ASC = "asc"
private const val DIR_DESC = "desc"

/** 读某页的排序偏好；没写过、或值损坏时返回默认值。 */
fun SpUtil.readSort(scope: SortScope, defaultMode: String, defaultAscending: Boolean): SortSpec {
    val raw = stringPref(KEY_PREFIX + scope.key, "")
    if (raw.isBlank()) return SortSpec(defaultMode, defaultAscending)
    val mode = raw.substringBefore(SPEC_SEPARATOR).ifBlank { defaultMode }
    val ascending = raw.substringAfter(SPEC_SEPARATOR, DIR_ASC) != DIR_DESC
    return SortSpec(mode, ascending)
}

fun SpUtil.writeSort(scope: SortScope, spec: SortSpec) {
    val dir = if (spec.ascending) DIR_ASC else DIR_DESC
    putStringPref(KEY_PREFIX + scope.key, spec.mode + SPEC_SEPARATOR + dir)
}
