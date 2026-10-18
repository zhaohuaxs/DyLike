package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 收藏表
 *
 * - video_id 单列唯一：一个视频至多一条收藏；写入用 ON CONFLICT IGNORE 去重
 *   （id 是自增主键，@Upsert 只按主键判存在，重复收藏会撞唯一索引）
 * - media_id 可空：收藏的视频可能无媒体上下文（虚拟媒体/独立文件）
 *   注意空串不是「无上下文」——空串会去 media_data 找父行并必然找不到，写入时应传 null
 * - 收藏依附于视频存在，删视频即删收藏（CASCADE）
 */
@Entity(
    tableName = "favorite",
    foreignKeys = [
        ForeignKey(
            entity = me.lingci.dy.player.entity.MediaData::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = me.lingci.dy.player.entity.VideoData::class,
            parentColumns = ["id"],
            childColumns = ["video_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("media_id"),
        Index(value = ["video_id"], unique = true)
    ]
)
@Serializable
data class Favorite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: String?,  // 可空：收藏的视频可能无媒体上下文
    @ColumnInfo(name = "video_id") val videoId: String,   // FK → video_data
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)
