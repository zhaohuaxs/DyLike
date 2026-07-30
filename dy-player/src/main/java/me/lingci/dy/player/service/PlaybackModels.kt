package me.lingci.dy.player.service

import android.graphics.Bitmap

/**
 * 后台播放的元数据,用于通知栏显示 + MediaSession。
 */
data class PlaybackMetadata(
    val title: String,           // 视频标题
    val subtitle: String = "",   // 副标题(如来源、第 N 个)
    val coverBitmap: Bitmap? = null,  // 封面(可为 null)
    val duration: Long = 0,      // 总时长(ms)
    val currentPosition: Long = 0  // 当前进度(ms)
)

/**
 * 通知栏 / Service → Activity 的广播 action 常量。
 * PLAY/PAUSE 由 Service 直接处理(Service 持有 player)。
 */
object PlaybackAction {
    /** 通知栏点了"上一个" */
    const val ACTION_PREV = "me.lingci.dy.player.playback.PREV"
    /** 通知栏点了"下一个" */
    const val ACTION_NEXT = "me.lingci.dy.player.playback.NEXT"
    /** 通知栏点了"关闭" */
    const val ACTION_CLOSE = "me.lingci.dy.player.playback.CLOSE"
    /** 通知栏点了"播放" */
    const val ACTION_PLAY = "me.lingci.dy.player.playback.PLAY"
    /** 通知栏点了"暂停" */
    const val ACTION_PAUSE = "me.lingci.dy.player.playback.PAUSE"
    /** 通知栏点了"后退15秒" */
    const val ACTION_REWIND = "me.lingci.dy.player.playback.REWIND"
    /** 通知栏点了"前进15秒" */
    const val ACTION_FORWARD = "me.lingci.dy.player.playback.FORWARD"
    /** 通知栏拖动了进度条 */
    const val ACTION_SEEK = "me.lingci.dy.player.playback.SEEK"
    /** SEEK 广播的 position extra(ms) */
    const val EXTRA_SEEK_POSITION = "seek_position"
}
