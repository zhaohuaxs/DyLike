package me.lingci.dy.player.ui.compose.library

import androidx.annotation.StringRes
import androidx.room3.RoomRawQuery
import androidx.sqlite.SQLiteStatement
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaLibType
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
 * 字段与方向来自枚举白名单，搜索词、类型和分页值全部绑定；SQL 只拼接常量片段。
 * 先按完整分组选代表行与季数，再过滤和 LIMIT，避免同剧季被窗口边界切开。
 * 组顺序沿用原始行的排序锚点，代表季切换不会让整张卡片突然跳位。
 */
fun libraryPageQuery(
    field: LibrarySortField,
    ascending: Boolean,
    limit: Int,
    offset: Int,
    query: String,
    type: MediaLibType?
): RoomRawQuery {
    val dir = if (ascending) "ASC" else "DESC"
    val anchorFieldClause = when (field) {
        LibrarySortField.LastPlayed -> "anchor.last_played_at $dir, anchor.`index` $dir"
        LibrarySortField.Name -> "anchor.title COLLATE NOCASE $dir"
        LibrarySortField.Added -> "anchor.`index` $dir"
    }
    val pattern = "%${escapeLikePattern(query)}%"
    return RoomRawQuery(
        sql = """
            -- API 29 SQLite 不支持窗口函数，用关联子查询保留完整分组语义。
            WITH visible AS (
                SELECT m.*,
                    CASE
                        WHEN m.series_key <> '' THEN 'series:' || m.series_key
                        ELSE 'media:' || m.id
                    END AS card_key
                FROM media_data m
                WHERE media_type IN (1,2,3,4,7) AND is_virtual = 0
            ),
            card_groups AS (
                SELECT v.card_key,
                    (
                        SELECT COUNT(*) FROM visible season
                        WHERE season.card_key = v.card_key
                    ) AS season_count,
                    (
                        SELECT representative.id FROM visible representative
                        WHERE representative.card_key = v.card_key
                        ORDER BY
                            CASE WHEN TRIM(representative.play_last) <> '' THEN 0 ELSE 1 END,
                            representative.season_number ASC,
                            representative.title COLLATE NOCASE ASC,
                            representative.id DESC
                        LIMIT 1
                    ) AS representative_id,
                    (
                        SELECT anchor.id FROM visible anchor
                        WHERE anchor.card_key = v.card_key
                        ORDER BY
                            anchor.pinned DESC,
                            anchor.pinned_at DESC,
                            $anchorFieldClause,
                            anchor.id DESC
                        LIMIT 1
                    ) AS sort_anchor_id
                FROM visible v
                GROUP BY v.card_key
            )
            SELECT
                representative.id, representative.title, representative.show_file,
                representative.cover_type, representative.path, representative.play_type,
                representative.play_mode, representative.`index`, representative.media_type,
                representative.op_offset, representative.ed_offset,
                representative.storage_type, representative.play_last,
                representative.storage_id, representative.pinned, representative.pinned_at,
                representative.last_played_at, representative.is_virtual,
                representative.series_key, representative.season_number,
                representative.search_title, groups.season_count
            FROM card_groups groups
            JOIN visible representative ON representative.id = groups.representative_id
            JOIN visible anchor ON anchor.id = groups.sort_anchor_id
            WHERE (
                ?1 = '%%'
                OR EXISTS (
                    SELECT 1 FROM visible season
                    WHERE season.card_key = groups.card_key
                        AND season.search_title LIKE ?1 ESCAPE '\'
                )
            )
                AND (?2 = -1 OR representative.media_type = ?2)
            ORDER BY
                anchor.pinned DESC,
                anchor.pinned_at DESC,
                $anchorFieldClause,
                anchor.id DESC
            LIMIT ?3 OFFSET ?4
        """.trimIndent(),
        onBindStatement = { statement: SQLiteStatement ->
            statement.bindText(1, pattern)
            statement.bindLong(2, type?.value?.toLong() ?: -1L)
            statement.bindLong(3, limit.toLong())
            statement.bindLong(4, offset.toLong())
        }
    )
}

internal fun escapeLikePattern(query: String): String = query
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

fun SpUtil.readLibrarySort(): SortSpec =
    readSort(SortScope.Library, LibrarySortField.LastPlayed.name, defaultAscending = false)

fun SpUtil.writeLibrarySort(field: LibrarySortField, ascending: Boolean) {
    writeSort(SortScope.Library, SortSpec(field.name, ascending))
}
