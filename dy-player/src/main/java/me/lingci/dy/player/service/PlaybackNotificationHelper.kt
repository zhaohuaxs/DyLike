package me.lingci.dy.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import me.lingci.dy.player.R

/**
 * 后台播放通知构建器(MediaStyle + 标准按钮)。
 */
class PlaybackNotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "dy_player_playback"
        const val CHANNEL_NAME = "后台播放"
        const val NOTIFICATION_ID = 1001
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "视频后台播放控制"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager?.createNotificationChannel(channel)
            }
        }
    }

    fun buildNotification(
        metadata: PlaybackMetadata,
        isPlaying: Boolean,
        sessionToken: android.support.v4.media.session.MediaSessionCompat.Token?,
        contentIntent: PendingIntent,
        position: Long = metadata.currentPosition
    ): Notification {
        ensureChannel()

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(metadata.title)
            .setContentText(metadata.subtitle)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)

        // 通知被用户划掉时触发 deleteIntent → 发 CLOSE 广播 → Service 释放 player 停止
        val deleteIntent = Intent(PlaybackAction.ACTION_CLOSE).setPackage(context.packageName)
        val deleteFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        builder.setDeleteIntent(PendingIntent.getBroadcast(context, PlaybackAction.ACTION_CLOSE.hashCode(), deleteIntent, deleteFlags))

        if (metadata.coverBitmap != null) {
            builder.setLargeIcon(metadata.coverBitmap)
        }

        if (metadata.duration > 0) {
            val pos = position.coerceIn(0L, metadata.duration)
            builder.setProgress(metadata.duration.toInt(), pos.toInt(), false)
        }

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseAction = if (isPlaying) PlaybackAction.ACTION_PAUSE else PlaybackAction.ACTION_PLAY

        builder.addAction(buildAction(PlaybackAction.ACTION_PREV, android.R.drawable.ic_media_previous, "上一个"))
        builder.addAction(buildAction(PlaybackAction.ACTION_REWIND, R.drawable.ic_rewind_15, "后退15秒"))
        builder.addAction(buildAction(playPauseAction, playPauseIcon, if (isPlaying) "暂停" else "播放"))
        builder.addAction(buildAction(PlaybackAction.ACTION_FORWARD, R.drawable.ic_forward_15, "前进15秒"))
        builder.addAction(buildAction(PlaybackAction.ACTION_NEXT, android.R.drawable.ic_media_next, "下一个"))

        val mediaStyle = MediaStyle()
        if (sessionToken != null) {
            mediaStyle.setMediaSession(sessionToken)
        }
        mediaStyle.setShowActionsInCompactView(0, 2, 4)
        builder.setStyle(mediaStyle)

        return builder.build()
    }

    private fun buildAction(action: String, icon: Int, label: String): NotificationCompat.Action {
        val intent = Intent(action).setPackage(context.packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getBroadcast(context, action.hashCode(), intent, flags)
        return NotificationCompat.Action.Builder(icon, label, pendingIntent).build()
    }
}
