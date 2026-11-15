package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.entity.PlaylistItem
import me.lingci.dy.player.room.entity.PlaylistItemCount

/**
 * PlaylistItem DAO接口
 */
@Dao
interface PlaylistItemDao {

    // ---- 响应式查询（Flow）----

    @Query("SELECT * FROM playlist_item WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    fun observeByPlaylist(playlistId: String): Flow<List<PlaylistItem>>

    // ---- 单次查询（suspend）----

    @Query("SELECT * FROM playlist_item WHERE playlist_id = :playlistId ORDER BY sort_order ASC")
    suspend fun listByPlaylist(playlistId: String): List<PlaylistItem>

    /**
     * 片单详情一次性取回全部视频实体。
     *
     * 原实现 listByPlaylist() + 逐条 videoDataDao.getById() 是 N+1，千条片单需要 1000 次单条 SQL。
     * `v.*` 限定列，避免与 playlist_item.media_id 同名歧义；INNER JOIN 跳过已删除的视频行，
     * 与 mapNotNull 语义一致；顺序按片单 sort_order，随后由 sortByParentPath 重排。
     */
    @Query("""
        SELECT v.* FROM video_data v
        INNER JOIN playlist_item p ON v.id = p.video_id
        WHERE p.playlist_id = :playlistId
        ORDER BY p.sort_order ASC
    """)
    suspend fun listVideosByPlaylist(playlistId: String): List<VideoData>

    @Query("SELECT count(*) FROM playlist_item WHERE playlist_id = :playlistId")
    suspend fun countByPlaylist(playlistId: String): Int

    /** 全部播放列表的条目数（一次聚合，替代列表页逐个 count 的 N+1） */
    @Query("SELECT playlist_id, COUNT(*) AS item_count FROM playlist_item GROUP BY playlist_id")
    fun observeItemCounts(): Flow<List<PlaylistItemCount>>

    /** 全表快照（备份导出用） */
    @Query("SELECT * FROM playlist_item")
    suspend fun list(): List<PlaylistItem>

    // ---- 写入 ----

    @Insert
    suspend fun insert(item: PlaylistItem)

    @Insert
    suspend fun insertAll(items: List<PlaylistItem>)

    /** 删除播放列表全部条目（配合 insertAll 在事务内做整表重建/diff） */
    @Query("DELETE FROM playlist_item WHERE playlist_id = :playlistId")
    suspend fun deleteByPlaylist(playlistId: String)

    @Query("DELETE FROM playlist_item WHERE playlist_id = :playlistId AND video_id = :videoId")
    suspend fun deleteByPlaylistAndVideo(playlistId: String, videoId: String)
}
