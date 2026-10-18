package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.room.entity.Playlist

/**
 * Playlist DAO接口
 */
@Dao
interface PlaylistDao {

    // ---- 响应式查询（Flow）----

    @Query("SELECT * FROM playlist ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<Playlist>>

    // ---- 单次查询（suspend）----

    @Query("SELECT * FROM playlist ORDER BY updated_at DESC")
    suspend fun list(): List<Playlist>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun getById(id: String): Playlist?

    @Query("SELECT count(*) FROM playlist")
    suspend fun count(): Int

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(playlist: Playlist)

    @Insert
    suspend fun insert(playlist: Playlist)

    /** 迁移合并写入：同 id 播放列表已存在则跳过，返回 rowId（-1 表示未插入） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(playlist: Playlist): Long

    /** 批量写入（备份恢复用） */
    @Insert
    suspend fun insertAll(playlists: List<Playlist>)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("UPDATE playlist SET title = :title, updated_at = :now WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, now: Long)

    @Query("UPDATE playlist SET play_mode = :playMode, updated_at = :now WHERE id = :id")
    suspend fun updatePlayMode(id: String, playMode: Int, now: Long)
}
