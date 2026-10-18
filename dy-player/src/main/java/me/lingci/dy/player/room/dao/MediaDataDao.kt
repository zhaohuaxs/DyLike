package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.room.entity.MediaWithVideos

/**
 * MediaData DAO接口
 *
 * 数据访问模式（v3 三次回填）：废弃 loadAll→内存改→saveAll 整 list 模式，
 * 改为分页查询 + 按 id 单条操作 + SQL 排序；is_virtual=0 过滤虚拟媒体。
 */
@Dao
interface MediaDataDao {

    companion object {
        /** 媒体库分页大小 */
        const val PAGE_SIZE = 200
    }

    // ---- 响应式查询（Flow）----

    /** 媒体库分页查询：SQL 排序（pinned → pinnedAt → lastPlayedAt → index），排除虚拟媒体 */
    @Query("""
        SELECT * FROM media_data
        WHERE media_type IN (1,2,3,4,7) AND is_virtual = 0
        ORDER BY pinned DESC, pinned_at DESC, last_played_at DESC, `index` DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeLibraryPage(limit: Int, offset: Int): Flow<List<MediaData>>

    @Query("SELECT * FROM media_data WHERE media_type = :type AND is_virtual = 0 ORDER BY pinned DESC, pinned_at DESC, last_played_at DESC")
    fun observeByType(type: MediaLibType): Flow<List<MediaData>>

    @Query("SELECT * FROM media_data WHERE storage_id = :storageId AND media_type IN (1,2,3,4,7) AND is_virtual = 0")
    fun observeByStorage(storageId: String): Flow<List<MediaData>>

    /** 媒体 + 全部剧集（@Transaction + @Relation，需 Room >= 3.0.3） */
    @Transaction
    @Query("SELECT * FROM media_data WHERE id = :mediaId")
    suspend fun getWithVideos(mediaId: String): MediaWithVideos?

    // ---- 单次查询（suspend）----

    @Query("SELECT count(*) FROM media_data WHERE media_type IN (1,2,3,4,7) AND is_virtual = 0")
    suspend fun count(): Int

    @Query("SELECT * FROM media_data WHERE id = :id")
    suspend fun getById(id: String): MediaData?

    /** 全表快照（备份导出用，含虚拟宿主与 DEFAULT 类型） */
    @Query("SELECT * FROM media_data")
    suspend fun list(): List<MediaData>

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(media: MediaData)

    @Insert
    suspend fun insert(media: MediaData)

    @Insert
    suspend fun insertAll(mediaList: List<MediaData>)

    /**
     * 迁移合并写入（单条）：主键已存在则跳过，返回 rowId（-1 表示未插入）。
     * 「稍后再说」后用户仍会往 Room 写数据，重迁移时同 id 行以库内为准。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(media: MediaData): Long

    /** 迁移合并写入（批量）：同主键行跳过，返回逐行 rowId（-1 表示未插入） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(mediaList: List<MediaData>): LongArray

    @Delete
    suspend fun delete(media: MediaData)

    @Query("UPDATE media_data SET pinned = :pinned, pinned_at = :pinnedAt WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean, pinnedAt: Long)

    @Query("UPDATE media_data SET play_last = :playLast, last_played_at = :lastPlayedAt WHERE id = :id")
    suspend fun updatePlayLast(id: String, playLast: String, lastPlayedAt: Long)
}
