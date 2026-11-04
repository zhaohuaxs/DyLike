package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.entity.HistoryGroupSummary
import me.lingci.dy.player.room.entity.PlayHistory
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.dy.player.room.entity.ResumeRow

/**
 * PlayHistory DAO接口
 *
 * 按 (video_id, source_key) 复合唯一，@Upsert 自动去重（同一视频不同来源各自一条进度）。
 * 无条数上限（SP 时代的 100 条限制不再需要）。
 */
@Dao
interface PlayHistoryDao {

    // ---- 响应式查询（Flow）----

    /** 历史页：全部来源混合，按最近播放排序 */
    @Query("SELECT * FROM play_history ORDER BY updated_at DESC LIMIT :limit OFFSET :offset")
    fun observeRecent(limit: Int, offset: Int): Flow<List<PlayHistory>>

    /** 首页继续观看：仅媒体库来源，按最近播放排序 */
    @Query("SELECT * FROM play_history WHERE source_type = 0 ORDER BY updated_at DESC LIMIT :limit")
    fun observeLibraryRecent(limit: Int): Flow<List<PlayHistory>>

    /** 首页继续观看：按媒体库分组，每个媒体库取最新一条 */
    @Query("""
        SELECT * FROM play_history
        WHERE source_type = 0 AND updated_at = (
            SELECT MAX(p2.updated_at) FROM play_history p2
            WHERE p2.source_key = play_history.source_key AND p2.source_type = 0
        )
        ORDER BY updated_at DESC
        LIMIT :limit
    """)
    fun observeLibraryGroupedRecent(limit: Int): Flow<List<PlayHistory>>

    /**
     * 首页「继续观看」联查：每个媒体库取最新一条，一次补齐媒体标题与分集名。
     *
     * 替代「先查历史、再逐条 getById 查 media / video」的 N+1，缩略图路径由调用方
     * 用返回的 videoId（即 video_data 主键）换算。
     */
    @Query("""
        SELECT p.video_id AS video_id, p.source_key AS source_key,
               p.progress AS progress, p.duration AS duration,
               m.title AS media_title, v.name AS video_name
        FROM play_history p
        INNER JOIN media_data m ON m.id = substr(p.source_key, 5)
        INNER JOIN video_data v ON v.id = p.video_id
        WHERE p.source_type = 0 AND p.updated_at = (
            SELECT MAX(p2.updated_at) FROM play_history p2
            WHERE p2.source_key = p.source_key AND p2.source_type = 0
        )
        ORDER BY p.updated_at DESC
        LIMIT :limit
    """)
    fun observeResumeRows(limit: Int): Flow<List<ResumeRow>>

    /** 某一来源（某剧/某播放列表）的全部观看记录 */
    @Query("SELECT * FROM play_history WHERE source_type = :source AND source_key = :sourceKey ORDER BY updated_at DESC")
    fun observeBySource(source: PlaySource, sourceKey: String): Flow<List<PlayHistory>>

