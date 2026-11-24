package me.lingci.dy.player.ui.compose.history

import androidx.annotation.StringRes
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.sort.SortScope
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.compose.sort.readSort
import me.lingci.dy.player.ui.compose.sort.writeSort
import me.lingci.dy.player.util.SpUtil

/**
 * 历史页排序字段。
 *
 * 时间倒序是该页的默认语义（「最近看了什么」），但不能写死：用户也会想按名称找某一部。
 */
enum class HistorySortField(@StringRes val labelRes: Int) {
    Time(R.string.sort_field_time),
    Name(R.string.sort_field_name);
}

fun SpUtil.readHistorySort(): SortSpec =
    readSort(SortScope.History, HistorySortField.Time.name, defaultAscending = false)

fun SpUtil.writeHistorySort(field: HistorySortField, ascending: Boolean) {
    writeSort(SortScope.History, SortSpec(field.name, ascending))
}
