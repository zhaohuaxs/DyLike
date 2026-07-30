package me.lingci.dy.player.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import xyz.doikki.videoplayer.player.AbstractPlayer

/**
 * 后台播放 Foreground Service。
 *
 * 职责:
 * 1. 持有 player 引用(临时,从 Activity 转入) + setVideoSurface(null) 停止视频解码
 * 2. 显示 MediaStyle 通知 + 前台保活
 * 3. MediaSession(对接锁屏/蓝牙/车机)
 * 4. 音频焦点管理(LOSS_TRANSIENT 暂停 / GAIN 恢复)
 *
 * 不负责:创建 player、加载媒体源、进度保存、列表管理。
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
        /** 通知栏切集 extra:+1=下一个,-1=上一个。 */
        const val EXTRA_SKIP = "playback_skip"
    }

    private val binder = PlaybackBinder(this)
    private var player: AbstractPlayer? = null
    private var metadata: PlaybackMetadata? = null
    private var mediaSession: MediaSessionCompat? = null
    private var notificationHelper: PlaybackNotificationHelper? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null

    /** 定时刷新通知进度条(每秒更新 MediaSession position)。 */
    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val md = metadata ?: return
            if (p.isPlaying) {
                // 用 player 实时 duration 修正 metadata(detach 时可能为 0)
                val liveDuration = p.duration
                if (liveDuration > 0 && md.duration != liveDuration) {
                    metadata = md.copy(duration = liveDuration)
                }
                updateMediaSession(metadata!!, isPlaying = true)
                updateNotification()
            }
            progressHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 后台切集回调:Activity 在 startPlaybackService 时注册。
     * Service 收到通知栏"上一个/下一个"时调用,让 Activity 在后台切集,
     * 新视频起播后重新 detach 给 Service(不拉起 Activity)。
     */
    @Volatile
    var skipCallback: ((Int) -> Unit)? = null

    /**
     * seek 目标位置(ms)。用户拖动进度条/后退/前进时设置。
     * progressRunnable 优先用它显示(避免 seek 异步期间进度回退),
     * 当 player 实际 position 追上后清除。
     */
    @Volatile
    private var pendingSeekPosition: Long = -1L

    /** 通知栏播放/暂停按钮接收器(Service 持有 player,直接控制)。 */
    private val playPauseReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "playPauseReceiver onReceive: ${intent?.action}")
            when (intent?.action) {
                PlaybackAction.ACTION_PLAY -> {
                    player?.start()
                    updateMediaSession(metadata ?: return, isPlaying = true)
                    updateNotification()
                }
                PlaybackAction.ACTION_PAUSE -> {
                    player?.pause()
                    updateMediaSession(metadata ?: return, isPlaying = false)
                    updateNotification()
                }
                PlaybackAction.ACTION_REWIND -> seekRelative(-15000)
                PlaybackAction.ACTION_FORWARD -> seekRelative(15000)
                PlaybackAction.ACTION_SEEK -> {
                    val pos = intent?.getLongExtra(PlaybackAction.EXTRA_SEEK_POSITION, -1L) ?: -1L
                    if (pos >= 0) {
                        player?.seekTo(pos)
                        updateNotification()
                    }
                }
                PlaybackAction.ACTION_NEXT, PlaybackAction.ACTION_PREV -> {
                    // 后台切集:通过回调让 Activity 切集(不拉起 Activity 到前台)。
                    // Activity 切集后新 player 起播时重新 detach 给 Service。
                    val cb = skipCallback ?: return
                    val direction = if (intent?.action == PlaybackAction.ACTION_NEXT) 1 else -1
                    android.os.Handler(android.os.Looper.getMainLooper()).post { cb(direction) }
                }
                PlaybackAction.ACTION_CLOSE -> {
                    player?.release()
                    player = null
                    stopForegroundAndNotification()
                    stopSelf()
                }
            }
        }
    }
    private var isPlayPauseReceiverRegistered = false

    val isHoldingPlayer: Boolean get() = player != null

    override fun onCreate() {
        super.onCreate()
        notificationHelper = PlaybackNotificationHelper(this)
        notificationHelper?.ensureChannel()
        setupMediaSession()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        registerPlayPauseReceiver()
        Log.i(TAG, "PlaybackService created")
    }

    private fun registerPlayPauseReceiver() {
        if (isPlayPauseReceiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(PlaybackAction.ACTION_PLAY)
            addAction(PlaybackAction.ACTION_PAUSE)
            addAction(PlaybackAction.ACTION_NEXT)
            addAction(PlaybackAction.ACTION_PREV)
            addAction(PlaybackAction.ACTION_CLOSE)
            addAction(PlaybackAction.ACTION_REWIND)
            addAction(PlaybackAction.ACTION_FORWARD)
            addAction(PlaybackAction.ACTION_SEEK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(playPauseReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(playPauseReceiver, filter)
        }
        isPlayPauseReceiverRegistered = true
    }

    private fun unregisterPlayPauseReceiver() {
        if (isPlayPauseReceiverRegistered) {
            try { unregisterReceiver(playPauseReceiver) } catch (_: Exception) {}
            isPlayPauseReceiverRegistered = false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 从 Intent extra 读取来源 Activity(在 startForeground 前就设置好 contentIntent)
        intent?.getStringExtra("source_activity")?.let { className ->
            try {
                sourceActivityClass = Class.forName(className)
            } catch (_: ClassNotFoundException) {}
        }
        // 立即 startForeground(空通知),避免 5 秒超时
        val placeholderNotification = notificationHelper?.buildNotification(
            PlaybackMetadata(title = "正在后台播放"),
            isPlaying = false,
            sessionToken = mediaSession?.sessionToken,
            contentIntent = createContentIntent()
        )
        if (placeholderNotification != null) {
            startForeground(PlaybackNotificationHelper.NOTIFICATION_ID, placeholderNotification)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Activity 把 player 交给 Service。 */
    fun takePlayer(player: AbstractPlayer, metadata: PlaybackMetadata) {
        this.player = player
        this.metadata = metadata
        // setVideoSurface(null) 已在 BaseVideoView.detachPlayerForBackground 中调用
        // 更新 MediaSession + 通知
        mediaSession?.isActive = true  // M3: 确保每次 takePlayer 都激活 MediaSession
        updateMediaSession(metadata, isPlaying = player.isPlaying)
        updateNotification()
        // M2: 先释放可能存在的旧 AudioFocus,再重新申请,避免反复 takePlayer 导致 focus 泄漏
        abandonAudioFocus()
        requestAudioFocus()
        // 启动进度条定时刷新
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
        Log.i(TAG, "took player: ${metadata.title}")
    }

    /** Activity 取回 player。 */
    fun returnPlayer(): AbstractPlayer? {
        progressHandler.removeCallbacks(progressRunnable)
        val p = player
        player = null
        abandonAudioFocus()
        stopForegroundAndNotification()
        Log.i(TAG, "returned player")
        return p
    }

    fun updateMetadata(metadata: PlaybackMetadata) {
        this.metadata = metadata
        updateMediaSession(metadata, isPlaying = player?.isPlaying == true)
        updateNotification()
    }

    fun stopForegroundAndNotification() {
        abandonAudioFocus()
        // minSdk = 24 (N),STOP_FOREGROUND_REMOVE 自 API 24 起可用,可直接调用
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSession?.isActive = false
    }

    /**
     * 用户从最近任务列表划掉 app 时调用。
     * 全局约束:划掉 app = 停止播放(不跨进程)。
     */
    /**
     * 用户从最近任务列表划掉 app 时调用。
     * 释放 player 停止播放。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.release()
        player = null
        abandonAudioFocus()
        stopForegroundAndNotification()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 如果 Service 被销毁时仍持有 player(Activity 异常销毁)，释放它
        player?.release()
        player = null
        abandonAudioFocus()
        progressHandler.removeCallbacks(progressRunnable)
        unregisterPlayPauseReceiver()
        mediaSession?.release()
        mediaSession = null
        Log.i(TAG, "PlaybackService destroyed")
    }

    // === 内部方法 ===

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "DyPlayer").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { Log.i(TAG, "MediaSession onPlay"); player?.start(); updateNotification() }
                override fun onPause() { Log.i(TAG, "MediaSession onPause"); player?.pause(); updateNotification() }
                override fun onStop() { Log.i(TAG, "MediaSession onStop"); player?.stop() }
                override fun onSkipToNext() { playPauseReceiver.onReceive(this@PlaybackService, Intent(PlaybackAction.ACTION_NEXT)) }
                override fun onSkipToPrevious() { playPauseReceiver.onReceive(this@PlaybackService, Intent(PlaybackAction.ACTION_PREV)) }
                override fun onSeekTo(pos: Long) {
                    Log.i(TAG, "MediaSession onSeekTo: $pos")
                    pendingSeekPosition = pos
                    player?.seekTo(pos)
                    // 立即更新 MediaSession + 通知,避免系统卡片用旧 position 渲染导致回退
                    metadata?.let { updateMediaSession(it, isPlaying = player?.isPlaying == true) }
                    updateNotification()
                }
                override fun onRewind() { seekRelative(-15000) }
                override fun onFastForward() { seekRelative(15000) }
            })
            isActive = true
        }
    }

    private fun updateMediaSession(metadata: PlaybackMetadata, isPlaying: Boolean) {
        val session = mediaSession ?: return
        // PlaybackState - 用 displayPosition(seek 期间避免进度条回退)
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, displayPosition, 1.0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_FAST_FORWARD
            )
            .build()
        session.setPlaybackState(playbackState)
        // Metadata
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.subtitle)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.duration)
        if (metadata.coverBitmap != null) {
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, metadata.coverBitmap)
        }
        session.setMetadata(md.build())
    }

    /** 相对 seek(±秒数),用于后退/前进按钮。 */
    private fun seekRelative(deltaMs: Long) {
        val p = player ?: return
        val target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val dur = p.duration
        val final = if (dur > 0) target.coerceAtMost(dur) else target
        pendingSeekPosition = final
        p.seekTo(final)
        Log.i(TAG, "seekRelative: delta=$deltaMs target=$final")
        metadata?.let { updateMediaSession(it, isPlaying = p.isPlaying) }
        updateNotification()
    }

    /** 获取当前应显示的 position:优先用 pendingSeekPosition(seek 期间避免回退)。 */
    private val displayPosition: Long
        get() {
            val p = player ?: return pendingSeekPosition.coerceAtLeast(0)
            val actual = p.currentPosition
            if (pendingSeekPosition >= 0 && kotlin.math.abs(actual - pendingSeekPosition) > 1000) {
                return pendingSeekPosition
            }
            if (pendingSeekPosition >= 0) pendingSeekPosition = -1L
            return actual
        }

    private fun updateNotification() {
        val md = metadata ?: return
        val p = player ?: return
        val notification = notificationHelper?.buildNotification(
            md,
            isPlaying = p.isPlaying,
            sessionToken = mediaSession?.sessionToken,
            contentIntent = createContentIntent(),
            position = displayPosition
        ) ?: return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(PlaybackNotificationHelper.NOTIFICATION_ID, notification)
    }

    /** Activity 在 takePlayer 时设置,用于通知点击恢复到正确的 Activity。 */
    private var sourceActivityClass: Class<*>? = null

    fun setSourceActivity(cls: Class<*>) {
        sourceActivityClass = cls
        // 立即更新通知(让 contentIntent 指向正确的 Activity)
        if (metadata != null && player != null) {
            updateNotification()
        }
    }

    private fun createContentIntent(): android.app.PendingIntent {
        // 点击通知打开来源 Activity(长视频/短视频),而非 MainActivity
        val cls = sourceActivityClass ?: me.lingci.dy.player.ui.main.MainActivity::class.java
        val intent = Intent(this, cls)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // 关键: requestCode 必须随目标 Activity 变化,否则首次用 MainActivity 占位通知生成的
        // PendingIntent(requestCode=0) 会被系统缓存,后续 setSourceActivity 改成 LongVideoActivity
        // 后,FLAG_UPDATE_CURRENT 只更新 extras 而不替换已缓存的 component,导致点击通知仍打开
        // MainActivity(媒体库)而非播放页。改用 cls.hashCode() 作为 requestCode 即可隔离。
        val requestCode = cls.name.hashCode()
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE
        Log.i(TAG, "createContentIntent: target=${cls.name} requestCode=$requestCode")
        return android.app.PendingIntent.getActivity(this, requestCode, intent, flags)
    }

    // === AudioFocus ===

    private fun requestAudioFocus() {
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focus ->
            when (focus) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    player?.pause()
                    metadata?.let { updateNotification() }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    player?.setVolume(0.1f, 0.1f)
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    player?.setVolume(1.0f, 1.0f)
                    // LOSS 时不自动恢复(用户手动点播放);GAIN(从 DUCK 恢复)可以继续
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusChangeListener!!)
                .build()
            audioManager?.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            audioFocusChangeListener?.let {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(it)
            }
        }
        audioFocusChangeListener = null
    }
}
