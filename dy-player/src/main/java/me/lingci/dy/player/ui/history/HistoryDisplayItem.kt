package me.lingci.dy.player.ui.history

import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.entity.PlaySource

/**
 * 历史页展示数据模型（方案 §5.7.2）：
 * LIBRARY/PLAYLIST 按 source_key 聚合为组项，STANDALONE 为单文件项。
 * 富化字段（标题/封面/最后播放集）由 HistoryActivity 根据 source_key 查库补全（容错"已删除"）。
 */
sealed class HistoryDisplayItem {

    /** 最后播放时间（历史页合并排序用，两种形态均有） */
    abstract val lastPlayedAt: Long

    /** 组项：从媒体库/播放列表进入的播放历史聚合（文件夹形态） */
    data class HistoryGroup(
        val sourceType: PlaySource,       // LIBRARY 或 PLAYLIST
        val sourceKey: String,            // "lib:{mediaId}" / "pl:{playlistId}"
        val mediaId: String,              // sourceKey 去前缀后的媒体库 id（LIBRARY 用）
        val playlistId: String,           // sourceKey 去前缀后的播放列表 id（PLAYLIST 用）
        val media: MediaData?,            // LIBRARY 组的媒体实体（已删为 null，标题/封面容错）
        val title: String,                // 组标题（媒体/播放列表已删则"已删除"）
        val lastVideoName: String,        // 最后播放的集名
        val lastVideoUrl: String,         // 最后播放的集 URL（组项缩略图回退用）
        val progress: Long,               // 最后播放集的进度 (ms)
        val duration: Long,               // 最后播放集的总时长 (ms)
        val epCount: Int,                 // 已播放集数
        override val lastPlayedAt: Long
    ) : HistoryDisplayItem()

    /** 文件项：单文件/串流直接播放的历史（文件形态） */
    data class HistorySingle(
        val videoData: VideoData,
        val sourceKey: String,            // 长按删除用（恒为 "standalone"）
        val progress: Long,               // 播放进度 (ms)
        val duration: Long,               // 总时长 (ms)
        override val lastPlayedAt: Long
    ) : HistoryDisplayItem()
}
