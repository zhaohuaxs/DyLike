package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.entity.VideoData

/**
 * VideoData DAO接口
 */
@Dao
interface VideoDataDao {

    @Query("SELECT count(*) FROM video_data")
    suspend fun count(): Int

    /** 某媒体下的全部剧集（sort_index 为扫描时按 parentPath 排序写入的序号） */
    @Query("SELECT * FROM video_data WHERE media_id = :mediaId ORDER BY sort_index ASC")
    suspend fun listByMedia(mediaId: String): List<VideoData>

    @Query("SELECT * FROM video_data WHERE media_id = :mediaId ORDER BY sort_index ASC")
    fun observeByMedia(mediaId: String): Flow<List<VideoData>>

    @Query("SELECT * FROM video_data WHERE id = :id")
    suspend fun getById(id: String): VideoData?

    /** 全表快照（备份导出用） */
    @Query("SELECT * FROM video_data")
    suspend fun list(): List<VideoData>

    @Upsert
    suspend fun upsert(videoData: VideoData)

    @Insert
    suspend fun insertAll(videoList: List<VideoData>)

    /** 迁移合并写入：同主键行跳过（库内行更新，SP 快照只补空），返回逐行 rowId（-1 表示未插入） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(videoList: List<VideoData>): LongArray

    @Delete
    suspend fun delete(videoData: VideoData)

    /** 媒体重新扫描时，事务内 deleteByMedia + insertAll 完成子表重建（见 LibraryCompat.updateMedia） */
    @Query("DELETE FROM video_data WHERE media_id = :mediaId")
    suspend fun deleteByMedia(mediaId: String)

    /** 更新剧集归属（孤儿视频归并到媒体时使用） */
    @Query("UPDATE video_data SET media_id = :mediaId WHERE id = :videoId")
    suspend fun updateMediaId(videoId: String, mediaId: String)
}
