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
 * 历史页聚合查询的中间结果（PlayHistoryDao.observeGroupedHistory）：
 * LIBRARY/PLAYLIST 按 source_key 分组取最新，标题/封面由业务层补全（容错"已删除"）。
 * 仅含聚合字段，不是实体。
 */
data class HistoryGroupSummary(
    @ColumnInfo(name = "source_type") val sourceType: Int,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long,
    @ColumnInfo(name = "ep_count") val epCount: Int
)
