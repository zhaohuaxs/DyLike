package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 播放列表表
 */
@Serializable
@Entity(tableName = "playlist")
data class Playlist(
    @PrimaryKey val id: String = "",
    var title: String = "",
    @ColumnInfo(name = "play_mode") var playMode: Int = 0, // 播放模式（顺序/随机等）
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") var updatedAt: Long = System.currentTimeMillis()
)

/**
 * 播放列表条目表
 *
 * - playlist_id / media_id / video_id 均 CASCADE：
 *   删播放列表即删条目；删媒体/视频也同步清理条目
 * - media_id 可空：条目的视频可能无媒体上下文
 */
@Entity(
    tableName = "playlist_item",
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE
        ),
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
    indices = [Index("playlist_id"), Index("media_id"), Index("video_id")]
)
@Serializable
data class PlaylistItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    @ColumnInfo(name = "media_id") val mediaId: String?,
    @ColumnInfo(name = "video_id") val videoId: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)
