package me.lingci.dy.player.ui.compose.likes

import androidx.annotation.StringRes
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.sort.SortScope
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.compose.sort.readSort
import me.lingci.dy.player.ui.compose.sort.writeSort
import me.lingci.dy.player.util.SpUtil

/**
 * 「我的喜欢」排序字段。
 *
 * [Liked] 沿用收藏表的 `created_at` 顺序（DAO 已是倒序，升序就是反一下），不需要额外查时间戳；
 * 默认收藏时间倒序，与旧版列表顺序一致。
 */
enum class LikesSortField(@StringRes val labelRes: Int) {
    Liked(R.string.sort_field_liked),
    Name(R.string.sort_field_name);
}

fun SpUtil.readLikesSort(): SortSpec =
    readSort(SortScope.Likes, LikesSortField.Liked.name, defaultAscending = false)

fun SpUtil.writeLikesSort(field: LikesSortField, ascending: Boolean) {
    writeSort(SortScope.Likes, SortSpec(field.name, ascending))
}
