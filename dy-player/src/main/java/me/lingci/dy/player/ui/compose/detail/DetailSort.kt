package me.lingci.dy.player.ui.compose.detail

import androidx.annotation.StringRes
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.sort.SortScope
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.compose.sort.readSort
import me.lingci.dy.player.ui.compose.sort.writeSort
import me.lingci.dy.player.util.SpUtil

/**
 * 分集列表的排序字段。
 *
 * [Default] = 保持集合自身的顺序：播放列表按「父目录 + 自然序」、本地/远端库按目录枚举顺序、
 * 喜欢按收藏时间、历史按播放时间。这一档的存在意义是播放列表——用户手工维护的顺序不能被
 * 名称排序覆盖，所以它也是详情页的默认值。
 *
 * 排序偏好全局一套（`sort.detail`），不按 mediaId 分别记：用户调的是「我想怎么看列表」，
 * 不该每进一个媒体库都重选一次。
 */
enum class DetailSortField(@StringRes val labelRes: Int) {
    Default(R.string.sort_field_default),
    Name(R.string.sort_field_name),
    Modified(R.string.sort_field_modified);
}

fun SpUtil.readDetailSort(): SortSpec =
    readSort(SortScope.Detail, DetailSortField.Default.name, defaultAscending = true)

fun SpUtil.writeDetailSort(field: DetailSortField, ascending: Boolean) {
    writeSort(SortScope.Detail, SortSpec(field.name, ascending))
}
