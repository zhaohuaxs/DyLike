package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.entity.Favorite

/**
 * Favorite DAO接口
 *
 * video_id 单列唯一，一个视频至多一条收藏。
 * 注意：@Upsert 只按**主键**判存在，而 id 是自增主键（新增行传 0），
 * 因此写入必须用 [insertIgnore]（ON CONFLICT IGNORE，靠 video_id 唯一索引去重）；
 * 用 [upsert] 会走 INSERT 撞唯一索引抛 SQLiteConstraintException。
 */
@Dao
interface FavoriteDao {

    // ---- 响应式查询（Flow）----

    @Query("SELECT * FROM favorite ORDER BY created_at DESC")
    fun observeAll(): Flow<List<Favorite>>

    // ---- 单次查询（suspend）----

    @Query("SELECT * FROM favorite WHERE video_id = :videoId LIMIT 1")
    suspend fun getByVideoId(videoId: String): Favorite?

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE video_id = :videoId)")
    suspend fun exists(videoId: String): Boolean

    @Query("SELECT count(*) FROM favorite")
    suspend fun count(): Int

    /** 全表快照（备份导出用） */
    @Query("SELECT * FROM favorite")
    suspend fun list(): List<Favorite>

    /** 批量写入（备份恢复用，删库重建后无唯一冲突） */
    @Insert
    suspend fun insertAll(favorites: List<Favorite>)

    /** 全部收藏的 video_id（短视频列表标 like 状态用） */
    @Query("SELECT video_id FROM favorite")
    suspend fun listVideoIds(): List<String>

    /** 收藏列表联查视频实体（按收藏时间倒序），替代 SP likeJson 读取 */
    @Query("""
        SELECT v.* FROM video_data v
        INNER JOIN favorite f ON v.id = f.video_id
        ORDER BY f.created_at DESC
    """)
    suspend fun listFavoriteVideos(): List<VideoData>

    // ---- 写入 ----

    /**
     * 仅供备份恢复等「已确保无冲突」的场景；常规收藏写入请用 [insertIgnore]。
     */
    @Upsert
    suspend fun upsert(favorite: Favorite)

    /** 收藏写入：video_id 唯一索引已存在则跳过（保留旧行 created_at，列表顺序不变），返回 rowId（-1 表示已存在） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(favorite: Favorite): Long

    @Query("DELETE FROM favorite WHERE video_id = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    @Delete
    suspend fun delete(favorite: Favorite)
}
