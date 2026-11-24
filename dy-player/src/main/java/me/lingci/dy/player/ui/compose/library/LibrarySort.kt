package me.lingci.dy.player.ui.compose.library

import androidx.annotation.StringRes
import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import me.lingci.dy.player.R
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.ui.compose.sort.SortScope
import me.lingci.dy.player.ui.compose.sort.SortSpec
import me.lingci.dy.player.ui.compose.sort.readSort
import me.lingci.dy.player.ui.compose.sort.writeSort

/**
 * 首页媒体库列表的排序字段。
 *
 * `置顶` 不在这里：它恒为第一档（需求：置顶 → 置顶时间 → 其余次序），用户切的是置顶之后的排序。
 * 默认 [LastPlayed] 降序，与旧版 SQL 的默认顺序一致，升级后首页观感不变。
 */
enum class LibrarySortField(@StringRes val labelRes: Int) {
    LastPlayed(R.string.sort_field_last_played),
    Name(R.string.sort_field_name),
    Added(R.string.sort_field_added);
}

/**
 * 拼分页查询。
 *
 * 字段与方向都来自枚举白名单，SQL 片段是常量，用户选择只决定「选哪一段」，
 * 因此这里没有把任何外部输入拼进语句。`LIMIT/OFFSET` 走绑定参数。
 *
 * 为什么要下推 SQL 而不是在内存里排：首页是分页的（每页 200），内存排序只能排到
 * 已经加载的那一页，翻页之后顺序会重新开始，滑到第 2 页就会出现「名称倒序里夹着升序」。
 */
fun libraryPageQuery(
    field: LibrarySortField,
    ascending: Boolean,
    limit: Int,
    offset: Int
): RoomRawQuery {
    val dir = if (ascending) "ASC" else "DESC"
    val fieldClause = when (field) {
        LibrarySortField.LastPlayed -> "last_played_at $dir, `index` $dir"
        LibrarySortField.Name -> "title COLLATE NOCASE $dir"
        LibrarySortField.Added -> "`index` $dir"
    }
    return RoomRawQuery(
        sql = """
            SELECT * FROM media_data
            WHERE media_type IN (1,2,3,4,7) AND is_virtual = 0
            ORDER BY pinned DESC, pinned_at DESC, $fieldClause, id DESC
            LIMIT ? OFFSET ?
        """.trimIndent(),
        onBindStatement = { statement: SQLiteStatement ->
            statement.bindLong(1, limit.toLong())
            statement.bindLong(2, offset.toLong())
        }
    )
}

fun SpUtil.readLibrarySort(): SortSpec =
    readSort(SortScope.Library, LibrarySortField.LastPlayed.name, defaultAscending = false)

fun SpUtil.writeLibrarySort(field: LibrarySortField, ascending: Boolean) {
    writeSort(SortScope.Library, SortSpec(field.name, ascending))
}
