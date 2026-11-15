package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import me.lingci.dy.player.room.entity.PlayingListItem

@Dao
interface PlayingListDao {

    @Insert
    suspend fun insertAll(items: List<PlayingListItem>)

    /** 只读不删：配置变化 / Activity 重建后仍能恢复队列。 */
    @Query("SELECT * FROM playing_list_item WHERE session_id = :sessionId ORDER BY sort_in_session ASC")
    suspend fun read(sessionId: String): List<PlayingListItem>

    @Query("DELETE FROM playing_list_item WHERE session_id = :sessionId")
    suspend fun clearSession(sessionId: String)

    /** 季名在会话内一致，只取首行即可，避免为读一个字段把整个千条队列捞出来。 */
    @Query(
        "SELECT season_label FROM playing_list_item " +
            "WHERE session_id = :sessionId ORDER BY sort_in_session ASC LIMIT 1"
    )
    suspend fun readSeasonLabel(sessionId: String): String?

    /** 保留最近 N 个会话，清理异常退出遗留的旧会话。 */
    @Query(
        """
        DELETE FROM playing_list_item
        WHERE session_id NOT IN (
            SELECT session_id FROM playing_list_item
            GROUP BY session_id
            ORDER BY MAX(rowId) DESC
            LIMIT :keepRecent
        )
        """
    )
    suspend fun clearStaleSessions(keepRecent: Int)
}
