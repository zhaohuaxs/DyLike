package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 一次播放会话的队列快照。
 *
 * 替代 `VideoListTempStore` 的 JSON 缓存文件（起播场景）：写入走 IO 线程 + 事务，
 * 读取不删除（Activity 重建可重复读），会话结束由播放页显式清理。
 *
 * 无外键：浏览器/外部文件打开的队列不一定在媒体库里。
 * `headers` 在 VideoData 上是 @Ignore，WebDAV 的 Authorization 必须单独保留。
 */
@Entity(
    tableName = "playing_list_item",
    indices = [
        Index("session_id"),
        Index(value = ["session_id", "sort_in_session"])
    ]
)
data class PlayingListItem(
    @PrimaryKey(autoGenerate = true)
    val rowId: Long = 0L,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "sort_in_session")
    val sortInSession: Int,

    /** 季名；空串表示普通队列。播放页据此在季末给出提示。 */
    @ColumnInfo(name = "season_label", defaultValue = "''")
    val seasonLabel: String = "",

    val id: String,
    val name: String,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "video_url")
    val videoUrl: String,
    @ColumnInfo(name = "dm_url")
    val dmUrl: String = "",
    val preview: String = "",
    /** StorageType.name（库内 storage_type 列为 TEXT，见 1.json） */
    @ColumnInfo(name = "storage_type")
    val storageType: String = "LOCAL_STORAGE",
    @ColumnInfo(name = "parent_path")
    val parentPath: String = "",
    @ColumnInfo(name = "headers_json")
    val headersJson: String = ""
)