    /** 某时间点之后有观看记录的条目数（「我的」本月观看统计；只看最近一次进度时间） */
    @Query("SELECT COUNT(*) FROM play_history WHERE updated_at >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    /**
     * 历史页聚合视图：LIBRARY/PLAYLIST 按 source_key 分组取最新。
     *
     * 标题与进度都在 SQL 侧补齐：
     * - `substr(source_key, 5)` 去掉 `lib:` 前缀（4 字符 + 1），`substr(source_key, 4)` 去掉 `pl:`；
     * - 两个 LEFT JOIN 各自限定 source_type，另一侧不会误命中；
     * - 进度取该组最新一条，替代业务层逐组调用 `getLatestBySource`（N+1）。
     */
    @Query("""
        SELECT h.source_type, h.source_key,
               MAX(h.updated_at) AS last_played_at,
               COUNT(*) AS ep_count,
               COALESCE((
                   SELECT p.progress FROM play_history p
                   WHERE p.source_type = h.source_type AND p.source_key = h.source_key
                   ORDER BY p.updated_at DESC LIMIT 1
               ), 0) AS progress,
               COALESCE((
                   SELECT p.duration FROM play_history p
                   WHERE p.source_type = h.source_type AND p.source_key = h.source_key
                   ORDER BY p.updated_at DESC LIMIT 1
               ), 0) AS duration,
               m.title AS media_title,
               pl.title AS playlist_title
        FROM play_history h
        LEFT JOIN media_data m ON h.source_type = 0 AND m.id = substr(h.source_key, 5)
        LEFT JOIN playlist pl ON h.source_type = 1 AND pl.id = substr(h.source_key, 4)
        WHERE h.source_type IN (0, 1)
        GROUP BY h.source_type, h.source_key
        ORDER BY last_played_at DESC
    """)
    fun observeGroupedHistory(): Flow<List<HistoryGroupSummary>>

    /** 历史页文件视图：STANDALONE 单文件历史（不聚合） */
    @Query("SELECT * FROM play_history WHERE source_type = 2 ORDER BY updated_at DESC")
    fun observeStandaloneHistory(): Flow<List<PlayHistory>>

    // ---- 单次查询（suspend）----

    /** 续播：同一视频在不同来源下各自进度 */
    @Query("SELECT * FROM play_history WHERE video_id = :videoId AND source_key = :sourceKey LIMIT 1")
    suspend fun getByVideoAndSource(videoId: String, sourceKey: String): PlayHistory?

    /** 某来源的最新一条历史（分组项补全进度/时长） */
    @Query("SELECT * FROM play_history WHERE source_type = :source AND source_key = :sourceKey ORDER BY updated_at DESC LIMIT 1")
    suspend fun getLatestBySource(source: PlaySource, sourceKey: String): PlayHistory?

    /** 「这剧看到第几集」（替代 media_data.playLast 的 URL 匹配） */
    @Query("SELECT video_id FROM play_history WHERE source_key = :sourceKey ORDER BY updated_at DESC LIMIT 1")
    suspend fun lastPlayedVideoId(sourceKey: String): String?

    @Query("SELECT count(*) FROM play_history")
    suspend fun count(): Int

    /** 全表快照（备份导出用） */
    @Query("SELECT * FROM play_history")
    suspend fun list(): List<PlayHistory>

    /**
     * 历史扁平视图联查：历史行关联的视频实体（同一视频多来源只取一条），
     * 按最近播放倒序，替代 SP historyJson 读取（旧 SP 列表存正序、展示前 reverse）
     */
    @Query("""
        SELECT v.* FROM video_data v
        INNER JOIN play_history p ON v.id = p.video_id
        GROUP BY v.id
        ORDER BY MAX(p.updated_at) DESC
    """)
    suspend fun listHistoryVideos(): List<VideoData>

    /** 删除单个视频的全部来源历史（历史详情页「删除当前」） */
    @Query("DELETE FROM play_history WHERE video_id = :videoId")
    suspend fun deleteByVideoId(videoId: String)

    /** 清空全部播放历史（历史详情页「清除全部」） */
    @Query("DELETE FROM play_history")
    suspend fun deleteAll()

    /** 按视频 URL 前缀清理历史（删除资源库时用，联查 video_data 前缀匹配） */
    @Query("DELETE FROM play_history WHERE video_id IN (SELECT id FROM video_data WHERE video_url LIKE :pattern)")
    suspend fun deleteByVideoUrlPrefix(pattern: String)

    /** 长按删除整组历史（历史页组项，按来源删除） */
    @Query("DELETE FROM play_history WHERE source_type = :source AND source_key = :sourceKey")
    suspend fun deleteBySource(source: PlaySource, sourceKey: String)

    /** 长按删除单条历史（历史页文件项，同一视频其他来源进度不受影响） */
    @Query("DELETE FROM play_history WHERE video_id = :videoId AND source_key = :sourceKey")
    suspend fun deleteByVideoAndSource(videoId: String, sourceKey: String)

    // ---- 写入 ----

    @Upsert
    suspend fun upsert(history: PlayHistory)

    /**
     * 迁移合并写入：唯一索引 (video_id, source_key) 已存在则跳过，返回 rowId（-1 表示未插入）。
     * 「稍后再说」后真实播放已写过进度，重迁移时不能用 SP 快照（duration=0）覆盖库内行。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(history: PlayHistory): Long

    @Insert
    suspend fun insertAll(historyList: List<PlayHistory>)

    /**
     * 开播打点（对应 PlayHelper.saveHistory）：单条 INSERT ... ON CONFLICT 完成"存在则只刷
     * updated_at、不存在则插入"，不覆盖已有 progress/duration。
     * 用单语句而非"SELECT + @Upsert"是为了避免 saveHistory 与进度刷新并发时的读改写竞态：
     * 两者都跑在 Dispatchers.IO 上，谁先谁后无法保证，UPDATE 抢在 INSERT 前会静默影响 0 行。
     */
    @Query("""
        INSERT INTO play_history (video_id, source_type, source_key, progress, duration, created_at, updated_at)
        VALUES (:videoId, :sourceType, :sourceKey, 0, 0, :now, :now)
        ON CONFLICT(video_id, source_key) DO UPDATE SET
            updated_at = excluded.updated_at
    """)
    suspend fun touch(videoId: String, sourceType: Int, sourceKey: String, now: Long)

    /**
     * 进度刷新（对应 PlayHelper.updateHistoryProgress）：同 [touch] 的原子写法，
     * 但冲突时更新 progress/duration/updated_at；行尚不存在时直接插入（省掉一次写事务）。
     * 注意：插入分支会触发 video_id → video_data 外键，调用方必须先确保父行已落库。
     */
    @Query("""
        INSERT INTO play_history (video_id, source_type, source_key, progress, duration, created_at, updated_at)
        VALUES (:videoId, :sourceType, :sourceKey, :progress, :duration, :now, :now)
        ON CONFLICT(video_id, source_key) DO UPDATE SET
            progress = excluded.progress,
            duration = excluded.duration,
            updated_at = excluded.updated_at
    """)
    suspend fun upsertProgress(
        videoId: String,
        sourceType: Int,
        sourceKey: String,
        progress: Long,
        duration: Long,
        now: Long
    )

    /** 播放完成清零进度（对应 PlayHelper.resetPlaySeek）
     *  若目标行不存在（开播打点被删/尚未写入）则补插一条，保证"看完后重新播放"不会被旧进度 seek 回去 */
    @Query("""
        INSERT INTO play_history (video_id, source_type, source_key, progress, duration, created_at, updated_at)
        VALUES (:videoId, :sourceType, :sourceKey, 0, 0, :now, :now)
        ON CONFLICT(video_id, source_key) DO UPDATE SET
            progress = 0,
            updated_at = excluded.updated_at
    """)
    suspend fun resetProgress(videoId: String, sourceType: Int, sourceKey: String, now: Long)
}
