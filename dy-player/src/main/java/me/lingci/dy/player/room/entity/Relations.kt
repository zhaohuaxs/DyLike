package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Relation
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData

/**
 * 媒体 + 其全部剧集的关联查询结果（@Relation 需 @Transaction，Room 3.0.3 已修复相关回归）
 */
data class MediaWithVideos(
    @Embedded val media: MediaData,
    @Relation(parentColumns = ["id"], entityColumns = ["media_id"])
    val videos: List<VideoData>
)

/**
 * 首页「继续观看」一行需要的字段（PlayHistoryDao.observeResumeRows）。
 *
 * 以前是「取 4 条历史 → 逐条查 media + video」，一次刷新 9 次查询；改成一条联查后
 * 无论列表多长都只发一次 SQL。缩略图路径由 ViewModel 用 [videoId] 换算（它就是 video_data 主键）。
 */
data class ResumeRow(
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "progress") val progress: Long,
    @ColumnInfo(name = "duration") val duration: Long,
    @ColumnInfo(name = "media_title") val mediaTitle: String,
    @ColumnInfo(name = "video_name") val videoName: String
)

/**
 * 播放列表条目数（PlaylistItemDao.observeItemCounts 的聚合结果）。
 *
 * 列表页以前对每个播放列表调一次 `countByPlaylist`，N 个列表就是 N 次查询；
 * 改成一次 GROUP BY 后与 playlist 主表 Flow 直接 combine。
 */
data class PlaylistItemCount(
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "item_count") val itemCount: Int
)

/**
 * 历史页聚合查询的中间结果（PlayHistoryDao.observeGroupedHistory）：
 * LIBRARY/PLAYLIST 按 source_key 分组取最新，标题在 SQL 侧 LEFT JOIN 补全（容错"已删除"）。
 * 不是实体。
 *
 * [progress]/[duration] 取该组最新一条记录：以前由业务层对每个分组各查一次
 * （`getLatestBySource`），列表一长就是 N+1；放在 SQL 里每组只多两个子查询。
 */
data class HistoryGroupSummary(
    @ColumnInfo(name = "source_type") val sourceType: Int,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
    @ColumnInfo(name = "ep_count") val epCount: Int,
    @ColumnInfo(name = "progress") val progress: Long = 0,
    @ColumnInfo(name = "duration") val duration: Long = 0,
    /** 关联媒体标题；source_key 的媒体已删除时为 null */
    @ColumnInfo(name = "media_title") val mediaTitle: String? = null,
    /** 关联播放列表标题；source_key 的列表已删除时为 null */
    @ColumnInfo(name = "playlist_title") val playlistTitle: String? = null
)
