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
import me.lingci.dy.player.core.PlayRequest
import me.lingci.dy.player.core.PlayRequestGate
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
import me.lingci.dy.player.util.VideoDataBinder
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
            setMergeState = itemViewModel::setMergeState,
            historySource = { HistorySource.fromMediaData(itemViewModel.getMediaData()) }
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
    /**
     * 后台切集超时兜底：新集起播后应由 STATE_PLAYING → handOffPlaybackToBackground 交回 Service，
     * 若 8 秒内未交回（起播失败/Surface 未创建等），显式收尾避免孤立 FGS 空转。
     */
    private val bgSwitchTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val bgSwitchTimeoutRunnable = Runnable {
        if (pendingBgSwitch && !isForeground) {
            Log.d(this, "pendingBgSwitch timed out, closing background session")
            closeBackgroundSession()
        }
    }
    private fun armBgSwitchTimeout() {
        bgSwitchTimeoutHandler.removeCallbacks(bgSwitchTimeoutRunnable)
        bgSwitchTimeoutHandler.postDelayed(bgSwitchTimeoutRunnable, 8000L)
    }
    private fun disarmBgSwitchTimeout() {
        bgSwitchTimeoutHandler.removeCallbacks(bgSwitchTimeoutRunnable)
    }
    /**
     * 播放请求代次：所有异步回调(URL 解析、历史恢复、字幕、弹幕、缩略图)入口
     * 都必须先校验是否仍是当前请求，否则直接丢弃 (F1-1)。
     */
    private val playRequests = PlayRequestGate()
    /** 请求级 Job：新请求会取消旧请求仍在进行的解析/恢复。 */
    private var playJob: Job? = null
    /** Activity 是否处于前台(started)。后台交接提交点必须再看一眼。 */
    private var isForeground = false
    /** 当前生效的后端指纹，用于判断 player 能否复用 (F4-4)。 */
    private var activeBackendKey: String? = null
    /** Service 侧会话已被关闭(通知栏关闭)，回到前台需要显式重建。 */
    private var needsRestartOnForeground = false
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
            // 重播当前视频，触发 player + render view 重建（渲染配置变了，必须强制重建）
            startPlay(mCurPos, force = true)
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
        videoController.setEnableInNormal(true)
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
                        // F2-4：错误切集与完成切集、通知切集共用同一交接事务，
                        // 先把 Service 侧 player 收回，避免新旧 player 并存导致通知控制对象分裂。
                        reclaimFromServiceForSwitch(keepService = !isForeground)
                        longVideoControlView.showTips("播放错误，自动播放下一集")
                        onNextPlay()
                    } else {
                        // 不自动续播：显式终止后台会话，避免通知栏残留一个已损坏的会话。
                        closeBackgroundSession()
                        longVideoControlView.showTips("播放错误")
                    }
                }
                if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    longDanmakuController.saveCacheInfo(0, true)
                    // 同步清零 PlayInfo.playSeek，避免点击"重新播放"时 onPlayStart 又 seekTo 到历史位置
                    // 注意：必须同步执行，不能依赖 updateInfo 的异步写入（有 6 秒节流 + 竞态）
                    if (itemViewModel.getItemSize() > mCurPos) {
                        // 同步清零文件 playSeek + 异步清零 play_history 进度
                        PlayHelper.resetPlaySeek(
                            this@LongVideoActivity,
                            itemViewModel.getItem(mCurPos),
                            HistorySource.fromMediaData(itemViewModel.getMediaData())
                        )
                    }
                    // 后台播放模式下播完:先把 Service 侧 player 收回释放(避免双 player),
                    // 再切下一集。新视频起播后由 STATE_PLAYING 的 pendingBgSwitch 交回 Service。
                    reclaimFromServiceForSwitch(keepService = !isForeground)
                    // 标记进入完成态，下一次 STATE_PREPARED 触发的 onPlayStart 视为"重新播放"，跳过历史进度恢复
                    isReplayFromCompletion = true
                    if (spUtil.autoNext) {
                        onNextPlay()
                    }
                }
                if (playState == VideoView.STATE_PLAYING) {
                    clearBackgroundRecoveryState()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                    // 后台通知栏切集:新视频起播后重新交回 Service 并更新通知标题。
                    // 提交点会再次校验前后台状态与请求代次，前台绝不 detach（F2-5）。
                    if (pendingBgSwitch) {
                        pendingBgSwitch = false
                        handOffPlaybackToBackground(playRequests.current)
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
            // 后台切集到达边界：没有新视频可交回，显式收尾避免孤立 FGS
            if (pendingBgSwitch) {
                pendingBgSwitch = false
                closeBackgroundSession()
            }
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
            // 后台连播到达边界：没有新视频可交回，显式收尾避免孤立 FGS
            if (pendingBgSwitch) {
                pendingBgSwitch = false
                closeBackgroundSession()
            }
        }
    }

    override fun onTimeSync() {
        longDanmakuController.syncTime()
    }

    /**
     * 切集/起播的唯一入口。
     *
     * F1-1：每次调用递增请求代次并取消旧 Job，后续所有异步回调必须持同一 request 才能提交，
     * 避免 A→B 快速切换时旧结果 release 掉新播放器或把新集 seek 到旧集进度。
     * F4-4：后端/渲染配置未变时复用 player（MPV 走 loadfile replace），不重建 native 实例。
     */
    private fun startPlay(position: Int, force: Boolean = false) {
        if (position < 0 || position >= itemViewModel.getItemSize()) {
            Log.d(this, "startPlay skipped, invalid position", position)
            return
        }
        // 切换集数时重置"重新播放"标记，避免误判为完成态重播而跳过进度恢复
        isReplayFromCompletion = false
        mediaPlaybackRecorder.cancelLastPlayedUpdate()
        // 切换播放项前先清掉上一集弹幕列表/曲线，避免旧数据短暂残留。
        longDanmakuController.clearPanels()
        val videoBean = itemViewModel.getItem(position)
        // 取消旧请求：旧的 URL 解析回调到达时已不再 isCurrent
        playJob?.cancel()
        val request = playRequests.begin(stableMediaId(videoBean))
        Log.d(this, "startPlay", position, videoBean.name, request.requestId, force)
        logAndCache(
            "LongVideoActivity", "D",
            "startPlay position=$position id=${request.requestId} force=$force name=${videoBean.name} url=${videoBean.videoUrl}"
        )
        playJob = lifecycleScope.launch(Dispatchers.IO) {
            // 决策8：headers 不落库，播放前从 source_data 现算凭据回填（DB 加载的视频无凭据）
            VideoDataBinder.bindTokens(videoBean, itemViewModel.getMediaData())
            val playUrl = videoBean.playUrl()
            withContext(Dispatchers.Main) {
                if (!playRequests.isCurrent(request)) {
                    Log.d(this@LongVideoActivity, "startPlay abandoned stale request", request.requestId)
                    return@withContext
                }
                commitPlayback(request, position, videoBean, playUrl, force)
            }
        }
    }

    /** 稳定媒体身份：同一集多次重试必须命中同一个 id。 */
    private fun stableMediaId(videoData: VideoData): String =
        "${videoData.type}:${videoData.name}:${videoData.videoUrl}"

    /** 后端 + 渲染配置指纹，用于判断现有 player 能否复用 (F4-4)。 */
    private fun backendKeyFor(videoBean: VideoData, playUrl: String): String {
        val core = if (isSmbVideo(videoBean, playUrl)) DyPlayerCore.EXO else spUtil.dyPlayerCore
        return listOf(
            core.name,
            spUtil.surfaceRender,
            spUtil.labMpvSpecialRender,
            spUtil.labMpvSuperResolution
        ).joinToString("|")
    }

    /** 唯一提交点：setUrl/start 只在这里发生，提交前再校验一次请求身份。 */
    private fun commitPlayback(
        request: PlayRequest,
        position: Int,
        videoBean: VideoData,
        playUrl: String,
        force: Boolean
    ) {
        if (!playRequests.isCurrent(request)) return
        // 新视频起播前统一重置 controller 持有的弹幕 UI 状态。
        longDanmakuController.resetForNewPlayback()
        subtitleControlView.clearText()
        videoScaleControlView.resetValue()
        PlayerInitializer.resetSpeed()

        val backendKey = backendKeyFor(videoBean, playUrl)
        // 只有后端/渲染配置一致且未要求强制重建时，才复用现有 player。
        val reusePlayer = !force && videoView.hasPlayer() && backendKey == activeBackendKey
        if (!reusePlayer) {
            videoView.release()
            applyPlaybackCoreFor(videoBean, playUrl)
            activeBackendKey = backendKey
        }
        longVideoControlView.setTitle(videoBean.name)
        videoView.setUrl(
            playUrl,
            if (videoBean.is302(playUrl).not()) videoBean.headers else mutableMapOf()
        )
        // 先更新位置再起播：onPlayStart 依赖 mCurPos 定位同一集。
        mCurPos = position
        if (reusePlayer) {
            videoView.startOrReplace()
        } else {
            videoView.start()
        }
        videoView.post { videoScaleControlView.applyCache() }
        epSelectView.selected(position)
        saveMediaInfo(videoBean.videoUrl)
    }

    private fun retryCurrentVideoAfterBackgroundError() {
        if (itemViewModel.getItemSize() <= mCurPos) {
            return
        }
        val position = mCurPos
        val retryPosition = lastKnownPlaybackPosition
        val videoBean = itemViewModel.getItem(position)
        playJob?.cancel()
        val request = playRequests.begin(stableMediaId(videoBean))
        playJob = lifecycleScope.launch(Dispatchers.IO) {
            // 决策8：重试路径同样先现算凭据回填 headers，再解析播放地址
            VideoDataBinder.bindTokens(videoBean, itemViewModel.getMediaData())
            val playUrl = videoBean.playUrl()
            withContext(Dispatchers.Main) {
                if (!playRequests.isCurrent(request)
                    || position != mCurPos
                    || itemViewModel.getItemSize() <= position
                ) {
                    return@withContext
                }
                videoView.release()
                applyPlaybackCoreFor(videoBean, playUrl)
                activeBackendKey = backendKeyFor(videoBean, playUrl)
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
        if (itemViewModel.getItemSize() <= 0) {
            return
        }
        // F1-2：只处理当前请求对应的这一集；旧集迟到的 PREPARED 不允许再产生副作用。
        val request = playRequests.current ?: return
        val videoData = itemViewModel.getItem(position)
        if (stableMediaId(videoData) != request.mediaId) {
            Log.d(this, "onPlayStart ignored: stale media", position, request.requestId)
            return
        }
        attachSubtitle(videoData, position, request)
        PlayHelper.loadInfo(this@LongVideoActivity, lifecycleScope, videoData) { info ->
            if (!playRequests.isCurrent(request)) {
                Log.d(this@LongVideoActivity, "loadInfo ignored: stale request", request.requestId)
                return@loadInfo
            }
            if (position != mCurPos || itemViewModel.getItemSize() <= position) {
                return@loadInfo
            }
            var unLoadDm = true
            if (info != null) {
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
                        if (playRequests.isCurrent(request)) {
                            longDanmakuController.addDmFile(file)
                        }
                    }
                }
            }
            // 历史进度与片头跳过收敛为唯一 seek 提交点（F1-2）
            commitInitialSeek(request, info?.playSeek ?: 0L, skipSeekDueToReplay)
        }
        updateTrackPanel(getMediaTrackProvider()?.getMediaTracks() ?: MediaTrackSnapshot())
        setupMediaInfoProvider()
        binding.root.post {
            if (!playRequests.isCurrent(request)) {
                return@post
            }
            if (onHistory) {
                return@post
            }
            // 保存历史（sourceType/sourceKey 按进入来源派生，写入 play_history）
            PlayHelper.saveHistory(videoData, HistorySource.fromMediaData(itemViewModel.getMediaData()))
        }
        binding.root.postDelayed({
            if (!playRequests.isCurrent(request) || position != mCurPos) {
                return@postDelayed
            }
            // 保存预览图
            PlayHelper.saveThumb(this, lifecycleScope, videoView, videoData)
        }, 1000)
    }

    /**
     * 片头跳过与历史进度合并为唯一 seek 提交点。
     * 优先级：片头跳过 > 历史进度；两者都夹取到片长内，避免直接落在结尾触发 COMPLETED。
     */
    private fun commitInitialSeek(request: PlayRequest, historySeek: Long, skipHistory: Boolean) {
        if (!playRequests.isCurrent(request)) return
        val duration = videoView.duration.toLong()
        val opOffset = (itemViewModel.getMediaData()?.opOffset ?: 0).toLong() * 1000L
        var target = if (skipHistory) 0L else historySeek.coerceAtLeast(0L)
        var skipIntro = false
        if (opOffset > 0 && target < opOffset) {
            target = opOffset
            skipIntro = true
        }
        if (target <= 0) return
        if (duration > 0) {
            // 留 1 秒余量，避免 seek 落在末尾直接播完
            target = target.coerceAtMost((duration - 1000L).coerceAtLeast(0L))
        }
        if (target <= 0) {
            Log.d(this, "commitInitialSeek skipped: target out of range", target, duration, opOffset)
            return
        }
        Log.d(this, "commitInitialSeek", target, "duration", duration, "op", opOffset, "history", historySeek)
        videoView.seekTo(target)
        if (skipIntro) {
            longVideoControlView.showTips("跳过片头")
        } else {
            longVideoControlView.showTips("跳转上传播放的位置 ${PlayerUtils.stringForTime(target.toInt())}")
        }
    }

    private fun attachSubtitle(videoData: VideoData, position: Int, request: PlayRequest) {
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        lifecycleScope.launch(Dispatchers.IO) {
            val subtitleFile = PlayHelper.resolveSubtitleFile(this@LongVideoActivity, videoData)
            withContext(Dispatchers.Main) {
                // 请求身份优先：仅位置校验无法区分同位置的重试与旧回调
                if (!playRequests.isCurrent(request)) {
                    return@withContext
                }
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
        isForeground = true
        android.util.Log.i("BgPlayDebug", "onStart: isBound=" + isBoundToPlaybackService + " binder=" + (playbackService != null) + " holding=" + (playbackService?.isHoldingPlayer ?: false))
        // M2 fix: 用户已返回前台，清除"待进入后台"标记。
        pendingBackgroundEntry = false
        // F2-5: 回前台时取消尚未提交的后台切集意图，避免新集 PLAYING 时把前台 player detach 出去。
        pendingBgSwitch = false
        // 如果 binder 已连接且 Service 持有 player，直接取回
        if (playbackService?.isHoldingPlayer == true) {
            reclaimPlayerFromService()
            return
        }
        // Service 会话在后台被关闭(通知栏关闭)或已无 player：回到前台必须显式重建，避免黑屏。
        if (needsRestartOnForeground || (isBoundToPlaybackService && !videoView.hasPlayer())) {
            needsRestartOnForeground = false
            Log.d(this, "onStart: no player in view, restarting pos=$mCurPos")
            closeBackgroundSession()
            if (itemViewModel.getItemSize() > 0) {
                startPlay(mCurPos, force = true)
            }
            return
        }
        // 注:不再用 500ms 延迟 tryReclaim。它在按 Home 进后台时会与 Service 的
        // takePlayer 竞态,错误地把刚交给 Service 的 player 取回,导致后台播放失效。
        // 通知点击回前台时,contentIntent 的 SINGLE_TOP 会走 onNewIntent,
        // binder 仍连接则由上面的 isHoldingPlayer 分支处理。
    }

    private fun reclaimPlayerFromService() {
        val binder = playbackService ?: return
        binder.setSkipCallback(null)
        binder.setPlayerClosedCallback(null)
        // 最终 reclaim：Service 不再持有 player，前台通知与 FGS 一并收尾。
        val player = binder.returnPlayer(stopService = true)
        binder.stopIfNoPlayer()
        try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
        isBoundToPlaybackService = false
        playbackService = null
        if (player == null) return
        videoView.setEnableAudioFocus(true)
        // 如果 player 已播完(ENDED/COMPLETED),不 attach 它(画面会是黑屏),
        // 直接释放并重新 startPlay 当前位置,让画面恢复。
        val state = videoView.currentPlayState
        if (state == VideoView.STATE_PLAYBACK_COMPLETED || state == VideoView.STATE_ERROR) {
            player.release()
            Log.d(this, "reclaim: player completed/error, restarting pos=$mCurPos")
            startPlay(mCurPos, force = true)
            return
        }
        // attach 内部会重新申请音频焦点，避免只改开关却没真正持有焦点（F2-3）。
        videoView.attachPlayer(player)
        Log.d(this, "resumed from background, player reattached")
    }

    /**
     * 终止后台会话：停通知、解绑、释放 Service 侧 player。
     * 与 [reclaimFromServiceForSwitch] 的区别是这里不保留 FGS，用于"会话真的结束"的场景。
     */
    private fun closeBackgroundSession() {
        val binder = playbackService
        binder?.setSkipCallback(null)
        binder?.setPlayerClosedCallback(null)
        binder?.returnPlayer(stopService = true)?.release()
        binder?.stopIfNoPlayer()
        if (isBoundToPlaybackService) {
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
        }
        playbackService = null
        pendingBgSwitch = false
        disarmBgSwitchTimeout()
        pendingBackgroundEntry = false
        videoView.setEnableAudioFocus(true)
    }

    /**
     * 统一的后台交接事务（F2-4）：切集/重播前把 player 从 Service 收回，
     * 保证新视频在 Activity 侧是唯一持有者。
     *
     * @param keepService true = 临时取回，保留绑定与前台通知，供后台连播直接交回，
     *                    避免再次 startForegroundService（Android 12+ 后台限制）；
     *                    false = 会话结束，停前台通知并解绑。
     * @return Service 此前是否持有 player
     */
    private fun reclaimFromServiceForSwitch(keepService: Boolean): Boolean {
        val binder = playbackService
        if (binder == null || !isBoundToPlaybackService) {
            if (!keepService) closeBackgroundSession()
            return false
        }
        val held = binder.isHoldingPlayer
        // 后台切集期间 Service 不再需要感知关闭事件，切换事务本身接管收尾
        binder.setPlayerClosedCallback(null)
        binder.returnPlayer(stopService = !keepService)?.release()
        videoView.setEnableAudioFocus(true)
        if (keepService) {
            pendingBgSwitch = true
            armBgSwitchTimeout()
        } else {
            binder.stopIfNoPlayer()
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
            playbackService = null
            pendingBgSwitch = false
        }
        Log.d(this, "reclaimForSwitch keepService=$keepService held=$held")
        return held
    }

    /** 通知栏关闭事件：Service 已释放 player，按当前可见性决定恢复或等待。 */
    private fun handleSessionClosedByService(reason: String) {
        Log.d(this, "playback session closed by service: $reason")
        if (isFinishing || isDestroyed) return
        if (isForeground) {
            // 前台可见却没了 player：立即重建，避免黑屏/静止画面。
            startPlay(mCurPos, force = true)
        } else {
            // 后台不可见时不擅自起播（会变成无通知的裸音频），回到前台再恢复。
            needsRestartOnForeground = true
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
        // F2-1：退出时强制持久化当前进度（含末尾<10s 清零）到 PlayInfo 文件 + DB，
        // 否则重进读 PlayInfo 仍旧值，末尾进度守卫失效。update=false 绕过 6 秒节流。
        longDanmakuController.saveCacheInfo(lastKnownPlaybackPosition, update = false)
    }

    override fun onStop() {
        super.onStop()
        // 先落前台状态：后台交接提交点依赖它做最后校验（前台绝不 detach）。
        isForeground = false
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
        // 播放中(含 PREPARING/BUFFERING 的起播意图) → 进入后台播放模式
        if (shouldEnterBackgroundPlay()) {
            startPlaybackService()
        } else if (isBoundToPlaybackService) {
            // 已不具备后台播放条件(已暂停/无 player)：收尾，避免残留前台通知与孤立绑定
            closeBackgroundSession()
        }
    }

    /**
     * 后台播放触发条件：开关开启 + 有 player + 用户仍想播放。
     *
     * PREPARING/BUFFERING 也算"想播放"：用户暂停时 {@code pause()} 已把状态改成 PAUSED 并清掉
     * playWhenReady，所以 PREPARING 只可能来自"正在起播"。把这两种状态纳入后，
     * 离开页面时不会出现"prepare 完成后在后台无通知地出声"（F2-7）。
     */
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
        val active = state == VideoView.STATE_PLAYING
                || state == VideoView.STATE_BUFFERED
                || state == VideoView.STATE_PREPARING
                || state == VideoView.STATE_BUFFERING
        return videoView.hasPlayer() && active
    }

    /** 启动并绑定 PlaybackService。 */
    private fun startPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        intent.putExtra("source_activity", LongVideoActivity::class.java.name)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Android 12+ 后台禁止启动 FGS：降级为不接管，避免直接崩溃（F2-6）。
            Log.d(this, "startForegroundService rejected: ${e.message}")
            videoView.setEnableAudioFocus(true)
            longVideoControlView.showTips("后台播放不可用，请回到前台继续")
            return
        }
        bindService(intent, playbackServiceConnection, Context.BIND_AUTO_CREATE)
        isBoundToPlaybackService = true
        // M2 fix: 标记正在等待 onServiceConnected → tryEnterBackgroundPlay。
        // onStart 会清除此标记；如果 Service 回调这时才到，说明用户已返回前台，应放弃 detach。
        pendingBackgroundEntry = true
        // 禁用 view 的音频焦点（Service 接管）
        videoView.setEnableAudioFocus(false)
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
            binder.stopIfNoPlayer()
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
            playbackService = null
            videoView.setEnableAudioFocus(true)
            return
        }
        pendingBackgroundEntry = false
        handOffPlaybackToBackground(playRequests.current)
    }

    /**
     * 把当前 player 交回后台 Service。
     * 这是 detach 的唯一提交点：必须同时满足"仍处于后台 + 仍是同一请求 + View 持有 player"，
     * 否则前台播放器会被错误交出导致画面冻结（F2-5）。
     */
    private fun handOffPlaybackToBackground(request: PlayRequest?) {
        if (isForeground) {
            Log.d(this, "skip background handoff: activity is foreground")
            // 前台绝不 detach；顺手收掉不再需要的 FGS，避免残留通知
            if (playbackService?.isHoldingPlayer == false) playbackService?.stopIfNoPlayer()
            return
        }
        if (request == null || !playRequests.isCurrent(request)) {
            Log.d(this, "skip background handoff: stale request")
            if (playbackService?.isHoldingPlayer == false) playbackService?.stopIfNoPlayer()
            return
        }
        if (!shouldEnterBackgroundPlay()) {
            Log.d(this, "skip background handoff: not in active playback")
            if (playbackService?.isHoldingPlayer == false) playbackService?.stopIfNoPlayer()
            return
        }
        val binder = playbackService ?: run {
            // 仍未连上：保持待交回意图，ServiceConnection 回调会再次尝试
            pendingBgSwitch = true
            return
        }
        if (binder.isHoldingPlayer) return
        // 元数据必须在 detach 之前采样：detach 后 View 已无 player，duration 会变成 0。
        val metadata = PlaybackMetadata(
            title = currentTitle,
            subtitle = currentSourceName,
            duration = videoView.duration,
            currentPosition = videoView.currentPosition
        )
        val player = videoView.detachPlayer() ?: return
        // Task 8: 先用 null 封面 takePlayer(立即显示标题),再异步加载封面后 updateMetadata。
        binder.setSourceActivity(LongVideoActivity::class.java)
        binder.takePlayer(player, metadata.copy(coverBitmap = null))
        binder.setSkipCallback(skipCallback)
        binder.setPlayerClosedCallback(::handleSessionClosedByService)
        disarmBgSwitchTimeout()
        loadCoverBitmap(currentCoverUrl) { bitmap ->
            if (bitmap != null) {
                playbackService?.updateMetadata(metadata.copy(coverBitmap = bitmap))
            }
        }
    }

    /**
     * 通知栏"上一个/下一个"回调（后台切集）。
     * 与完成/错误切集共用同一交接事务：先临时收回并释放 Service 侧 player，再切集；
     * 新集起播后由 pendingBgSwitch 交回同一个 Service 实例。
     */
    private val skipCallback: (Int) -> Unit = { direction ->
        Log.d(this, "notification skip: direction=$direction")
        reclaimFromServiceForSwitch(keepService = !isForeground)
        if (direction > 0) onNextPlay() else onPreviousPlay()
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
        // 作废所有在途请求：销毁后不再允许任何异步回调提交起播
        playRequests.invalidate()
        playJob?.cancel()
        playJob = null
        disarmBgSwitchTimeout()

        val binder = playbackService
        // Service 侧闭包持有 Activity 引用，必须在销毁时清掉（F2-2）。
        binder?.setSkipCallback(null)
        binder?.setPlayerClosedCallback(null)

        // 区分"UI 销毁"与"主动结束"（F2-2 / F2-10）：
        // 后台播放中 Activity 被系统回收时，播放会话属于 Service，这里只解绑不取回不释放，
        // 由 Service.onTaskRemoved / 通知控制收尾；用户主动退出或配置变化才真正释放。
        val keepBackgroundSession = !userInitiatedExit
                && !isFinishing
                && !isChangingConfigurations
                && isBoundToPlaybackService
                && binder?.isHoldingPlayer == true
        if (keepBackgroundSession) {
            Log.d(this, "onDestroy: background session kept by service")
        } else {
            binder?.returnPlayer(stopService = true)?.release()
            binder?.stopIfNoPlayer()
        }
        if (isBoundToPlaybackService) {
            try { unbindService(playbackServiceConnection) } catch (_: Exception) {}
            isBoundToPlaybackService = false
        }
        pendingBgSwitch = false
        pendingBackgroundEntry = false
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
        val request = playRequests.current
        if (request == null || itemViewModel.getItemSize() <= mCurPos) {
            // 没有有效请求时明确失败，而不是截一张空图（F4-3）
            longVideoControlView.showTips("截图失败")
            return
        }
        PlayHelper.doScreenShot(
            lifecycleScope, videoView,
            itemViewModel.getItem(mCurPos),
            videoView.currentPosition
        ) { save ->
            // 截图完成时若已切集，该产物不再属于当前画面，只提示不误导
            val stillCurrent = playRequests.isCurrent(request)
            lifecycleScope.launch(Dispatchers.Main) {
                longVideoControlView.showTips(
                    when {
                        !stillCurrent -> "截图完成（已切换视频）"
                        save -> "截图成功"
                        else -> "截图失败"
                    }
                )
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
            // PiP 退出后的决策顺序（F2-10）：
            // - 展开回全屏：Activity 仍可见，这里绝不能 detach，否则画面冻结 + 误显示通知；
            // - 关闭小窗：系统随后会回调 onStop，真正的后台交接交给 onStop 判断。
            // 因此这里只处理"用户明确要求暂停"的分支，不主动启动后台服务。
            if (!spUtil.longVideoBackgroundPlay) {
                if (::videoView.isInitialized && videoView.hasPlayer()) {
                    videoView.pause()
                    android.util.Log.i("BgPlayDebug", "PiP exit: bg switch off, pause")
                }
            } else {
                android.util.Log.i("BgPlayDebug", "PiP exit: defer background decision to onStop")
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
