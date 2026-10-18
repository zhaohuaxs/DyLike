package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 播放来源
 *
 * 同一视频在不同来源下各自保留进度（play_history 以 (video_id, source_key) 唯一）。
 * sourceKey 格式："lib:{mediaId}" / "pl:{playlistId}" / "standalone"，永非 null。
 */
@Serializable
enum class PlaySource(val value: Int) {
    LIBRARY(0),     // 从媒体库进入，sourceKey = "lib:{mediaId}"
    PLAYLIST(1),    // 从播放列表进入，sourceKey = "pl:{playlistId}"
    STANDALONE(2);  // 从文件夹/串流/历史等直接进入，sourceKey = "standalone"

    companion object {
        /** sourceKey 规范格式集中定义，写入/查询必须同源 */
        const val STANDALONE_KEY = "standalone"

        fun libraryKey(mediaId: String): String = "lib:$mediaId"

        fun playlistKey(playlistId: String): String = "pl:$playlistId"

        fun fromValue(value: Int): PlaySource {
            return entries.firstOrNull { it.value == value } ?: STANDALONE
        }
    }
}

/**
 * 播放历史表：播放进度 + 播放来源
 *
 * - video_id 是 FK → video_data（删视频即删记录）
 * - source_key 不加 FK：进度独立于媒体/播放列表生命周期，
 *   悬空时 UI 容错（标题显示"已删除"）
 * - (video_id, source_key) 复合唯一索引：source_key 永非 null，@Upsert 可自动去重
 * - 无条数上限（SP 时代的 100 条限制不再需要）
 */
@Entity(
    tableName = "play_history",
    foreignKeys = [
        ForeignKey(
            entity = me.lingci.dy.player.entity.VideoData::class,
            parentColumns = ["id"],
            childColumns = ["video_id"],
            onDelete = ForeignKey.CASCADE
        )
        // ★ 不加 mediaId/playlistId 的 FK：进度应独立于播放列表/媒体的生命周期
    ],
    indices = [
        Index("video_id"),
        Index("source_type"),
        Index(value = ["video_id", "source_key"], unique = true)
    ]
)
@Serializable
data class PlayHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "video_id") val videoId: String,              // FK → video_data
    @ColumnInfo(name = "source_type") val sourceType: PlaySource,   // 播放来源
    @ColumnInfo(name = "source_key") val sourceKey: String,         // 永非 null
    val progress: Long = 0L,                                         // 播放进度 (ms)
    val duration: Long = 0L,                                         // 总时长 (ms)
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") var updatedAt: Long = System.currentTimeMillis()
)
