package me.lingci.dy.player.ui.long_video

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Rational
import androidx.annotation.RequiresApi
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.databinding.ActivityLongVideoBinding
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.DyPlayerCoreRegistry
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.service.PlaybackAction
import me.lingci.dy.player.service.PlaybackBinder
import me.lingci.dy.player.service.PlaybackMetadata
import me.lingci.dy.player.service.PlaybackService
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.ExternalSubtitleLoader
import me.lingci.dy.player.util.MediaPlaybackRecorder
import me.lingci.dy.player.util.PlaybackTraceHelper
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.VideoListTempStore
import me.lingci.dy.player.view.LongVideoControlView
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.md5
import me.lingci.lib.base.util.safeGetParcelable
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.exo.CustomExoMediaPlayer
import me.lingci.lib.player.listener.OnFontChangeListener
import me.lingci.lib.player.listener.OnLongVideoListener
import me.lingci.lib.player.listener.OnPlayNextListener
import me.lingci.lib.player.render.SurfaceRenderViewFactory
import me.lingci.lib.player.widget.component.DanmakuControlView
import me.lingci.lib.player.widget.component.DmConfControlView
import me.lingci.lib.player.widget.component.DmListControlView
import me.lingci.lib.player.widget.component.DmSelectControlView
import me.lingci.lib.player.widget.component.DmTrackControlView
import me.lingci.lib.player.widget.component.EpSelectControlView
import me.lingci.lib.player.widget.component.MediaInfoControlView
import me.lingci.lib.player.widget.component.SpeedControlView
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.component.TrackPanelControlView
import me.lingci.lib.player.widget.component.VideoScaleControlView
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videocontroller.StandardVideoController
import xyz.doikki.videocontroller.component.CompleteView
import xyz.doikki.videocontroller.component.GestureView
import me.lingci.lib.player.capability.PlayerCapabilities
import me.lingci.lib.player.capability.PlayerCapabilityProvider
import me.lingci.lib.player.mediainfo.MediaInfoProviderOwner
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.track.MediaTrackController
import me.lingci.lib.player.track.MediaTrackProvider
import me.lingci.lib.player.track.MediaTrackSnapshot
import xyz.doikki.videoplayer.player.BaseVideoView.OnStateChangeListener
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.render.TextureRenderViewFactory
import xyz.doikki.videoplayer.util.PlayerUtils
import java.io.File

/**
 * 长视频播放页
 */
class LongVideoActivity : BaseActivity(), OnLongVideoListener, OnPlayNextListener, OnFontChangeListener {

    companion object {
        private const val KEY_BUNDLE = "bundle"
        private const val KEY_MEDIA = "media"
        private const val KEY_INDEX = "index"
        private const val KEY_HISTORY = "history"
        private const val KEY_TEMP = "temp"
        private const val BACKGROUND_RECOVERY_WINDOW_MS = 5000L

        // 超分开关广播 action（adb 和 LabSettingsFragment 都可发）
        const val ACTION_SUPER_RESOLUTION_ON = "me.lingci.dy.player.SUPER_RES_ON"
        const val ACTION_SUPER_RESOLUTION_OFF = "me.lingci.dy.player.SUPER_RES_OFF"

        fun start(context: Context, list: ArrayList<VideoData>, index: Int, history: Boolean) {
            val bundle = bundleOf()
            bundle.putInt(KEY_INDEX, index)
            bundle.putBoolean(KEY_HISTORY, history)
            start(context, bundle, list)
        }

        fun start(
            context: Context, mediaData: MediaData?, list: ArrayList<VideoData>,
            index: Int, history: Boolean
        ) {
            val bundle = bundleOf()
            bundle.putInt(KEY_INDEX, index)
            bundle.putBoolean(KEY_HISTORY, history)
            if (mediaData != null && !history) {
                bundle.putParcelable(KEY_MEDIA, mediaData)
            }
            start(context, bundle, list)
        }

        private fun start(context: Context, bundle: Bundle, list: ArrayList<VideoData>) {
            bundle.putString(KEY_TEMP, VideoListTempStore.write(context, list))
            val intent = Intent(context, LongVideoActivity::class.java)
            intent.putExtra(KEY_BUNDLE, bundle)
            context.startActivity(intent)
        }
    }

    /**
     * 当前播放位置
     */
    private var mCurPos = 0
    private val spUtil by lazy { SpUtil(this) }
    private var onHistory: Boolean = false
    private val itemViewModel: LongVideoViewModel by viewModels()
    private lateinit var binding: ActivityLongVideoBinding
    private lateinit var videoView: CustomVideoView
    private lateinit var videoController: StandardVideoController
    private lateinit var longVideoControlView: LongVideoControlView
    private lateinit var danmakuView: DanmakuControlView
    private lateinit var dmSelectView: DmSelectControlView
    private lateinit var dmConfView: DmConfControlView
    private lateinit var videoScaleControlView: VideoScaleControlView
    private lateinit var dmTrackView: DmTrackControlView
    private lateinit var dmListView: DmListControlView
    private lateinit var epSelectView: EpSelectControlView
    private lateinit var subtitleControlView: SubtitleControlView
    private lateinit var mTrackPanelControlView: TrackPanelControlView
    private lateinit var speedControlView: SpeedControlView
    private lateinit var mediaInfoControlView: MediaInfoControlView
    private val playbackLogCache = PlaybackLogCache()
    private var backgroundRecoveryJob: Job? = null
    private val mediaPlaybackRecorder by lazy { MediaPlaybackRecorder(spUtil) }
    // 轨道能力查询仍依赖当前 videoView，面板交互和字幕 cue 开关交给 controller。
    private val longTrackPanelController by lazy {
        LongTrackPanelController(
            spUtil = spUtil,
            trackPanelControlView = mTrackPanelControlView,
            longVideoControlView = longVideoControlView,
            subtitleControlView = subtitleControlView,
            getCapabilities = ::getPlayerCapabilities,
            getMediaTrackController = ::getMediaTrackController,
            hasSubtitleCueProvider = { getSubtitleCueProvider() != null },
            addExternalSubtitle = ::addExternalSubtitle,
            attachSubtitleCueListener = ::attachSubtitleCueListener,
            clearSubtitleCueListener = ::clearSubtitleCueListener
        )
    }
    // Activity 只提供当前视频和播放页回调，弹幕文件解析/合并/恢复交给 controller。
    private val longDanmakuController by lazy {
        LongDanmakuController(
            context = this,
            scope = lifecycleScope,
            spUtil = spUtil,
            danmakuView = danmakuView,
            dmSelectView = dmSelectView,
            dmConfView = dmConfView,
            dmTrackView = dmTrackView,
            dmListView = dmListView,
            longVideoControlView = longVideoControlView,
            videoView = videoView,
            currentVideoData = {
                if (itemViewModel.getItemSize() > mCurPos) itemViewModel.getItem(mCurPos) else null
            },
            currentVideoName = {
                if (itemViewModel.getItemSize() > mCurPos) itemViewModel.getItem(mCurPos).name else ""
            },
            isMerge = itemViewModel::isMerge,
            setMergeState = itemViewModel::setMergeState
        )
    }
    private var hasPausedForBackgroundRecovery = false
    private var lastKnownPlaybackPosition = 0L

