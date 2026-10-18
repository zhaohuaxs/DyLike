package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 随机播放状态表（对应 SP 的 mediaShuffleJson）
 *
 * - mediaId 是 PK 也是 FK → media_data（删媒体即删状态）
 * - selectedIds / currentDisplayIds 以 JSON 字符串存储，
 *   由业务层手动 JsonUtil 序列化，不需 TypeConverter
 */
@Entity(
    tableName = "media_shuffle_state",
    foreignKeys = [ForeignKey(
        entity = me.lingci.dy.player.entity.MediaData::class,
        parentColumns = ["id"],
        childColumns = ["media_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("media_id")]
)
@Serializable
data class MediaShuffleStateEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: String,  // FK → media_data
    @ColumnInfo(name = "selected_ids") val selectedIds: String,      // JSON: Set<String>
    @ColumnInfo(name = "current_display_ids") val currentDisplayIds: String,  // JSON: List<String>
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
