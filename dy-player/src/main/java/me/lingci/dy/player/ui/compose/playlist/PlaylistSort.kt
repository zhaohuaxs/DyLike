package me.lingci.dy.player.ui.compose.playlist

import androidx.annotation.StringRes
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.sort.SortScope
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.compose.sort.readSort
import me.lingci.dy.player.ui.compose.sort.writeSort
import me.lingci.dy.player.util.SpUtil

/**
 * 播放列表页排序字段。
 *
 * 默认「更新时间倒序」：新建/改名/加条目都会推进 `updated_at`，用户最近动过的列表自然浮到顶部，
 * 与旧版 `ORDER BY updated_at DESC` 一致。
 */
enum class PlaylistSortField(@StringRes val labelRes: Int) {
    Updated(R.string.sort_field_updated),
    Created(R.string.sort_field_created),
    Name(R.string.sort_field_name);
}

fun SpUtil.readPlaylistSort(): SortSpec =
    readSort(SortScope.Playlists, PlaylistSortField.Updated.name, defaultAscending = false)

fun SpUtil.writePlaylistSort(field: PlaylistSortField, ascending: Boolean) {
    writeSort(SortScope.Playlists, SortSpec(field.name, ascending))
}