    // ===== PiP 画中画相关状态 =====
    /** PiP 模式下保存的弹幕字体大小（用于退出 PiP 后恢复） */
    private var pipSavedDanmakuSize: Int = PlayerInitializer.Danmu.size
    /** PiP 模式下保存的滚动弹幕最大行数 */
    private var pipSavedMaxLine: Int = PlayerInitializer.Danmu.maxLine
    /** PiP 模式下保存的顶部弹幕最大行数 */
    private var pipSavedMaxTopLine: Int = PlayerInitializer.Danmu.maxTopLine
    /** PiP 模式下保存的底部弹幕最大行数 */
    private var pipSavedMaxBottomLine: Int = PlayerInitializer.Danmu.maxBottomLine
    /** PiP 操作按钮广播接收器 */
    private val pipActionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                PiPActionReceiver.ACTION_PREV -> onPreviousPlay()
                PiPActionReceiver.ACTION_PLAY_PAUSE -> togglePlayPause()
                PiPActionReceiver.ACTION_NEXT -> onNextPlay()
            }
        }
    }
    /** 标记 PiP 广播接收器是否已注册 */
    private var isPipReceiverRegistered = false
    /** 标记从 PiP 退出时视频是否正在播放（用于 onResume 恢复播放） */
    private var wasPlayingBeforePipExit = false

    /** 通知栏控制广播接收器(后台播放时通知按钮)。 */
    // ===== 后台播放 Service =====
    private var playbackService: PlaybackBinder? = null
    private var isBoundToPlaybackService = false
    /**
     * M2 fix: 标记 onStop 已发起后台播放绑定，但 Service 尚未把 player 接走。
     * onStart 取回前台时清掉；tryEnterBackgroundPlay 必须看到此标记才 detach。
     * 避免 onServiceConnected 在用户已返回前台后才触发，把 player 错误交给 Service。
     */
    private var pendingBackgroundEntry = false
    /**
     * 后台切集标志:通知栏点了上一个/下一个,新视频起播后(STATE_PLAYING)
     * 需要重新 detach 给 Service 并更新通知。
     */
    private var pendingBgSwitch = false
    private val playbackServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = service as? PlaybackBinder
            // Service 连上后，把 player 交给它
            tryEnterBackgroundPlay()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBoundToPlaybackService = false
        }
    }

    /** 标记：用户主动按返回退出（不进后台） */
    private var userInitiatedExit = false

    // 超分开关广播接收器：收到 ON/OFF 时写 SP 并重播当前视频以应用新的 render view。
    // 注意：GLSurfaceView 不能热切换，必须重建 player。
    private val superResolutionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == ACTION_SUPER_RESOLUTION_ON
            if (spUtil.labMpvSuperResolution == on) return  // 状态没变，跳过
            spUtil.labMpvSuperResolution = on
            android.widget.Toast.makeText(
                this@LongVideoActivity,
                if (on) "画质增强：已开启（重播以生效）" else "画质增强：已关闭（重播以生效）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            // 重播当前视频，触发 player + render view 重建
            startPlay(mCurPos)
        }
    }
    private var isSuperResReceiverRegistered = false

    private fun getPlayerCapabilities(): PlayerCapabilities {
        return videoView.getPlayerCapability(PlayerCapabilityProvider::class.java)
            ?.getPlayerCapabilities()
            ?: PlayerCapabilities()
    }

    private fun getMediaTrackProvider(): MediaTrackProvider? {
        // Track UI consumes common snapshots so it works for Exo and MPV without private models.
        return videoView.getPlayerCapability(MediaTrackProvider::class.java)
    }

    private fun getMediaTrackController(): MediaTrackController? {
        return videoView.getPlayerCapability(MediaTrackController::class.java)
    }

    private fun getExternalTrackController(): ExternalTrackController? {
        return videoView.getPlayerCapability(ExternalTrackController::class.java)
    }

    private fun getSubtitleCueProvider(): SubtitleCueProvider? {
        return videoView.getPlayerCapability(SubtitleCueProvider::class.java)
    }

    private fun getMediaInfoProviderOwner(): MediaInfoProviderOwner? {
        return videoView.getPlayerCapability(MediaInfoProviderOwner::class.java)
    }

    private fun logAndCache(tag: String, level: String, message: String) {
        // 统一走共享 trace helper，保证页面日志与诊断缓存格式一致。
        PlaybackTraceHelper.logAndCache(playbackLogCache, tag, level, message)
    }

    private fun setupSurfaceTrace() {
        PlaybackTraceHelper.setupSurfaceTrace(spUtil.debugMode, playbackLogCache)
    }

    private fun clearSurfaceTrace() {
        PlaybackTraceHelper.clearSurfaceTrace()
    }

    // ===== 后台恢复重试相关状态 ===== // track-id: bg-retry-20260421
    /**
     * 标记当前是否处于后台返回后的短暂恢复窗口
     * 仅在 onResume 中设为 true，恢复成功、自动重试或窗口结束后设为 false
     */
    private var isReturningFromBackground = false

    /**
     * 标记是否已经执行过后台恢复自动重试
     * 防止无限重试循环，每次进入后台只重试一次
     */
    private var hasAutoRetriedOnResume = false

    /**
     * 标记是否正在执行自动重试后的暂停
     * 用于在 STATE_PREPARED 时判断是否需要暂停
     */
    private var shouldPauseAfterAutoRetry = false
    // ================================

    /**
     * 标记当前起播是否由"播放完成后的重新播放"触发
     * 用于在 onPlayStart 中跳过历史进度恢复，避免点击"重新播放"时跳到历史位置
     */
    private var isReplayFromCompletion = false

    private fun clearBackgroundRecoveryState() {
        isReturningFromBackground = false
        backgroundRecoveryJob?.cancel()
        backgroundRecoveryJob = null
    }

    private fun rememberPlaybackPosition(position: Long) {
        if (position > 0) {
            // 接近末尾时(剩余 < 10秒)记为 0,避免后台恢复重试时跳到末尾秒播完。
            val duration = videoView.duration
            if (duration > 0 && duration - position < 10000) {
                lastKnownPlaybackPosition = 0
            } else {
                lastKnownPlaybackPosition = position
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 竖屏模式：尽早锁定竖屏，避免 Manifest 的 landscape 启动后旋转闪烁
        if (spUtil.labLongVideoPortrait) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        binding = ActivityLongVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        logD(intent.type, intent.action, intent.scheme, intent.data)
        init(savedInstanceState?: intent.getBundleExtra(KEY_BUNDLE))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        fromOpen()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isChangingConfigurations) {
            Log.d(this, "isFinishing", isFinishing)
            outState.putInt(KEY_INDEX, mCurPos)
            outState.putBoolean(KEY_HISTORY, onHistory)
            outState.putParcelable(KEY_MEDIA, itemViewModel.getMediaData())
            outState.putString(KEY_TEMP, VideoListTempStore.write(this, itemViewModel.getData()))
        }
    }

    private fun fromOpen() {
        val filepath = intent?.let { AppUtil.parsePathFromIntent(it) }
        if (filepath == null) {
            return
        }
        logD("new file", filepath)
        val list = mutableListOf<VideoData>()
        mCurPos = 0
        if (filepath.startsWith("/")) {
            FileOperator.getSortedFiles(File(filepath).parentFile!!, FileOperator.VIDEO_EXTENSIONS).let {
                it.forEach { file ->
                    if (file.path == filepath) {
                        mCurPos = list.size
                    }
                    list.add(VideoData(file))
                }
            }
        } else {
            list.add(VideoData(File(filepath)))
        }
        onHistory = true
        itemViewModel.addAll(list)
    }

    private fun init(bundle: Bundle?) {
        mCurPos = bundle?.getInt(KEY_INDEX, 0)?: 0
        itemViewModel.initData.observe(this) {
            if (it) {
                itemViewModel.getData().let { data ->
                    data.map { item -> TitleItem(title = item.name) }
                        .let { list -> epSelectView.setData(list) }
                }
                if (itemViewModel.getItemSize() > 0) {
                    startPlay(mCurPos)
                }
            }
        }
        onHistory = bundle?.getBoolean(KEY_HISTORY, false)?: false
        bundle?.getString(KEY_TEMP)?.let { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val list = VideoListTempStore.readAndDelete(path)
                withContext(Dispatchers.Main) {
                    itemViewModel.addAll(list)
                }
            }
        }
        bundle?.safeGetParcelable<MediaData>(KEY_MEDIA)?.let {
            itemViewModel.setMediaData(it)
        }
        initVideoView()
        setupSurfaceTrace()
        initVideoListener()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                userInitiatedExit = true
                finish()
                /*if (!videoView.onBackPressed()) {
                    finish()
                }*/
            }
        })
        fromOpen()
    }

    private fun initVideoView() {
        videoView = binding.videoView
        // Apply the generic render preference first. MPV is allowed to override it below because
        // MPV playback requires its own Surface-backed render view.
        applyConfiguredRenderFactory()
        DyPlayerCoreRegistry.applyCore(videoView, spUtil.dyPlayerCore, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
        videoView.setOnPlayerInitializedListener { player ->
            // BaseVideoView may recreate the backend; reattach both Exo-only settings and common
            // capability listeners for every new concrete player instance.
            (player as? CustomExoMediaPlayer)?.let { exoPlayer ->
                exoPlayer.useOkhttp(spUtil.useOkhttp)
                exoPlayer.setDebugMode(spUtil.debugMode)
            }
            (player as? MediaTrackProvider)?.setOnMediaTracksChangedListener { snapshot ->
                Log.d(this, "updateTrackPanel", snapshot.audioTracks.size, snapshot.subtitleTracks.size)
                updateTrackPanel(snapshot)
            }
            updateTrackEntryByCapabilities()
            applyPreferredLanguages()
            setupMediaInfoProvider()
        }
        // 以下只能二选一，看你的需求
        videoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
        // 音频渐入渐出配置
        videoView.setEnableAudioFade(spUtil.audioFadeEnabled)
        videoView.setAudioFadeInDuration(spUtil.audioFadeInDuration)
        videoView.setAudioFadeOutDuration(spUtil.audioFadeOutDuration)
        videoController = StandardVideoController(this)
        // 滑动控制视图
        videoController.addControlComponent(GestureView(this))
        // 竖屏手势
        videoController.setEnableInNormal(false)
        // 自动完成播放界面
        videoController.addControlComponent(CompleteView(this))
        // 剧集选择
        epSelectView = EpSelectControlView(this)
        epSelectView.setOnEpSelectListener { _, position ->
            dmTrackView.cleanData()
            startPlay(position)
        }
        videoController.addControlComponent(epSelectView)
        // 弹幕选择
        dmSelectView = DmSelectControlView(this)
        videoController.addControlComponent(dmSelectView)
        // 弹幕样式
        dmConfView = DmConfControlView(this)
        videoController.addControlComponent(dmConfView)
        // 视频样式
        videoScaleControlView = VideoScaleControlView(this)
        videoScaleControlView.setOnVideoScaleListener(videoView)
        videoScaleControlView.applyCache()
        videoController.addControlComponent(videoScaleControlView)
        // 弹幕轨道
        dmTrackView = DmTrackControlView(this)
        videoController.addControlComponent(dmTrackView)
        // 弹幕列表
        dmListView = DmListControlView(this)
        videoController.addControlComponent(dmListView)
        // 音频/字幕轨道
        mTrackPanelControlView = TrackPanelControlView(this)
        videoController.addControlComponent(mTrackPanelControlView)
        // 倍数
        speedControlView = SpeedControlView(this)
        videoController.addControlComponent(speedControlView)
        // 媒体信息
        mediaInfoControlView = MediaInfoControlView(this)
        videoController.addControlComponent(mediaInfoControlView)
        // 字幕
        subtitleControlView = SubtitleControlView(this)
        //subtitleControlView.setSubtitleAbsoluteTextSize(32f)
        videoController.addControlComponent(subtitleControlView)
        // 弹幕
        danmakuView = DanmakuControlView(this)
        videoController.addControlComponent(danmakuView)
        videoController.setTimeSyncListener { onTimeSync() }
        // 长视频控制器
        longVideoControlView = LongVideoControlView(this)
        longVideoControlView.setTitle("本地播放")
        //longVideoControlView.setFull(true)
        longVideoControlView.setOnLongVideoListener(this)
        longVideoControlView.setOnPlayNextListener(this)
        videoController.addControlComponent(longVideoControlView)
        // 弹幕默认配置集中在 controller，等长视频控制器就绪后再初始化，避免 lazy 依赖提前访问未初始化字段。
        longDanmakuController.applyInitialSettings()
        videoView.setVideoController(videoController)
        if (spUtil.labLongVideoPortrait) {
            // 竖屏模式：不进入全屏，保持竖屏播放（PLAYER_NORMAL 状态）
        } else {
            // 默认横屏全屏
            videoView.startFullScreen()
        }
        // 轨道面板回调只绑一次，后续轨道数据刷新由 updateTrackPanel 驱动。
        longTrackPanelController.bind()
        updateTrackEntryByCapabilities()
    }

    private fun applyPlaybackCoreFor(videoBean: VideoData, playUrl: String) {
        applyConfiguredRenderFactory()
        val core = if (isSmbVideo(videoBean, playUrl)) DyPlayerCore.EXO else spUtil.dyPlayerCore
        DyPlayerCoreRegistry.applyCore(videoView, core, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
    }

    private fun applyConfiguredRenderFactory() {
        if (spUtil.surfaceRender) {
            videoView.setRenderViewFactory(SurfaceRenderViewFactory.create())
        } else {
            videoView.setRenderViewFactory(TextureRenderViewFactory.create())
        }
    }

    private fun isSmbVideo(videoBean: VideoData, playUrl: String): Boolean {
        return videoBean.type == StorageType.SMB || playUrl.startsWith("smb://", ignoreCase = true)
    }

    private fun initVideoListener() {
        // 弹幕设置
        dmConfView.setOnFontChangeListener(this)
        // 弹幕相关 UI 回调已下沉到 controller，Activity 只保留播放状态编排。
        longDanmakuController.bind()
        speedControlView.setOnChangeSpeedListener { speed, position ->
            Log.d(this@LongVideoActivity, "onSpeedChange", PlayerInitializer.Player.videoSpeed)
            videoView.speed = PlayerInitializer.Player.videoSpeed
            longDanmakuController.updateSpeed()
        }
        // 视频播放状态监听
        videoView.addOnStateChangeListener(object : OnStateChangeListener {

            private var isLandscape = false

            override fun onPlayerStateChanged(playerState: Int) {
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                        if (isLandscape) {
                            isLandscape = false
                        }
                    }

                    VideoView.PLAYER_FULL_SCREEN -> {
                        isLandscape = true
                    }
                }
            }

            override fun onPlayStateChanged(playState: Int) {
                if (playState == VideoView.STATE_PREPARED) {
                    logAndCache("LongVideoActivity", "D", "STATE_PREPARED pos=$mCurPos")
                    // ===== 自动重试后维持暂停态 ===== // track-id: bg-retry-20260421
                    if (shouldPauseAfterAutoRetry) {
                        shouldPauseAfterAutoRetry = false
                        videoView.post {
                            videoView.pause()
                            Log.d(this@LongVideoActivity, "Auto retry completed, paused to maintain non-auto-play policy")
                        }
                    }
                    // ======================================
                    onPlayStart()
                }
                if (playState == VideoView.STATE_ERROR) {
                    logAndCache("LongVideoActivity", "E", "STATE_ERROR pos=$mCurPos")
                    lifecycleScope.launch(Dispatchers.IO) {
                        val errorLog = buildString {
                            appendLine("=== 播放错误埋点日志 ===")
                            appendLine("时间: ${System.currentTimeMillis()}")
                            appendLine("视频位置: $mCurPos")
                            appendLine("视频名称: ${if (itemViewModel.getItemSize() > 0) itemViewModel.getItem(mCurPos).name else "N/A"}")
                            appendLine("播放位置: ${videoView.currentPosition}")
                            appendLine("播放状态: ${videoView.currentPlayState}")
                            appendLine("播放器状态: ${videoView.currentPlayerState}")
                            appendLine("是否有MediaPlayer: ${videoView.hasPlayer()}")
                            appendLine("是否有RenderView: ${videoView.getRenderTransformView() != null}")
                            appendLine("是否从后台恢复: $isReturningFromBackground")
                            appendLine("是否已自动重试: $hasAutoRetriedOnResume")
                            appendLine("URL: ${if (itemViewModel.getItemSize() > 0) itemViewModel.getItem(mCurPos).videoUrl else "N/A"}")
                            appendLine("======================")
                        }
                        Log.d(this@LongVideoActivity, "STATE_ERROR triggered", errorLog)
                        FileOperator.writeText(
                            lifecycleScope,
                            AppFile(this@LongVideoActivity).buildCustom(
                                "logs",
                                "play_error_${System.currentTimeMillis()}.log"
                            ),
                            errorLog
                        )
                    }

                    // ===== 后台恢复错误自动重试 ===== // track-id: bg-retry-20260421
                    if (isReturningFromBackground && !hasAutoRetriedOnResume) {
                        hasAutoRetriedOnResume = true
                        isReturningFromBackground = false
                        shouldPauseAfterAutoRetry = true
                        Log.d(this, "Auto retry from background error, position=$mCurPos")
                        longVideoControlView.showTips("播放恢复中...")

                        // 投递到下一轮主线程循环，避免与当前错误回调里的状态切换冲突 // track-id: bg-retry-20260421
                        videoView.post {
                            retryCurrentVideoAfterBackgroundError()
                        }

                        // 后台恢复错误场景下，不立即重置进度，等重试结果确定后再处理 // track-id: bg-retry-20260421
                        return
                    }
                    // ======================================

                    // 非后台恢复错误，或已重试过一次，执行正常错误处理
                    longDanmakuController.saveCacheInfo(binding.videoView.currentPosition, true)
                    if (spUtil.autoNext) {
                        longVideoControlView.showTips("播放错误，自动播放下一集")
                        onNextPlay()
                    } else {
                        longVideoControlView.showTips("播放错误")
                    }
                }
                if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    longDanmakuController.saveCacheInfo(0, true)
                    // 同步清零 PlayInfo.playSeek，避免点击"重新播放"时 onPlayStart 又 seekTo 到历史位置
                    // 注意：必须同步执行，不能依赖 updateInfo 的异步写入（有 6 秒节流 + 竞态）
                    if (itemViewModel.getItemSize() > mCurPos) {
                        PlayHelper.resetPlaySeek(this@LongVideoActivity, itemViewModel.getItem(mCurPos))
                    }
                    // 后台播放模式下播完:先释放 Service 持有的旧 player(避免双 player),
                    // 再切下一集。新视频起播后由 STATE_PLAYING 的 pendingBgSwitch 重新 detach。
                    if (isBoundToPlaybackService && playbackService?.isHoldingPlayer == true) {
                        playbackService?.returnPlayer()?.release()
                        try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
                        isBoundToPlaybackService = false
                        playbackService?.setSkipCallback(null)
                        playbackService = null
                        videoView.setEnableAudioFocus(true)
                        pendingBgSwitch = true
                    }
                    // 标记进入完成态，下一次 STATE_PREPARED 触发的 onPlayStart 视为"重新播放"，跳过历史进度恢复
                    isReplayFromCompletion = true
                    if (spUtil.autoNext) {
                        onNextPlay()
                    }
                }
                if (playState == VideoView.STATE_PLAYING) {
                    clearBackgroundRecoveryState()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                    // 后台通知栏切集:新视频起播后重新 detach 给 Service 并更新通知标题。
                    if (pendingBgSwitch) {
                        pendingBgSwitch = false
                        startPlaybackService()
                    }
                }
            }
        })
    }

    override fun onPreviousPlay() {
        if (mCurPos - 1 >= 0) {
            startPlay(mCurPos - 1)
        } else if (spUtil.loopList && itemViewModel.getItemSize() > 1) {
            startPlay(itemViewModel.getItemSize() - 1)
            longVideoControlView.showTips("循环到最后一集")
        } else {
            danmakuView.release()
            longVideoControlView.showTips("没有更多")
        }
    }

    override fun onNextPlay() {
        if (mCurPos + 1 < itemViewModel.getItemSize()) {
            startPlay(mCurPos + 1)
        } else if (spUtil.loopList && itemViewModel.getItemSize() > 1) {
            startPlay(0)
            longVideoControlView.showTips("循环到第一集")
        } else {
            danmakuView.release()
            longVideoControlView.showTips("没有更多")
        }
    }

    override fun onTimeSync() {
        longDanmakuController.syncTime()
    }

    private fun startPlay(position: Int) {
        // 切换集数时重置"重新播放"标记，避免误判为完成态重播而跳过进度恢复
        isReplayFromCompletion = false
        mediaPlaybackRecorder.cancelLastPlayedUpdate()
        // 切换播放项前先清掉上一集弹幕列表/曲线，避免旧数据短暂残留。
        longDanmakuController.clearPanels()
        val videoBean = itemViewModel.getItem(position)
        Log.d(this, "startPlay", position, videoBean.name)
        logAndCache("LongVideoActivity", "D", "startPlay position=$position name=${videoBean.name} url=${videoBean.videoUrl}")
        lifecycleScope.launch(Dispatchers.IO) {
            videoBean.playUrl().let { playUrl ->
                lifecycleScope.launch(Dispatchers.Main) {
                    // 新视频起播前统一重置 controller 持有的弹幕 UI 状态。
                    longDanmakuController.resetForNewPlayback()
                    subtitleControlView.clearText()
                    videoScaleControlView.resetValue()
                    PlayerInitializer.resetSpeed()
                    videoView.release()
                    applyPlaybackCoreFor(videoBean, playUrl)
                    //mController.setTitle(videoBean.name)
                    longVideoControlView.setTitle(videoBean.name)
                    videoView.setUrl(
                        playUrl,
                        if (videoBean.is302(playUrl).not()) videoBean.headers else mutableMapOf()
                    )
                    videoView.start()
                    videoView.post { videoScaleControlView.applyCache() }
                    mCurPos = position
                    epSelectView.selected(position)
                    saveMediaInfo(videoBean.videoUrl)
                }
            }
        }
    }

    private fun retryCurrentVideoAfterBackgroundError() {
        if (itemViewModel.getItemSize() <= mCurPos) {
            return
        }
        val position = mCurPos
        val retryPosition = lastKnownPlaybackPosition
        val videoBean = itemViewModel.getItem(position)
        lifecycleScope.launch(Dispatchers.IO) {
            val playUrl = videoBean.playUrl()
            withContext(Dispatchers.Main) {
                if (position != mCurPos || itemViewModel.getItemSize() <= position) {
                    return@withContext
                }
                videoView.release()
                applyPlaybackCoreFor(videoBean, playUrl)
                videoView.setUrl(
                    playUrl,
                    if (videoBean.is302(playUrl).not()) videoBean.headers else mutableMapOf()
                )
                if (retryPosition > 0) {
                    videoView.skipPositionWhenPlay(retryPosition.toInt())
                }
                videoView.start()
                videoView.post { videoScaleControlView.applyCache() }
            }
        }
    }

    private fun saveMediaInfo(url: String) {
        mediaPlaybackRecorder.saveLastPlayedUrl(itemViewModel.getMediaData(), url)
    }

    private fun scheduleMediaLastPlayedUpdate(position: Int) {
        mediaPlaybackRecorder.scheduleLastPlayedAtUpdate(
            scope = lifecycleScope,
            mediaData = itemViewModel.getMediaData(),
            position = position,
            currentPosition = { mCurPos },
            currentPlayState = { videoView.currentPlayState }
        )
    }

    private fun onPlayStart() {
        val position = mCurPos
        // 读取"重新播放"标记并立即重置（loadInfo 回调是异步的，需在同步阶段捕获）
        val skipSeekDueToReplay = isReplayFromCompletion
        isReplayFromCompletion = false
        /*videoView.setOnTimedTextListener(null)
        videoView.trackInfo.let {
            if (it.second.isNotEmpty()) {
                videoView.showSubTitle(1)
                videoView.setOnTimedTextListener { _, text ->
                    text?.let {
                        Log.d(this@LongVideoActivity, "timedText", text)
                    }
                }
            }
        }*/
        if (itemViewModel.getItemSize() <= 0) {
            return
        }
        val videoData = itemViewModel.getItem(position)
        attachSubtitle(videoData, position)
        PlayHelper.loadInfo(this@LongVideoActivity, lifecycleScope, videoData) { info ->
            var unLoadDm = true
            if (info != null) {
                // 超过片长9/10，重播
                // 若由"播放完成 → 重新播放"触发，跳过历史进度恢复（resetPlaySeek 已清零，此处为双保险）
                if (!skipSeekDueToReplay && info.playSeek > 0L){
                    videoView.seekTo(info.playSeek)
                    longVideoControlView.showTips("跳转上传播放的位置 ${PlayerUtils.stringForTime(info.playSeek.toInt())}")
                } else if (skipSeekDueToReplay && info.playSeek > 0L) {
                    // 完成态下不跳转，仅记录日志便于排查
                    Log.d(this@LongVideoActivity, "Skip seekTo due to replay from completion, stored playSeek=${info.playSeek}")
                }
                info.dmTrack.let { list ->
                    // 弹幕轨道恢复细节由 controller 处理，Activity 只决定何时触发恢复。
                    longDanmakuController.addTracks(list)
                    if (longDanmakuController.hasTracks()) {
                        unLoadDm = false
                        longDanmakuController.restoreSavedTrack(info)
                    }
                }
            }
            if (unLoadDm) {
                // 没有历史轨道时，按当前存储类型兜底装载默认弹幕文件。
                if (videoData.type == StorageType.LOCAL_STORAGE) {
                    longDanmakuController.addDmFile(File(videoData.dmLink))
                }
                if (videoData.type == StorageType.WEBDAV) {
                    PlayHelper.loadDavDm(
                        this@LongVideoActivity,
                        lifecycleScope,
                        videoData
                    ) { file ->
                        longDanmakuController.addDmFile(file)
                    }
                }
            }
        }
        if (itemViewModel.hasMediaData()) {
            itemViewModel.getMediaData()?.let {
                // 跳过片头
                Log.d(this@LongVideoActivity, "op", it.opOffset)
                if (it.opOffset > 0 && videoView.currentPosition < it.opOffset * 1000) {
                    videoView.seekTo(it.opOffset * 1000L)
                    longVideoControlView.showTips("跳过片头")
                }
            }
        }
        updateTrackPanel(getMediaTrackProvider()?.getMediaTracks() ?: MediaTrackSnapshot())
        setupMediaInfoProvider()
        binding.root.post {
            if (onHistory) {
                return@post
            }
            // 保存历史
            PlayHelper.saveHistory(spUtil, videoData)
        }
        binding.root.postDelayed({
            if (position != mCurPos) {
                return@postDelayed
            }
            // 保存预览图
            PlayHelper.saveThumb(this, lifecycleScope, videoView, videoData)
        }, 1000)
    }

    private fun attachSubtitle(videoData: VideoData, position: Int) {
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        lifecycleScope.launch(Dispatchers.IO) {
            val subtitleFile = PlayHelper.resolveSubtitleFile(this@LongVideoActivity, videoData)
            withContext(Dispatchers.Main) {
                if (position != mCurPos || itemViewModel.getItemSize() <= position) {
                    return@withContext
                }
                if (itemViewModel.getItem(position).videoUrl != videoData.videoUrl) {
                    return@withContext
                }
                if (subtitleFile != null && subtitleFile.exists() && subtitleFile.isFile) {
                    addExternalSubtitle(subtitleFile.toUri(), null, subtitleFile.name)
                }
            }
        }
    }

    private fun updateTrackPanel(snapshot: MediaTrackSnapshot) {
        if (!::mTrackPanelControlView.isInitialized) {
            return
        }
        // 轨道列表映射和字幕 cue 开关由 controller 统一维护。
        longTrackPanelController.updateTracks(snapshot)
    }

    private fun updateTrackEntryByCapabilities() {
        if (!::longVideoControlView.isInitialized) {
            return
        }
        longTrackPanelController.updateEntryByCapabilities()
    }

    private fun applyPreferredLanguages() {
        if (!::mTrackPanelControlView.isInitialized) {
            return
        }
        longTrackPanelController.applyPreferredLanguages()
    }

    private fun addExternalSubtitle(uri: Uri, mimeType: String?, title: String?): Boolean {
        return ExternalSubtitleLoader.addSubtitle(
            externalTrackController = getExternalTrackController(),
            subtitleCueProvider = getSubtitleCueProvider(),
            subtitleControlView = subtitleControlView,
            uri = uri,
            mimeType = mimeType,
            title = title
        )
    }

    private fun attachSubtitleCueListener() {
        // 具体 cue 绑定逻辑抽到共享 helper，避免长短视频页各自维护一套。
        ExternalSubtitleLoader.attachCueListener(getSubtitleCueProvider(), subtitleControlView)
    }

    private fun clearSubtitleCueListener() {
        ExternalSubtitleLoader.clearCueListener(getSubtitleCueProvider())
    }

    override fun onStart() {
        super.onStart()
        android.util.Log.i("BgPlayDebug", "onStart: isBound=" + isBoundToPlaybackService + " binder=" + (playbackService != null) + " holding=" + (playbackService?.isHoldingPlayer ?: false))
        // M2 fix: 用户已返回前台，清除"待进入后台"标记。
        pendingBackgroundEntry = false
        // 如果 binder 已连接且 Service 持有 player，直接取回
        if (playbackService?.isHoldingPlayer == true) {
            reclaimPlayerFromService()
            return
        }
        // 注:不再用 500ms 延迟 tryReclaim。它在按 Home 进后台时会与 Service 的
        // takePlayer 竞态,错误地把刚交给 Service 的 player 取回,导致后台播放失效。
        // 通知点击回前台时,contentIntent 的 SINGLE_TOP 会走 onNewIntent,
        // binder 仍连接则由上面的 isHoldingPlayer 分支处理。
    }

    private fun reclaimPlayerFromService() {
        val player = playbackService?.returnPlayer()
        if (player != null) {
            // 如果 player 已播完(ENDED/COMPLETED),不 attach 它(画面会是黑屏),
            // 直接释放并重新 startPlay 当前位置,让画面恢复。
            val state = videoView.currentPlayState
            if (state == VideoView.STATE_PLAYBACK_COMPLETED || state == VideoView.STATE_ERROR) {
                player.release()
                Log.d(this, "reclaim: player completed/error, restarting pos=$mCurPos")
                videoView.setEnableAudioFocus(true)
                try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
                isBoundToPlaybackService = false
                playbackService?.setSkipCallback(null)
                playbackService = null
                startPlay(mCurPos)
                return
            }
            videoView.attachPlayer(player)
            videoView.setEnableAudioFocus(true)
            Log.d(this, "resumed from background, player reattached")
        }
        try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
        isBoundToPlaybackService = false
        playbackService?.setSkipCallback(null)
        playbackService = null
    }

    /**
     * 通知点击恢复场景：Service 可能还在运行(持有 player)，但 Activity 的 binder 已断开。
     * 重新 bind 取回 player。
     */
    private fun tryReclaimFromSurvivingService() {
        val intent = Intent(this, PlaybackService::class.java)
        val reclaimConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? PlaybackBinder
                if (binder?.isHoldingPlayer == true) {
                    val player = binder.returnPlayer()
                    if (player != null) {
                        videoView.attachPlayer(player)
                        videoView.setEnableAudioFocus(true)
                        android.util.Log.i("BgPlayDebug", "reclaimed player from surviving service")
                    }
                    binder.stopForegroundAndNotification()
                } else {
                    // Service 没持有 player,停止它
                    binder?.stopForegroundAndNotification()
                }
                try { unbindService(this) } catch (_: Exception) {}
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            bindService(intent, reclaimConnection, 0)
        } catch (e: Exception) {
            android.util.Log.w("BgPlayDebug", "tryReclaimFromSurvivingService failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        // 标记后台返回后的短暂恢复窗口，仅在该窗口内允许 STATE_ERROR 自动重试 // track-id: bg-retry-20260421
        clearBackgroundRecoveryState()
        if (hasPausedForBackgroundRecovery) {
            hasPausedForBackgroundRecovery = false
            isReturningFromBackground = true
            backgroundRecoveryJob = lifecycleScope.launch {
                delay(BACKGROUND_RECOVERY_WINDOW_MS)
                clearBackgroundRecoveryState()
            }
        }
        // 移除可能导致 STATE_ERROR 的同步指令。使用 post 确保在 Surface 准备就绪后的下一个主线程循环执行。
        // 这样可以安全地触发一次 seek 来恢复画面，而不会触发自动播放。
        videoView.post {
            if (videoView.currentPlayState == VideoView.STATE_PAUSED && itemViewModel.getItemSize() > 0) {
                val currentPos = videoView.currentPosition
                if (currentPos > 0) {
                    videoView.seekTo(currentPos)
                    Log.d(this, "onResume - safe frame restoration via seekTo", currentPos)
                }
            }
            // 从 PiP 返回全屏且之前在播放，恢复播放
            if (wasPlayingBeforePipExit) {
                wasPlayingBeforePipExit = false
                videoView.start()
            }
        }
        // 注册超分开关广播接收器（让 adb 和设置页都能切换 FSR）
        registerSuperResolutionReceiver()
    }

    override fun onPause() {
        super.onPause()
        android.util.Log.i("BgPlayDebug", "onPause: pip=$isInPictureInPictureMode state=${videoView.currentPlayState} hasPlayer=${videoView.hasPlayer()} changingCfg=$isChangingConfigurations userExit=$userInitiatedExit")
        // PiP 模式下不暂停视频和弹幕，保持小窗继续播放
        if (isInPictureInPictureMode) {
            android.util.Log.i("BgPlayDebug", "onPause: in PiP, early return")
            return
        }
        rememberPlaybackPosition(videoView.currentPosition)
        // 后台恢复相关状态：无论接下来是否暂停，重置都安全。
        clearBackgroundRecoveryState()
        hasPausedForBackgroundRecovery = true
        // 重置重试标记，下次从后台回来允许重试一次 // track-id: bg-retry-20260421
        hasAutoRetriedOnResume = false
        // 重置自动重试后暂停标记 // track-id: bg-retry-20260421
        shouldPauseAfterAutoRetry = false
        // M1 fix: 若即将进入后台播放（onStop 会 detach player 交给 Service），不要在这里暂停——
        // 否则 onStop 里 shouldEnterBackgroundPlay() 会因状态已被改为 STATE_PAUSED 而被跳过，
        // 后台播放功能在非 PiP 路径下会静默失效。
        // 弹幕也要保持播放（音频继续），由 onStart 取回 player 时再统一恢复。
        // 用 currentPlayState 判断(与 shouldEnterBackgroundPlay 一致)：
        // player 从后台 Service 取回后内部状态可能与 currentPlayState 不同步。
        if (!isChangingConfigurations && !userInitiatedExit && shouldEnterBackgroundPlay()) {
            Log.d(this, "onPause skip pause: pending background play")
            return
        }
        danmakuView.pause()
        videoView.pause()
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.i("BgPlayDebug", "onStop: userExit=$userInitiatedExit changingCfg=$isChangingConfigurations pip=$isInPictureInPictureMode state=${videoView.currentPlayState} hasPlayer=${videoView.hasPlayer()}")
        // 用户主动退出 → 不进后台
        if (userInitiatedExit) {
            userInitiatedExit = false
            android.util.Log.i("BgPlayDebug", "onStop: user exit, skip background")
            return
        }
        // 旋屏等配置变化 → 不进后台
        if (isChangingConfigurations) return
        // 正在 PiP → 不进后台（PiP 期间 Activity 仍可见）
        if (isInPictureInPictureMode) return
        // 播放中 + 未暂停 → 进入后台播放模式
        if (shouldEnterBackgroundPlay()) {
            startPlaybackService()
        }
    }

    /** 后台播放触发条件：开关开启 + 处于活跃播放态(PLAYING/BUFFERED) + 有 player。 */
    private fun shouldEnterBackgroundPlay(): Boolean {
        // 后台播放开关关闭 → 不走后台播放
        if (!spUtil.longVideoBackgroundPlay) {
            android.util.Log.i("BgPlayDebug", "shouldEnterBackgroundPlay: switch OFF, skip")
            return false
        }
        // 用 currentPlayState 判断而非 isPlaying()：player 从后台 Service 取回后，
        // 内部状态可能与 currentPlayState 不同步(isPlaying 返回 false 但 state 仍是 PLAYING)，
        // 导致后台播放触发不了。currentPlayState 反映的是用户预期状态。
        val state = videoView.currentPlayState
        return videoView.hasPlayer() && (state == VideoView.STATE_PLAYING || state == VideoView.STATE_BUFFERED)
    }

    /** 启动并绑定 PlaybackService。 */
    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        intent.putExtra("source_activity", LongVideoActivity::class.java.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, playbackServiceConnection, Context.BIND_AUTO_CREATE)
        isBoundToPlaybackService = true
        // M2 fix: 标记正在等待 onServiceConnected → tryEnterBackgroundPlay。
        // onStart 会清除此标记；如果 Service 回调这时才到，说明用户已返回前台，应放弃 detach。
        pendingBackgroundEntry = true
        // 禁用 view 的音频焦点（Service 接管）
        videoView.setEnableAudioFocus(false)
        // 注册后台切集回调:通知栏上一个/下一个时 Service 调用,Activity 在后台切集,
        // 新视频起播后(STATE_PLAYING)重新 detach 给 Service(不拉起 Activity)。
        pendingBgSwitch = false
        Log.d(this, "starting background playback")
    }

    /** 在 ServiceConnection.onServiceConnected 中调用：把 player 交给 Service。 */
    private fun tryEnterBackgroundPlay() {
        val binder = playbackService ?: return
        // M2 fix: 若用户在 Service 连上之前已通过 onStart 返回前台，
        // pendingBackgroundEntry 会被清除——此时不能 detach，否则会把前台播放的 player 错误交给 Service，
        // 导致画面冻结+误显示后台通知。直接解绑并退出，让前台继续播放。
        if (!pendingBackgroundEntry) {
            Log.d(this, "abort background entry: user already returned to foreground")
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
            playbackService = null
            // Service 未持有 player，调用 stopForegroundAndNotification 让其自停
            binder.stopForegroundAndNotification()
            videoView.setEnableAudioFocus(true)
            return
        }
        pendingBackgroundEntry = false
        val player = videoView.detachPlayer() ?: return
        val metadata = PlaybackMetadata(
            title = currentTitle,
            subtitle = currentSourceName,
            duration = videoView.duration,
            currentPosition = videoView.currentPosition
        )
        // Task 8: 先用 null 封面 takePlayer(立即显示标题),再异步加载封面后 updateMetadata。
        binder.setSourceActivity(LongVideoActivity::class.java)
        binder.takePlayer(player, metadata.copy(coverBitmap = null))
        // 注册后台切集回调:通知栏上一个/下一个时 Service 调用。
        binder.setSkipCallback { direction ->
            // 取回 Service 持有的 player 并释放(避免双音频),再切集。
            // 新视频起播后(STATE_PLAYING)由 pendingBgSwitch 重新 detach 给 Service。
            playbackService?.returnPlayer()?.release()
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
            playbackService = null
            videoView.setEnableAudioFocus(true)
            pendingBgSwitch = true
            if (direction > 0) onNextPlay() else onPreviousPlay()
        }
        loadCoverBitmap(currentCoverUrl) { bitmap ->
            if (bitmap != null) {
                playbackService?.updateMetadata(metadata.copy(coverBitmap = bitmap))
            }
        }
    }

    /**
     * 当前视频封面 URL(Task 8):优先用本地 thumb(已通过 saveThumb 缓存),
     * 回退到视频源 preview 字段;都没有则返回 null(通知仍可正常显示)。
     */
    private val currentCoverUrl: String?
        get() {
            if (itemViewModel.getItemSize() <= mCurPos) return null
            val videoData = itemViewModel.getItem(mCurPos)
            val thumbFile = AppFile(this)
                .buildCache(".thumb/${videoData.videoUrl.md5()}.${AppUtil.THUMB_TYPE}")
            if (thumbFile.exists() && thumbFile.isFile && thumbFile.length() > 0L) {
                return thumbFile.path
            }
            return videoData.preview.takeIf { it.isNotBlank() }
        }

    /**
     * 当前视频文件路径(用于 MediaMetadataRetriever 提取封面帧)。
     */
    private val currentVideoFilePath: String?
        get() {
            if (itemViewModel.getItemSize() <= mCurPos) return null
            val videoData = itemViewModel.getItem(mCurPos)
            return videoData.videoUrl.takeIf { it.startsWith("/") }
        }

    /**
     * 用 Glide 异步加载封面 Bitmap,加载完成后回调到主线程。
     * URL 为空时,对本地视频用 MediaMetadataRetriever 提取一帧作为封面。
     * submit().get() 阻塞,必须在后台线程执行。
     */
    private fun loadCoverBitmap(url: String?, callback: (android.graphics.Bitmap?) -> Unit) {
        Thread {
            try {
                var bitmap: android.graphics.Bitmap? = null
                if (!url.isNullOrBlank()) {
                    bitmap = com.bumptech.glide.Glide.with(this)
                        .asBitmap()
                        .load(url)
                        .submit(256, 256)
                        .get()
                }
                // URL 没有或加载失败,尝试从本地视频文件提取帧
                if (bitmap == null) {
                    val path = currentVideoFilePath
                    if (path != null) {
                        bitmap = android.media.MediaMetadataRetriever().use { retriever ->
                            try {
                                retriever.setDataSource(path)
                                retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            } catch (_: Exception) { null }
                        }
                    }
                }
                runOnUiThread { callback(bitmap) }
            } catch (e: Exception) {
                Log.d(this, "loadCoverBitmap failed: ${e.message}")
                runOnUiThread { callback(null) }
            }
        }.start()
    }

    /** 当前视频标题（用于后台通知栏）。 */
    private val currentTitle: String
        get() {
            return if (itemViewModel.getItemSize() > mCurPos) {
                itemViewModel.getItem(mCurPos).name.ifBlank { "正在播放" }
            } else {
                "正在播放"
            }
        }

    /** 当前视频来源副标题（用于后台通知栏）。 */
    private val currentSourceName: String
        get() {
            return itemViewModel.getMediaData()?.title ?: ""
        }

    override fun onDestroy() {
        super.onDestroy()
        // 注销 PiP 广播接收器，避免内存泄漏
        unregisterPipActionReceiver()
        // 注销超分开关广播接收器
        unregisterSuperResolutionReceiver()
        mediaPlaybackRecorder.release()
        clearBackgroundRecoveryState()
        clearSurfaceTrace()
        PlayerInitializer.resetSpeed()
        // 如果 Service 还持有 player（Activity 异常销毁），取回并释放
        if (isBoundToPlaybackService) {
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
        }
        playbackService?.returnPlayer()?.release()
        playbackService = null
        videoView.release()
        danmakuView.release()
    }

    override fun onDmShow(isShow: Boolean) {
        danmakuView.toggleVis(isShow)
    }

    override fun onSelectDm() {
        videoController.hide()
        dmSelectView.switchVib()
    }

    override fun onConfDm() {
        videoController.hide()
        dmConfView.switchVib()
    }

    override fun onConfVideo() {
        videoController.hide()
        videoScaleControlView.switchVib()
    }

    override fun onScreenshot() {
        PlayHelper.doScreenShot(
            lifecycleScope, videoView,
            itemViewModel.getItem(mCurPos),
            videoView.currentPosition
        ) { save ->
            lifecycleScope.launch(Dispatchers.Main) {
                longVideoControlView.showTips(if (save) "截图成功" else "截图失败")
            }
        }
    }

    override fun onShowEpisodeSelect() {
        videoController.hide()
        epSelectView.switchVib()
    }

    override fun onShowDmTrack() {
        videoController.hide()
        longDanmakuController.switchTrackPanel()
    }

    override fun onShowDmList() {
        videoController.hide()
        longDanmakuController.switchListPanel(videoView.currentPosition)
    }

    override fun onShowTrackPanel() {
        videoController.hide()
        mTrackPanelControlView.switchVib()
    }

    override fun onVideoProgress(duration: Int, position: Int) {
        rememberPlaybackPosition(position.toLong())
        if (position > 3000) {
            longDanmakuController.saveCacheInfo(position.toLong())
        }
        if (itemViewModel.hasMediaData()) {
            itemViewModel.getMediaData()?.let {
                // 跳过片尾
                if (it.edOffset > 0 && (duration - position) / 1000 == it.edOffset) {
                    logD("duration", duration, "position", position, "ed", it.edOffset)
                    longDanmakuController.saveCacheInfo(0)
                    longVideoControlView.showTips("跳过片尾")
                    onNextPlay()
                }
            }
        }
    }

    override fun onSpeedChange() {
        videoController.hide()
        speedControlView.switchVib()
    }

    override fun onEnterPiP() {
        // 手动点击 PiP 按钮：检查系统支持后进入画中画（不检查自动进入开关，手动点击表示用户明确意图）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            enterPiPMode()
        }
    }

    override fun onShowMediaInfo() {
        videoController.hide()
        mediaInfoControlView.switchVib()
    }

    private fun setupMediaInfoProvider() {
        val owner = getMediaInfoProviderOwner()
        val capabilities = getPlayerCapabilities()
        // MediaInfoControlView stays backend-agnostic; unsupported cores simply clear the provider.
        mediaInfoControlView.setMediaInfoProvider(
            if (capabilities.canProvideMediaInfo) owner?.createMediaInfoProvider() else null
        )
        mediaInfoControlView.setProviderName(
            if (capabilities.canProvideMediaInfo) owner?.getMediaInfoProviderName().orEmpty() else ""
        )
        val vd = if (itemViewModel.getItemSize() > mCurPos) itemViewModel.getItem(mCurPos) else null
        if (vd != null) {
            val position = mCurPos
            // playUrl() 可能执行同步网络请求，需在IO线程调用
            lifecycleScope.launch(Dispatchers.IO) {
                val fullPath = vd.playUrl()
                lifecycleScope.launch(Dispatchers.Main) {
                    if (position == mCurPos) {
                        val fileEntity = me.lingci.lib.base.storage.entity.FileEntity(
                            name = vd.name.ifBlank { vd.videoUrl.substringAfterLast("/") },
                            path = vd.videoUrl,
                            fullPath = fullPath,
                            type = vd.type,
                            size = 0L
                        )
                        mediaInfoControlView.setFileEntity(fileEntity, vd.type)
                    }
                }
            }
        } else {
            mediaInfoControlView.setFileEntity(null, null)
        }
    }

    override fun onFontChange(fontPath: String) {
        longDanmakuController.onFontChange(fontPath)
    }

    // ===== PiP 画中画模式实现 =====

    /**
     * 用户离开 Activity 时触发（按 Home 键等）。
     * 若视频正在播放且系统支持 PiP，则进入小窗模式。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // 仅在 API 26+ 且视频处于播放状态时进入 PiP
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && shouldEnterPip()) {
            enterPiPMode()
        }
    }

    /**
     * 判断是否应该进入 PiP 模式。
     * 需同时满足：用户开启 PiP 开关、系统支持 PiP、视频处于播放中状态。
     */
    private fun shouldEnterPip(): Boolean {
        // 用户未开启 PiP 开关时不进入
        if (!spUtil.longVideoPip) return false
        // 系统不支持 PiP 时不进入（API 26+ 才有该特性检测）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        // 视频处于播放中才进入 PiP。
        // 用 isPlaying() 而非 currentPlayState == STATE_PLAYING，
        // 因为视频可能处于 STATE_BUFFERED(7) 等活跃态（isPlaying 对其返回 true）。
        return videoView.isPlaying && videoView.hasPlayer()
    }

    /**
     * 进入 PiP 画中画模式。
     * 根据视频实际宽高比构建 PiP 参数，并添加操作按钮。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun enterPiPMode() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(calculateVideoAspectRatio())
            .setActions(buildPipActions())
            .build()
        enterPictureInPictureMode(params)
    }

    /**
     * 计算视频宽高比，用于 PiP 窗口尺寸。
     * 若无法获取视频尺寸则默认 16:9。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun calculateVideoAspectRatio(): Rational {
        val videoSize = videoView.getVideoSize()
        val width = videoSize[0]
        val height = videoSize[1]
        // 视频尺寸有效且比例合理时使用实际比例，否则默认 16:9
        return if (width > 0 && height > 0) {
            Rational(width, height)
        } else {
            Rational(16, 9)
        }
    }

    /**
     * PiP 模式切换回调。
     * 进入 PiP 时隐藏控制器并应用弹幕小窗配置；退出时恢复。
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            enterPipUiState()
        } else {
            exitPipUiState()
            // PiP 退出后:
            // - 后台播放开关开: 直接进入后台播放(PiP 退出后 Activity 回到前台,不会触发 onStop)
            // - 后台播放开关关: 主动 pause(用户关闭 PiP 意味着不想继续播放)
            if (spUtil.longVideoBackgroundPlay && shouldEnterBackgroundPlay()) {
                android.util.Log.i("BgPlayDebug", "PiP exit: bg switch on, start background play")
                startPlaybackService()
            } else if (!spUtil.longVideoBackgroundPlay) {
                if (::videoView.isInitialized && videoView.hasPlayer()) {
                    videoView.pause()
                    android.util.Log.i("BgPlayDebug", "PiP exit: bg switch off, pause")
                }
            }
        }
    }

    /**
     * 进入 PiP 模式时的 UI 状态调整。
     * 隐藏控制器控制栏，关闭浮层面板，弹幕缩小字体并限制最多3行。
     */
    private fun enterPipUiState() {
        // 隐藏控制器控制栏（弹幕组件 onVisibilityChanged 为空实现，不受影响）
        videoController.hide()
        // 关闭所有浮层面板（选集、弹幕配置、倍速等）
        closeAllPanelsForPip()
        // 弹幕：缩小字体 + 限制最多3行
        applyPipDanmakuConfig()
        // 注册 PiP 操作按钮广播接收器
        registerPipActionReceiver()
    }

    /**
     * 退出 PiP 模式时的 UI 状态恢复。
     * 恢复弹幕原配置，重新显示控制器。
     */
    private fun exitPipUiState() {
        // 恢复弹幕原配置
        restoreDanmakuConfig()
        // 注销 PiP 操作按钮广播接收器
        unregisterPipActionReceiver()
        // 恢复控制器显示
        videoController.show()
        // 保存退出 PiP 前的播放状态，用于 onResume 判断是否恢复播放
        wasPlayingBeforePipExit = videoView.isPlaying
        // 暂停视频和弹幕（关闭 PiP 场景需要；返回全屏场景由 onResume 恢复）
        // （onStop 不会再次触发，因为 Activity 在进入 PiP 时已处于 stopped 状态）
        rememberPlaybackPosition(videoView.currentPosition)
        danmakuView.pause()
        videoView.pause()
    }

    /**
     * 关闭所有不适合在 PiP 中显示的浮层面板。
     * 直接设置 visibility = GONE，避免 toggle 逻辑导致状态不确定。
     */
    private fun closeAllPanelsForPip() {
        epSelectView.visibility = android.view.View.GONE
        dmSelectView.visibility = android.view.View.GONE
        dmConfView.visibility = android.view.View.GONE
        videoScaleControlView.visibility = android.view.View.GONE
        dmTrackView.visibility = android.view.View.GONE
        dmListView.visibility = android.view.View.GONE
        mTrackPanelControlView.visibility = android.view.View.GONE
        speedControlView.visibility = android.view.View.GONE
        mediaInfoControlView.visibility = android.view.View.GONE
    }

    /**
     * 应用 PiP 模式弹幕配置：缩小字体 + 限制最多3行。
     * 保存原配置以便退出 PiP 时恢复。
     */
    private fun applyPipDanmakuConfig() {
        // 保存原配置
        pipSavedDanmakuSize = PlayerInitializer.Danmu.size
        pipSavedMaxLine = PlayerInitializer.Danmu.maxLine
        pipSavedMaxTopLine = PlayerInitializer.Danmu.maxTopLine
        pipSavedMaxBottomLine = PlayerInitializer.Danmu.maxBottomLine
        // 缩小字体至原值的50%，最小不低于10
        PlayerInitializer.Danmu.size = (pipSavedDanmakuSize * 0.5f).toInt().coerceAtLeast(10)
        danmakuView.updateDanmuSize()
        // 限制滚动/顶部/底部弹幕最多3行
        PlayerInitializer.Danmu.maxLine = 3
        PlayerInitializer.Danmu.maxTopLine = 3
        PlayerInitializer.Danmu.maxBottomLine = 3
        danmakuView.updateMaxLine()
    }

    /**
     * 恢复 PiP 模式前的弹幕配置。
     */
    private fun restoreDanmakuConfig() {
        PlayerInitializer.Danmu.size = pipSavedDanmakuSize
        PlayerInitializer.Danmu.maxLine = pipSavedMaxLine
        PlayerInitializer.Danmu.maxTopLine = pipSavedMaxTopLine
        PlayerInitializer.Danmu.maxBottomLine = pipSavedMaxBottomLine
        danmakuView.updateDanmuSize()
        danmakuView.updateMaxLine()
    }

    /**
     * 构建 PiP 窗口操作按钮列表（最多3个）。
     * - 上一集
     * - 播放/暂停（图标随播放状态动态切换）
     * - 下一集
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipActions(): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()
        // 上一集
        actions.add(
            createRemoteAction(
                me.lingci.lib.player.ui.R.drawable.ic_skip_previous,
                "上一集",
                PiPActionReceiver.ACTION_PREV,
                PiPActionReceiver.REQUEST_PREV
            )
        )
        // 播放/暂停（根据当前播放状态切换图标）
        val playPauseIcon = if (videoView.isPlaying) {
            me.lingci.lib.player.ui.R.drawable.ic_pause_24
        } else {
            me.lingci.lib.player.ui.R.drawable.ic_play_arrow_24
        }
        actions.add(
            createRemoteAction(
                playPauseIcon,
                "播放/暂停",
                PiPActionReceiver.ACTION_PLAY_PAUSE,
                PiPActionReceiver.REQUEST_PLAY_PAUSE
            )
        )
        // 下一集
        actions.add(
            createRemoteAction(
                me.lingci.lib.player.ui.R.drawable.ic_skip_next,
                "下一集",
                PiPActionReceiver.ACTION_NEXT,
                PiPActionReceiver.REQUEST_NEXT
            )
        )
        return actions
    }

    /**
     * 创建单个 RemoteAction。
     *
     * @param iconRes 图标资源ID
     * @param title 操作标题
     * @param action 广播动作
     * @param requestCode 请求码
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteAction(
        iconRes: Int,
        title: String,
        action: String,
        requestCode: Int
    ): RemoteAction {
        val intent = Intent(action).setPackage(packageName)
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this, requestCode, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(
            Icon.createWithResource(this, iconRes),
            title,
            title,
            pendingIntent
        )
    }

    /**
     * 切换播放/暂停状态。
     * PiP 窗口中点击播放/暂停按钮时调用。
     */
    private fun togglePlayPause() {
        if (videoView.isPlaying) {
            videoView.pause()
        } else {
            videoView.start()
        }
        // 播放状态变化后刷新 PiP 按钮图标
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            updatePipActions()
        }
    }

    /**
     * 刷新 PiP 窗口的操作按钮（播放/暂停图标随状态切换）。
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun updatePipActions() {
        if (isInPictureInPictureMode) {
            val params = PictureInPictureParams.Builder()
                .setActions(buildPipActions())
                .build()
            setPictureInPictureParams(params)
        }
    }

    /**
     * 注册 PiP 操作按钮广播接收器。
     * API 34+ 需显式指定 RECEIVER_NOT_EXPORTED 标志（广播仅限应用内）。
     */
    private fun registerPipActionReceiver() {
        if (isPipReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(PiPActionReceiver.ACTION_PREV)
            addAction(PiPActionReceiver.ACTION_PLAY_PAUSE)
            addAction(PiPActionReceiver.ACTION_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 必须指定导出标志
            registerReceiver(pipActionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipActionReceiver, filter)
        }
        isPipReceiverRegistered = true
    }

    /**
     * 注销 PiP 操作按钮广播接收器。
     */
    private fun unregisterPipActionReceiver() {
        if (isPipReceiverRegistered) {
            unregisterReceiver(pipActionReceiver)
            isPipReceiverRegistered = false
        }
    }

    // 注册超分开关广播接收器（exported，让 adb 和 LabSettingsFragment 都能发）。
    private fun registerSuperResolutionReceiver() {
        if (isSuperResReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_SUPER_RESOLUTION_ON)
            addAction(ACTION_SUPER_RESOLUTION_OFF)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(superResolutionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(superResolutionReceiver, filter)
        }
        isSuperResReceiverRegistered = true
    }

    private fun unregisterSuperResolutionReceiver() {
        if (isSuperResReceiverRegistered) {
            unregisterReceiver(superResolutionReceiver)
            isSuperResReceiverRegistered = false
        }
    }

}
