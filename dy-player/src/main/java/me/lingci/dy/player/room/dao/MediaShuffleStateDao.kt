package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.room.entity.MediaShuffleStateEntity

/**
 * MediaShuffleState DAO接口（随机播放状态，对应 SP 的 mediaShuffleJson）
 */
@Dao
interface MediaShuffleStateDao {

    // ---- 响应式查询（Flow）----

    @Query("SELECT * FROM media_shuffle_state")
    fun observeAll(): Flow<List<MediaShuffleStateEntity>>

    // ---- 单次查询（suspend）----

    @Query("SELECT * FROM media_shuffle_state WHERE media_id = :mediaId")
    suspend fun getByMediaId(mediaId: String): MediaShuffleStateEntity?

    @Query("SELECT * FROM media_shuffle_state")
    suspend fun list(): List<MediaShuffleStateEntity>

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(state: MediaShuffleStateEntity)

    /** 迁移合并写入：同 media_id 状态已存在则跳过（库内随机播放进度更新，不被 SP 快照覆盖） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(state: MediaShuffleStateEntity): Long

    /** 批量写入（替代 SP 时代整 map 覆盖保存） */
    @Upsert
    suspend fun upsertAll(states: List<MediaShuffleStateEntity>)

    @Insert
    suspend fun insertAll(states: List<MediaShuffleStateEntity>)

    @Query("DELETE FROM media_shuffle_state WHERE media_id = :mediaId")
    suspend fun deleteByMediaId(mediaId: String)
}
