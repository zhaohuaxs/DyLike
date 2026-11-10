package me.lingci.dy.player.ui.short_video

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityShortVideoBinding
import me.lingci.dy.player.cache.ProgressManagerImpl
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.DyPlayerCoreRegistry
import me.lingci.dy.player.core.PlayRequest
import me.lingci.dy.player.core.PlayRequestGate
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.Favorite
import me.lingci.dy.player.ui.long_video.HistorySource
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.AppUtil.removeViewFormParent
import me.lingci.dy.player.util.ExternalSubtitleLoader
import me.lingci.dy.player.util.MediaPlaybackRecorder
import me.lingci.dy.player.util.MediaManger
import me.lingci.dy.player.util.PlaybackErrorLogger
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.dy.player.util.PlaybackTraceHelper
import me.lingci.dy.player.util.PreloadCapabilityGuard
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.ShortTitleFormatter
import me.lingci.dy.player.util.VideoDataBinder
import me.lingci.dy.player.util.VideoListTempStore
import me.lingci.dy.player.view.ShortVideoControlView
import me.lingci.dy.player.view.ShortVideoController
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.HttpUtil
import me.lingci.lib.base.util.createNew
import me.lingci.lib.base.util.isLocal
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.md5
import me.lingci.lib.base.util.safeGetParcelable
import me.lingci.lib.base.util.safeGetParcelableArrayList
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.render.ShortVideoRenderViewFactory
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videoplayer.player.BaseVideoView.OnStateChangeListener
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.player.VideoViewManager
import xyz.doikki.videoplayer.render.TextureRenderViewFactory
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 短视频，使用ViewPager2实现
 * Created by Doikki on 2019/12/04.
 */
class ShortVideoActivity : BaseActivity() {

    private val subtitleScaleNormal = 1.0f
    private val subtitleScaleFullScreen = 1.2f

    companion object {
        private const val KEY_LIST = "list"
        private const val KEY_INDEX = "index"
        private const val KEY_HISTORY = "history"
        private const val KEY_RANDOM = "random"
        private const val KEY_TEMP = "temp"
        private const val KEY_MEDIA = "media"
        private const val CONTROL_ALPHA_FULL = 1.0f
        private const val CONTROL_ALPHA_DIMMED = 0.3f
        private const val CONTROL_ALPHA_DIM_PROGRESS = 0.4f
        private const val CONTROL_ALPHA_RESTORE_DURATION = 160L
        // 播放完成判定阈值：剩余时间小于此值视为播放完成（毫秒）
        private const val COMPLETION_THRESHOLD = 1000L
        // 播放进度落库心跳：略短于 PlayHelper 的 6 秒节流窗口，保证每个窗口至少落一次
        private const val PROGRESS_TICK_MS = 5000L

        fun start(context: Context, list: ArrayList<VideoData>, index: Int, history: Boolean) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            start(context, i, list)
        }

        fun start(
            context: Context,
            mediaData: MediaData?,
            list: ArrayList<VideoData>,
            index: Int,
            history: Boolean
        ) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            if (mediaData != null && !history) {
                i.putExtra(KEY_MEDIA, mediaData)
            }
            start(context, i, list)
        }

        fun start(
            context: Context,
            list: ArrayList<VideoData>,
            index: Int,
            history: Boolean,
            random: Boolean
        ) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            i.putExtra(KEY_RANDOM, random)
            start(context, i, list)
        }

        // 同时携带 mediaData 与 random：供自动播放等需要既记录历史又显式控制随机的入口使用
        fun start(
            context: Context,
            mediaData: MediaData?,
            list: ArrayList<VideoData>,
            index: Int,
            history: Boolean,
            random: Boolean
        ) {
            val i = Intent(context, ShortVideoActivity::class.java)
            i.putExtra(KEY_INDEX, index)
            i.putExtra(KEY_HISTORY, history)
            i.putExtra(KEY_RANDOM, random)
            if (mediaData != null && !history) {
                i.putExtra(KEY_MEDIA, mediaData)
            }
            start(context, i, list)
        }

        private fun start(context: Context, intent: Intent, list: ArrayList<VideoData>) {
            intent.putExtra(KEY_TEMP, VideoListTempStore.write(context, list))
            context.startActivity(intent)
        }
    }

    /**
     * 当前播放位置
     */
    private var mCurPos = 0
    private val spUtil by lazy { SpUtil(this) }
    private var onHistory: Boolean = false
    private var mediaData: MediaData? = null
    private val mVideoList: MutableList<VideoData> = mutableListOf()
    private lateinit var mBinding: ActivityShortVideoBinding
    private lateinit var mVideoView: CustomVideoView
    private lateinit var mShortVideoAdapter: ShortVideoAdapter
    private lateinit var mController: ShortVideoController
    private lateinit var subtitleControlView: SubtitleControlView
    private lateinit var mViewPagerImpl: RecyclerView
    // 三个设置/评论/更多弹窗统一改为 lazy，避免 onResume 早于异步 initData 完成时
    private val shortSettingsDialog by lazy { ShortSettingsDialog() }
    private val shortCommentDialog by lazy { ShortCommentDialog() }
    private val shortMoreDialog by lazy { ShortMoreDialog() }
    private var activeShortVideoControlView: ShortVideoControlView? = null

    /** 每次起播/重试都有独立身份，异步 URL/token/302 回调不能提交到后来的页面。 */
    private val playRequests = PlayRequestGate()
    private var playJob: Job? = null
    private var isForeground = false
    private var pagerSelectedItem = 0
    // C1: 推迟 mVideoView 移动/切源到 SCROLL_STATE_IDLE，避免 settle 期间旧页未离屏时画面跳回中央。
    private var pendingPlayTarget = -1
    private var currentScrollState = ViewPager2.SCROLL_STATE_IDLE

    // ---- 双 VideoView 预加载 ----
    private var mNextVideoView: CustomVideoView? = null
    private var mNextPreloadedPos = -1
    private var mNextPreloadTargetPos = -1
    private var mNextPreloadJob: Job? = null
    // 方向预测已能提前预备回滑目标，旧播放器不再需要长时间驻留；缩短并存实例窗口
    private val mNextPreloadReleaseDelayMs = 120L
    /** 本次拖动已锁定的方向预测目标，用于去重，避免每帧 onPageScrolled 反复重建预载。 */
    private var mLastPredictedPos = -1
    /** 解码器初始化失败后的冷却表：url -> 失败时刻，避免快滑时反复重建硬解实例。 */
    private val preloadFailureAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val preloadCooldownMs = 30_000L
    /** 归位预载的单例 pending：连续切页时只保留最后一次，减少解码器初始化次数。 */
    private var pendingPreloadNext: Runnable? = null
    private val preloadMergeDelayMs = 500L

    /** 沉浸体验定时器(Activity 级,确保操作的是活跃的 ControlView)。 */
    private val immersiveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var immersiveScheduled = false
    private var immersiveDeadline = 0L
    // 显式声明为 Runnable 并用 object 实现：run() 内部需要重新 postDelayed 自身，
    // 用 lambda 会触发递归类型推断（Kotlin 无法推断自引用 lambda 的类型）。
    private val enterImmersiveRunnable: Runnable = object : Runnable {
        override fun run() {
            val control = activeShortVideoControlView
            if (!isForeground || control == null) {
                immersiveScheduled = false
                return
            }
            if (control.isUserInteracting) {
                // 拖进度/长按/缩放/翻页期间禁止隐藏控件；操作结束从完整 3 秒重新计时。
                immersiveDeadline = System.currentTimeMillis() + 3000L
                immersiveHandler.postDelayed(this, 250L)
                return
            }
            val remaining = immersiveDeadline - System.currentTimeMillis()
            if (remaining > 0) {
                immersiveHandler.postDelayed(this, remaining)
                return
            }
            immersiveScheduled = false
            control.enterImmersive()
        }
    }
    private fun scheduleImmersive() {
        if (spUtil.showSysBar || !isForeground) return
        immersiveHandler.removeCallbacks(enterImmersiveRunnable)
        immersiveScheduled = true
        immersiveDeadline = System.currentTimeMillis() + 3000L
        immersiveHandler.postDelayed(enterImmersiveRunnable, 3000)
    }
    private fun cancelImmersive() {
        immersiveHandler.removeCallbacks(enterImmersiveRunnable)
        immersiveScheduled = false
    }
    private fun exitAndRescheduleImmersive() {
        activeShortVideoControlView?.exitImmersive()
        scheduleImmersive()
    }

    // 超分开关广播接收器：仅写 SP，重播生效由短视频列表自然触发（滑到下一个再滑回来）。
    // 短视频列表架构复杂，运行时强切 render view 风险大；简化为"下次播放时生效"。
    private val superResolutionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == me.lingci.dy.player.ui.long_video.LongVideoActivity.ACTION_SUPER_RESOLUTION_ON
            if (spUtil.labMpvSuperResolution == on) return
            spUtil.labMpvSuperResolution = on
            android.widget.Toast.makeText(
                this@ShortVideoActivity,
                if (on) "画质增强：已开启（切换视频后生效）" else "画质增强：已关闭（切换视频后生效）",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    private var isSuperResReceiverRegistered = false

    private fun registerSuperResolutionReceiver() {
        if (isSuperResReceiverRegistered) return
        val filter = android.content.IntentFilter().apply {
            addAction(me.lingci.dy.player.ui.long_video.LongVideoActivity.ACTION_SUPER_RESOLUTION_ON)
            addAction(me.lingci.dy.player.ui.long_video.LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
    private val isUserScroll = AtomicBoolean(false)
    private val playbackLogCache = PlaybackLogCache()
    private val mediaPlaybackRecorder by lazy { MediaPlaybackRecorder(spUtil) }
    // 评论文件持久化和播放位置回写已下沉，Activity 只负责当前页计数刷新。
    private val shortCommentController by lazy {
        ShortCommentController(
            context = this,
            scope = lifecycleScope,
            currentPlayPosition = { mVideoView.currentPosition },
            updateCommentCount = ::updateCommentCount
        )
    }
    // 定时关闭同时影响设置弹窗和当前控制条，统一交给 controller 维护剩余时间。
    private val timerCloseController by lazy {
        TimerCloseController(
            activity = this,
            settingsDialog = shortSettingsDialog,
            activeControlView = { activeShortVideoControlView },
            closeApp = ::closeApp
        )
    }
    // 文件重命名/删除/分享涉及本地与远程存储解析，集中到独立 action 类处理。
    private val shortVideoFileActions by lazy {
        ShortVideoFileActions(
            activity = this,
            scope = lifecycleScope,
            spUtil = spUtil,
            mediaData = { mediaData },
            videoList = { mVideoList },
            currentPosition = { mCurPos },
            adapter = { mShortVideoAdapter },
            controller = { mController },
            currentControlView = { findCurrentShortVideoControlView() },
            removeItemById = ::removeItemById
        )
    }
    // 字幕停靠几何计算较重，拆出后 Activity 只在时机点触发刷新。
    private lateinit var subtitleDockController: SubtitleDockController

    /** 标记：用户主动按返回退出 */
    private var userInitiatedExit = false

    private fun getExternalTrackController(): ExternalTrackController? {
        return mVideoView.getPlayerCapability(ExternalTrackController::class.java)
    }

    private fun getSubtitleCueProvider(): SubtitleCueProvider? {
        return mVideoView.getPlayerCapability(SubtitleCueProvider::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUtil.setStatusBarTransparent(this)
        hideSysBar()
        mBinding = ActivityShortVideoBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        // 播放器视图必须在任何生命周期回调与用户交互回调之前完成构造，
        // 消除异步 initData（KEY_TEMP 路径）未完成时 onPause 等回调访问 mVideoView 的 lateinit 竞态
        ensureVideoViewConstructed()
        init()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    private fun logAndCache(tag: String, level: String, message: String) {
        // 与长视频页共用同一套 trace 入口，便于后续统一排查播放问题。
        PlaybackTraceHelper.logAndCache(playbackLogCache, tag, level, message)
    }

    private fun setupSurfaceTrace() {
        PlaybackTraceHelper.setupSurfaceTrace(spUtil.debugMode, playbackLogCache)
    }

    private fun clearSurfaceTrace() {
        PlaybackTraceHelper.clearSurfaceTrace()
    }

    /** 根据短视频标题策略格式化文件名为展示标题（参数从运行时缓存读取）。 */
    private fun formatShortTitle(rawName: String): String {
        return ShortTitleFormatter.format(rawName)
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
            FileOperator.getSortedFiles(File(filepath).parentFile!!, FileOperator.VIDEO_EXTENSIONS)
                .let {
                    it.forEach { file ->
                        if (file.path == filepath) {
                            list.add(0, VideoData(file))
                        } else {
                            list.add(VideoData(file))
                        }
                    }
                }
        } else {
            list.add(VideoData(File(filepath)))
        }
        onHistory = true
        mVideoList.clear()
        // initData 内部含挂起 DAO 调用（收藏状态读取），协程内执行
        lifecycleScope.launch { initData(0, false, false, list) }
    }

    private fun init() {
        setupSurfaceTrace()
        PlayerInitializer.initShort(
            spUtil.shortLifeSpeed,
            spUtil.shortRightSpeed,
            spUtil.showShortLike,
            spUtil.showShortComment,
            spUtil.showShortTitle,
            spUtil.showShortPager,
            spUtil.showShortSeekbar,
            spUtil.showSysBar,
            spUtil.showShortMore
        )
        PlayerInitializer.Player.shortAutoNext = spUtil.shortPlayNext
        // 同步短视频标题策略运行时缓存
        PlayerInitializer.Player.shortTitleStrategy = spUtil.shortTitleStrategy
        PlayerInitializer.Player.shortTitleDelimiter = spUtil.shortTitleDelimiter!!
        PlayerInitializer.Player.shortTitleRegex = spUtil.shortTitleRegex!!
        PlayerInitializer.Player.shortTitleMaxLines = spUtil.shortTitleMaxLines
        val customRandom = intent.hasExtra(KEY_RANDOM)
        val random = intent.getBooleanExtra(KEY_RANDOM, false)
        val index = intent.getIntExtra(KEY_INDEX, 0)
        onHistory = intent.getBooleanExtra(KEY_HISTORY, false)
        mediaData = intent.safeGetParcelable(KEY_MEDIA)
        intent.safeGetParcelableArrayList<VideoData>(KEY_LIST)?.let {
            // initData 内部含挂起 DAO 调用（收藏状态读取），协程内执行
            lifecycleScope.launch { initData(index, customRandom, random, it) }
        }
        intent.getStringExtra(KEY_TEMP)?.let { path ->
            lifecycleScope.launch(Dispatchers.IO) {
                val list = VideoListTempStore.readAndDelete(path).toMutableList()
                withContext(Dispatchers.Main) {
                    initData(index, customRandom, random, list)
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                userInitiatedExit = true
                if (!::mVideoView.isInitialized || !mVideoView.onBackPressed()) {
                    finish()
                }
            }
        })
        fromOpen()
    }

    // suspend：收藏状态从 favorite 表异步读取（原 SP likeJson 同步读取已废弃）
    private suspend fun initData(
        index: Int,
        customRandom: Boolean,
        random: Boolean,
        list: MutableList<VideoData>
    ) {
        // 收藏状态改查 favorite 表（video_id 与 VideoData.md5() 规则一致）
        val ids = DatabaseManager.favoriteDao.listVideoIds().toSet()
        logD("likes ${ids.joinToString(",")}")
        list.forEach {
            it.id = it.md5()
            it.like = ids.contains(it.id)
        }
        var currentIndex = index
        if (((customRandom && random) || (!customRandom && spUtil.shortRandom)) && index in list.indices) {
            val data = list.removeAt(index)
            mVideoList.addAll(list.shuffled())
            mVideoList.add(0, data)
            currentIndex = 0
        } else {
            mVideoList.addAll(list)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            MediaManger.scanVideoThumb(baseContext, mVideoList.toList())
        }

        // 播放器视图已在 onCreate 同步构造（ensureVideoViewConstructed），此处仅初始化列表
        initViewPager()

        mBinding.viewpage2.post {
            logD("currentIndex", currentIndex)
            pagerSelectedItem = currentIndex
            if (currentIndex != 0) {
                mBinding.viewpage2.setCurrentItem(currentIndex, false)
            }
            startPlay(currentIndex)
        }
        shortSettingsDialog.onChange {
            mVideoView.setLooping(PlayerInitializer.Player.shortAutoNext.not())

            mVideoView.start()
            // 沉浸体验:开关变化时重新调度
            cancelImmersive()
            activeShortVideoControlView?.exitImmersive()
            scheduleImmersive()
        }
        shortSettingsDialog.onTimerClose {
            timerCloseController.showDialog()
        }
        // 评论弹窗只绑定一次，后续增删逻辑全部在 controller 内完成。
        shortCommentController.bind(shortCommentDialog)
    }

    private fun updateCommentCount(count: Int) {
        val countTmp = mViewPagerImpl.childCount
        for (i in 0 until countTmp) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as ShortVideoAdapter.ViewHolder
            if (viewHolder.bindingAdapterPosition == mCurPos) {
                viewHolder.shortVideoControlView.setCommentCount(count)
                break
            }
        }
    }

    /**
     * 播放器视图同步构造与基础配置（幂等）。
     * 在 onCreate 调用，构造期不依赖 mVideoList（applyConfiguredPlaybackCore/ShortVideoController
     * 均只读 spUtil 与 context）；依赖数据项的挂载（addView/setUrl/start）保留在 startPlay。
     * 状态监听器内引用的 mViewPagerImpl 仅在播放开始后才会被回调触发（startPlay 晚于
     * initViewPager 同步执行），无早期回调风险。
     */
    private fun ensureVideoViewConstructed() {
        if (::mVideoView.isInitialized) return
        mVideoView = CustomVideoView(this)
        applyConfiguredPlaybackCore()
        mVideoView.setLooping(PlayerInitializer.Player.shortAutoNext.not())
        mController = ShortVideoController(this)
        subtitleControlView = SubtitleControlView(this)
        subtitleDockController = SubtitleDockController(
            context = this,
            videoViewProvider = { mVideoView },
            subtitleControlView = subtitleControlView,
            currentPosition = { mCurPos }
        )
        subtitleControlView.setSubtitleScale(subtitleScaleNormal)
        mController.addControlComponent(subtitleControlView)
        mVideoView.setVideoController(mController)
        mVideoView.setEnableAudioFade(spUtil.audioFadeEnabled)
        mVideoView.setAudioFadeInDuration(spUtil.audioFadeInDuration)
        mVideoView.setAudioFadeOutDuration(spUtil.audioFadeOutDuration)
        mVideoView.addOnStateChangeListener(createMainStateListener(mVideoView))
    }

    private fun createMainStateListener(targetView: CustomVideoView): OnStateChangeListener {
        return object : OnStateChangeListener {

            private var isLandscape = false

            override fun onPlayerStateChanged(playerState: Int) {
                if (mVideoView !== targetView) return
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                        applySubtitleTextSizeForPlayerState(VideoView.PLAYER_NORMAL)
                        isUserScroll.set(false)
                        updateSubtitleDocking()
                        if (isLandscape) {
                            isLandscape = false
                            mViewPagerImpl.scrollToPosition(mCurPos)
                        }
                    }

                    VideoView.PLAYER_FULL_SCREEN -> {
                        applySubtitleTextSizeForPlayerState(VideoView.PLAYER_FULL_SCREEN)
                        isUserScroll.set(false)
                        isLandscape = true
                        updateSubtitleDocking()
                        mViewPagerImpl.scrollToPosition(mCurPos)
                    }
                }
            }

            override fun onPlayStateChanged(playState: Int) {
                if (mVideoView !== targetView) return
                if (playState == VideoView.STATE_PREPARED) {
                    logAndCache(TAG, "D", "STATE_PREPARED: pos=$mCurPos")
                    updateSubtitleDocking()
                    attachSubtitle()
                }
                if (playState == VideoView.STATE_PLAYING) {
                    logAndCache(TAG, "D", "STATE_PLAYING: pos=$mCurPos")
                    updateSubtitleDocking()
                    onPlayStart()
                    scheduleMediaLastPlayedUpdate(mCurPos)
                    preloadNext()
                }
                if (playState == VideoView.STATE_PAUSED) {
                    logAndCache(TAG, "D", "STATE_PAUSED: pos=$mCurPos")
                    stopProgressTicker()
                    saveProgress(force = true)
                }
                if (playState == VideoView.STATE_PLAYBACK_COMPLETED) {
                    logAndCache(TAG, "D", "STATE_PLAYBACK_COMPLETED: pos=$mCurPos")
                    stopProgressTicker()
                    val position = mCurPos
                    if (position in mVideoList.indices) {
                        PlayHelper.resetPlaySeek(
                            this@ShortVideoActivity, mVideoList[position], historySource()
                        )
                    }
                }
                if (playState == VideoView.STATE_ERROR) {
                    logAndCache(TAG, "E", "STATE_ERROR: pos=$mCurPos")
                    subtitleControlView.clearSubtitleLayoutBounds()
                    val videoData = if (mCurPos in mVideoList.indices) mVideoList[mCurPos] else null
                    if (spUtil.debugMode) {
                        PlaybackErrorLogger.saveErrorLog(
                            lifecycleScope,
                            this@ShortVideoActivity,
                            playbackLogCache,
                            PlaybackErrorLogger.ErrorInfo(
                                videoPosition = mCurPos,
                                videoName = videoData?.name ?: "N/A",
                                videoUrl = videoData?.videoUrl ?: "N/A",
                                playPosition = mVideoView.currentPosition,
                                playState = mVideoView.currentPlayState,
                                playerState = mVideoView.currentPlayerState,
                                hasMediaPlayer = mVideoView.hasPlayer(),
                                hasRenderView = mVideoView.getRenderTransformView() != null,
                                storageType = videoData?.type?.name ?: "N/A"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun initViewPager() {
        mBinding.viewpage2.offscreenPageLimit = 2
        mShortVideoAdapter = ShortVideoAdapter(mVideoList)
        mBinding.viewpage2.adapter = mShortVideoAdapter
        mBinding.viewpage2.overScrollMode = View.OVER_SCROLL_NEVER
        mBinding.viewpage2.registerOnPageChangeCallback(object : OnPageChangeCallback() {

            private var mCurItem = 0
            private var dragStartItem = 0

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
                super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                updateControlAlphaForPageScroll(position, positionOffset, dragStartItem)
                predictPreloadByDirection(position, positionOffset, dragStartItem)
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position == pagerSelectedItem) return
                pagerSelectedItem = position
                // 程序化切页(setCurrentItem false)时 scrollState 为 IDLE，直接起播；
                // 用户拖动/settle 期间推迟到 IDLE 再移动 mVideoView，避免旧页未离屏时画面跳回中央。
                if (currentScrollState == ViewPager2.SCROLL_STATE_IDLE) {
                    mBinding.viewpage2.post { startPlay(position) }
                } else {
                    pendingPlayTarget = position
                }
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                currentScrollState = state
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    mCurItem = mBinding.viewpage2.currentItem
                    dragStartItem = mCurItem
                    // 每次新拖动重新预测，方向去重不跨手势生效
                    mLastPredictedPos = -1
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    mLastPredictedPos = -1
                    isUserScroll.set(false)
                    resetAllControlViewAlpha(animated = true)
                    activeShortVideoControlView?.setAutoImmersiveEnabled(spUtil.showSysBar.not())
                    // settle 完成、旧页已离屏，此时移动 mVideoView 并切源
                    if (pendingPlayTarget >= 0) {
                        val target = pendingPlayTarget
                        pendingPlayTarget = -1
                        mBinding.viewpage2.post { startPlay(target) }
                    }
                } else {
                    isUserScroll.set(true)
                }
            }
        })
        mShortVideoAdapter.setOnShortVideoListener(object : ShortVideoControlView.OnShortVideoListener {

            override fun onLike(like: Boolean) {
                // onLike 在 adapter 层对所有页共用，理论上只在当前页触发；仍做下标防御避免越界
                val position = mCurPos
                if (position !in mVideoList.indices) {
                    return
                }
                val videoData = mVideoList[position]
                videoData.id = videoData.md5()
                // 收藏写入 favorite 表：
                // ① 先确保 video_data 父行存在，并把内存 mediaId 补成库内权威值（否则撞 media_data 外键）
                // ② 用 insertIgnore 去重——@Upsert 只按自增主键判定，id 恒为 0 会撞 video_id 唯一索引
                lifecycleScope.launch(Dispatchers.IO) {
                    var failure: Throwable? = null
                    try {
                        PlayHelper.ensureVideoParent(videoData)
                        val dao = DatabaseManager.favoriteDao
                        if (like) {
                            // media_id 是 FK → media_data，空串必然违约，无媒体上下文时降为 NULL
                            dao.insertIgnore(
                                Favorite(mediaId = videoData.mediaId.ifBlank { null }, videoId = videoData.id)
                            )
                        } else {
                            dao.deleteByVideoId(videoData.id)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failure = e
                    }
                    withContext(Dispatchers.Main) {
                        if (failure == null) {
                            // 回写内存 like，避免 RecyclerView 重新 bind 时用旧值覆盖心形
                            videoData.like = like
                            if (mCurPos == position) {
                                activeShortVideoControlView?.setLike(like)
                            }
                        } else {
                            // 写库失败必须可见：心形已先翻转，这里回滚避免 UI 与 DB 不一致
                            Log.e(TAG, "onLike failed: ${videoData.videoUrl}", failure)
                            if (mCurPos == position) {
                                activeShortVideoControlView?.setLike(!like)
                            }
                            ToastUtil.showToast(this@ShortVideoActivity, getString(R.string.favorite_failed))
                        }
                    }
                }
                logD("like", like)
            }

            override fun onComment() {
                if (!shortCommentDialog.isAdded) {
                    shortCommentDialog.arguments = Bundle().apply {
                        putString(ShortCommentDialog.KEY_VIDEO_ID, mVideoList[mCurPos].id)
                    }
                    shortCommentDialog.show(supportFragmentManager, shortCommentDialog.tag)
                }
            }

            override fun onMore() {
                shortSettingsDialog.show(supportFragmentManager, shortSettingsDialog.tag)
            }

            override fun onMoreAction() {
                if (!shortMoreDialog.isAdded) {
                    shortMoreDialog.setMoreActionListener(object : ShortMoreDialog.OnMoreActionListener {
                        override fun onRename() {
                            shortVideoFileActions.showRenameDialog()
                        }

                        override fun onDelete() {
                            shortVideoFileActions.showDeleteConfirmDialog()
                        }

                        override fun onShare() {
                            shortVideoFileActions.shareVideo()
                        }
                    })
                    shortMoreDialog.show(supportFragmentManager, shortMoreDialog.tag)
                }
            }

            override fun playNext() {
                val next = mCurPos + 1
                if (next !in mVideoList.indices) return
                // 只改变选择，由 onPageSelected 唯一驱动起播。
                mBinding.viewpage2.setCurrentItem(next, true)
            }

            override fun onChangeSysBar(show: Boolean) {
                // 系统栏始终隐藏(竖向全屏)，沉浸不再切换系统栏显隐
            }

            override fun onSingleTap() {
                // 沉浸体验:点击屏幕退出沉浸并重新计时
                exitAndRescheduleImmersive()
            }

            override fun onBack() {
                finish()
            }

        })

        // ViewPage2内部是通过RecyclerView去实现的，它位于ViewPager2的第0个位置
        mViewPagerImpl = mBinding.viewpage2.getChildAt(0) as RecyclerView
        //mViewPagerImpl.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING)
    }

    private var edgeTouchActive = false
    private val edgeSizePx by lazy { (resources.displayMetrics.density *16).toInt() }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val h = mBinding.root.height
                edgeTouchActive = h > 0 && (ev.y < edgeSizePx || ev.y > h - edgeSizePx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (edgeTouchActive) {
                    edgeTouchActive = false
                    return false
                }
            }
        }
        if (edgeTouchActive) {
            return false
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyPlaybackCoreFor(videoBean: VideoData) {
        applyPlaybackCoreForTo(mVideoView, videoBean)
    }

    private fun applyPlaybackCoreForTo(videoView: CustomVideoView, videoBean: VideoData) {
        if (isSmbVideo(videoBean)) {
            applyShortVideoRenderFactoryTo(videoView)
            DyPlayerCoreRegistry.applyCore(videoView, DyPlayerCore.EXO, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
            if (!spUtil.sortRender) {
                videoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            }
            return
        }
        applyConfiguredPlaybackCoreTo(videoView)
    }

    private fun applyConfiguredPlaybackCore() {
        applyConfiguredPlaybackCoreTo(mVideoView)
    }

    private fun applyConfiguredPlaybackCoreTo(videoView: CustomVideoView) {
        val currentCore = DyPlayerCoreRegistry.resolveCore(spUtil.shortDyPlayerCore)
        if (currentCore == DyPlayerCore.MPV) {
            if (!spUtil.labMpvSpecialRender) {
                videoView.setRenderViewFactory(TextureRenderViewFactory.create())
            }
            DyPlayerCoreRegistry.applyCore(videoView, spUtil.shortDyPlayerCore, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
            videoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            return
        }
        if (spUtil.sortRender) {
            applyShortVideoRenderFactoryTo(videoView)
        }
        DyPlayerCoreRegistry.applyCore(videoView, spUtil.shortDyPlayerCore, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
        if (!spUtil.sortRender && !spUtil.labMpvSuperResolution) {
            videoView.setRenderViewFactory(TextureRenderViewFactory.create())
            videoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
        }
    }

    private fun applyShortVideoRenderFactory() {
        applyShortVideoRenderFactoryTo(mVideoView)
    }

    private fun applyShortVideoRenderFactoryTo(videoView: CustomVideoView) {
        if (spUtil.sortRender) {
            videoView.setRenderViewFactory(ShortVideoRenderViewFactory.create())
        } else {
            videoView.setRenderViewFactory(TextureRenderViewFactory.create())
        }
    }

    private fun isSmbVideo(videoBean: VideoData): Boolean {
        return videoBean.type == StorageType.SMB || videoBean.videoUrl.startsWith("smb://", ignoreCase = true)
    }

    /**
     * 播放带凭据的远程视频（WebDAV/SMB 共用）。
     * 决策8：headers 不落库——调用方列表准备时已填 token 则立即起播；
     * 缺 token（如播放列表/历史等 DB 加载路径）先从 source_data 现算回填再起播。
     */
    private suspend fun resolvePlaySource(videoBean: VideoData): Pair<String, Map<String, String>?> {
        if (videoBean.type == StorageType.WEBDAV || videoBean.type == StorageType.SMB) {
            if (videoBean.getToken().isNullOrBlank()) {
                VideoDataBinder.bindTokens(videoBean, mediaData)
            }
        }
        // 302 解析结果只用于本次起播，不回写 videoBean.videoUrl：
        // 回写会污染数据层（进度 key 按 url.hashCode、is302 判断失准、同实体跨请求串扰）。
        // 每次起播重新解析的代价是一次轻量 HTTP HEAD，远小于数据污染风险。
        val url = if (videoBean.type != StorageType.WEBDAV && videoBean.videoUrl.startsWith("http")) {
            HttpUtil.getRedirectUrl(videoBean.videoUrl)
        } else {
            videoBean.videoUrl
        }
        return url to videoBean.headers
    }

    private fun stableMediaId(videoBean: VideoData): String {
        if (videoBean.id.isBlank()) videoBean.id = videoBean.md5()
        return videoBean.id
    }

    private fun currentMediaId(): String? {
        return mVideoList.getOrNull(mCurPos)?.let { stableMediaId(it) }
    }

    private fun backendKey(videoBean: VideoData): String {
        if (isSmbVideo(videoBean)) return "exo-smb"
        return listOf(
            DyPlayerCoreRegistry.resolveCore(spUtil.shortDyPlayerCore).name,
            spUtil.labMpvSpecialRender,
            spUtil.labMpvSuperResolution,
            spUtil.sortRender
        ).joinToString("|")
    }

    private var activeBackendKey: String? = null

    // ---- 双 VideoView 预加载方法 ----

    private fun cancelPreload() {
        mNextPreloadJob?.cancel()
        mNextPreloadJob = null
        mNextPreloadTargetPos = -1
        pendingPreloadNext?.let { mBinding.viewpage2.removeCallbacks(it) }
        pendingPreloadNext = null
        mNextVideoView?.let { view ->
            // 预载 view 可能已经 addView 到邻页 holder 的 playerContainer。
            // 只 release 不摘除会残留一个空 View，后续主播放器以 index=0 插入时被它盖住。
            removeViewFormParent(view)
            view.release()
        }
        mNextVideoView = null
        mNextPreloadedPos = -1
    }

    /**
     * 拖动过程中按方向锁定预载目标：
     * 回滑时 ViewPager2 的 position = dragStartItem - 1，目标即 position；
     * 前滑时 position = dragStartItem 且 offset 递增，目标为 position + 1。
     * 只在 DRAGGING 且方向首次变化时触发，避免每帧 onPageScrolled 反复 cancel / 重建解码器。
     */
    private fun predictPreloadByDirection(position: Int, positionOffset: Float, dragStartItem: Int) {
        if (currentScrollState != ViewPager2.SCROLL_STATE_DRAGGING) return
        if (positionOffset <= 0f) return
        val target = when {
            position < dragStartItem -> position
            position == dragStartItem -> position + 1
            else -> return
        }
        if (target !in mVideoList.indices || target == mCurPos) return
        if (target == mLastPredictedPos) return
        mLastPredictedPos = target
        logAndCache(TAG, "D", "predict dir: pos=$target")
        // 方向预测立即生效，取消尚未执行的归位预载，避免它稍后覆盖已预载好的目标
        pendingPreloadNext?.let { mBinding.viewpage2.removeCallbacks(it) }
        pendingPreloadNext = null
        preloadAt(target)
    }

    /**
     * 归位：滑动结束后预备再下一页。用单例 pending 合并连续切页，
     * 快滑时只保留最后一次初始化；方向预测的预载是即时的，不受此延迟影响。
     */
    private fun preloadNext() {
        pendingPreloadNext?.let { mBinding.viewpage2.removeCallbacks(it) }
        pendingPreloadNext = Runnable {
            pendingPreloadNext = null
            preloadAt(mCurPos + 1)
        }.also { mBinding.viewpage2.postDelayed(it, preloadMergeDelayMs) }
    }

    /** 解码器初始化失败后冷却，避免同一个源在快滑中被反复重试。 */
    private fun isPreloadCoolingDown(url: String): Boolean {
        val failedAt = preloadFailureAt[url] ?: return false
        if (SystemClock.elapsedRealtime() - failedAt < preloadCooldownMs) return true
        preloadFailureAt.remove(url)
        return false
    }

    /**
     * 按目标位置预备一个静音 VideoView：setUrl + start 到 STATE_PLAYING 后立刻 pause，
     * 让渲染视图保留首帧并挂到目标 holder；切页时 startPlay 直接 swap，不走重建管线。
     * 方向预测与归位共用此路径，同一时刻只存在一个预载实例。
     */
    private fun preloadAt(targetPos: Int) {
        if (targetPos !in mVideoList.indices || targetPos == mCurPos) return
        if (mNextPreloadedPos == targetPos || mNextPreloadTargetPos == targetPos) return

        val nextPos = targetPos
        val videoBean = mVideoList[nextPos]
        val targetUrl = videoBean.videoUrl

        // A: 同规格近期预载失败过；B: 该 url 失败冷却期内 —— 都不再初始化解码器
        if (PreloadCapabilityGuard.isKnownUnsupported(targetUrl)) {
            logAndCache(TAG, "D", "preload skip: failed spec pos=$nextPos")
            return
        }
        if (isPreloadCoolingDown(targetUrl)) {
            logAndCache(TAG, "D", "preload cooldown: pos=$nextPos")
            return
        }
        // 不按静态规格跳过：1440x2560 这类边界规格实测大多能预载成功，
        // 一刀切会让回滑 / 慢滑丢首帧。只有真正失败过的规格 / url 才跳过。

        cancelPreload()

        logAndCache(TAG, "D", "preload start: pos=$nextPos")
        val nextView = CustomVideoView(this)
        nextView.setMute(true)
        nextView.setEnableAudioFade(false)
        nextView.setLooping(PlayerInitializer.Player.shortAutoNext.not())

        applyPlaybackCoreForTo(nextView, videoBean)

        nextView.addOnStateChangeListener(object : OnStateChangeListener {
            override fun onPlayerStateChanged(playerState: Int) {}

            override fun onPlayStateChanged(playState: Int) {
                if (mNextVideoView !== nextView) return
                if (playState == VideoView.STATE_PLAYING) {
                    nextView.pause()
                    nextView.setMute(true)
                    mNextPreloadedPos = nextPos
                    logAndCache(TAG, "D", "preload ready: pos=$nextPos")
                }
                if (playState == VideoView.STATE_ERROR) {
                    mNextPreloadedPos = -1
                    mNextPreloadTargetPos = -1
                    preloadFailureAt[videoBean.videoUrl] = SystemClock.elapsedRealtime()
                    PreloadCapabilityGuard.markFailure(videoBean.videoUrl)
                    logAndCache(TAG, "E", "preload error: pos=$nextPos, cooldown=${preloadCooldownMs}ms")
                    // 失败实例同样要摘除并释放：否则残留 View 会盖住后续主播放器，
                    // 还会继续占着一个解码器（快滑并发时容易触发 HEVC 解码器初始化失败）。
                    // 延迟到状态回调之外再 release，避免在 onPlayStateChanged 内销毁自身。
                    mBinding.viewpage2.post {
                        if (mNextVideoView === nextView) cancelPreload()
                    }
                }
            }
        })

        mNextVideoView = nextView
        mNextPreloadTargetPos = nextPos

        val nextHolder = findViewHolderByPosition(nextPos)
        if (nextHolder != null) {
            removeViewFormParent(nextView)
            nextHolder.playerContainer.addView(nextView, 0)
        }

        mNextPreloadJob = lifecycleScope.launch {
            val source = try {
                withContext(Dispatchers.IO) {
                    val resolved = resolvePlaySource(videoBean)
                    // A: 记录规格，用于诊断、失败规格传播、快滑阶段跳过依据
                    PreloadCapabilityGuard.inspect(resolved.first)
                    resolved
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logAndCache(TAG, "E", "preload resolve failed: ${e.message}")
                mNextPreloadTargetPos = -1
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (mNextVideoView !== nextView) return@withContext
                // inspect 之后可能命中「同规格近期失败」，此时不再初始化解码器
                if (PreloadCapabilityGuard.isKnownUnsupported(source.first)) {
                    logAndCache(TAG, "D", "preload skip: failed spec pos=$nextPos")
                    mBinding.viewpage2.post {
                        if (mNextVideoView === nextView) cancelPreload()
                    }
                    return@withContext
                }
                if (!spUtil.shortAutoLoadProgress) {
                    (VideoViewManager.getConfig().mProgressManager as? ProgressManagerImpl)
                        ?.clearSavedProgressByUrl(videoBean.videoUrl)
                }
                nextView.setUrl(source.first, source.second?.toMutableMap())
                nextView.start()
            }
        }
    }

    private fun swapToPreloaded(position: Int) {
        val nextView = mNextVideoView ?: return
        if (mNextPreloadedPos != position) return

        val oldView = mVideoView

        val previousPosition = mCurPos
        if (previousPosition in mVideoList.indices) {
            saveProgressAt(previousPosition, force = true)
        }

        oldView.pause()
        oldView.setMute(true)
        oldView.setEnableAudioFade(false)

        activeShortVideoControlView?.let { old ->
            old.setOnVideoTransformChangedListener(null)
            old.hidePlayButton()
            mController.removeControlComponent(old)
        }
        activeShortVideoControlView = null
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        subtitleControlView.clearSubtitleLayoutBounds()

        mNextVideoView = null
        mNextPreloadedPos = -1
        mNextPreloadTargetPos = -1
        mVideoView = nextView

        playJob?.cancel()
        val mediaId = stableMediaId(mVideoList[position])
        val request = playRequests.begin(mediaId)

        nextView.setMute(false)
        nextView.setEnableAudioFade(spUtil.audioFadeEnabled)
        nextView.setAudioFadeInDuration(spUtil.audioFadeInDuration)
        nextView.setAudioFadeOutDuration(spUtil.audioFadeOutDuration)
        nextView.setLooping(PlayerInitializer.Player.shortAutoNext.not())

        // mController 本身是一个 View，构造时已通过 setVideoController 挂到 oldView 的
        // playerContainer。Doikki 的 setVideoController 只移除「自己」容器里的旧 controller，
        // 不会替我们从别的 VideoView 解绑；不先摘掉就会触发
        // "The specified child already has a parent"。传 null 会 removeView(mController)，
        // 随后 nextView.setVideoController 重新 setMediaPlayer 绑定 ControlWrapper。
        oldView.setVideoController(null)
        nextView.setVideoController(mController)
        nextView.addOnStateChangeListener(createMainStateListener(nextView))

        val holder = findViewHolderByPosition(position)
        if (holder != null) {
            // 预载时邻页 holder 可能尚未就绪（nextView 没有宿主），或 holder 被 ViewPager2 复用；
            // 切换时确保宿主就是当前页的 playerContainer，否则会出现有声音无画面。
            if (nextView.parent !== holder.playerContainer) {
                removeViewFormParent(nextView)
                holder.playerContainer.addView(nextView, 0)
            }
            val displayTitle = formatShortTitle(mVideoList[position].name)
            mController.setTitle(displayTitle)
            holder.shortVideoControlView.setTitle(displayTitle)
            holder.shortVideoControlView.setPage(position, mVideoList.size)
            mController.addShortVideoControlView(holder.shortVideoControlView)
            holder.shortVideoControlView.setOnVideoTransformChangedListener {
                updateSubtitleDocking()
            }
            holder.shortVideoControlView.setAutoImmersiveEnabled(spUtil.showSysBar.not())
            holder.shortVideoControlView.resetScale(1.0f)
            holder.shortVideoControlView.hidePlayButton()
            activeShortVideoControlView = holder.shortVideoControlView
        }

        mCurPos = position
        pagerSelectedItem = position
        activeBackendKey = backendKey(mVideoList[position])

        cancelImmersive()
        if (ShortVideoControlView.isGlobalImmersive()) {
            activeShortVideoControlView?.enterImmersive()
        } else {
            scheduleImmersive()
        }

        saveMediaInfo(mVideoList[position].videoUrl)
        mediaPlaybackRecorder.cancelLastPlayedUpdate()

        nextView.start()
        attachSubtitle()

        logAndCache(TAG, "D", "swapToPreloaded: pos=$position")

        mBinding.viewpage2.postDelayed({
            if (mVideoView === nextView) {
                oldView.release()
                removeViewFormParent(oldView)
            }
        }, mNextPreloadReleaseDelayMs)
    }

    private fun startPlay(position: Int, force: Boolean = false) {
        if (position !in mVideoList.indices) return

        if (!force && mNextPreloadedPos == position && mNextVideoView != null) {
            swapToPreloaded(position)
            return
        }

        val videoBean = mVideoList[position]
        val mediaId = stableMediaId(videoBean)
        val existing = playRequests.current
        // 幂等保护：同一媒体且仍在播放时不重复起播；但 ERROR 态必须放行，
        // 否则网络抖动后用户无法在同页重试（hasPlayer() 仍 true 会直接 return）。
        if (!force && existing?.mediaId == mediaId && mCurPos == position && mVideoView.hasPlayer()
            && mVideoView.currentPlayState != VideoView.STATE_ERROR) {
            return
        }

        // 跨页跳转 / 回滑 / 重试走的是重建路径，旧的预载实例已不对应目标页：
        // 先释放，避免残留播放器与 Surface 占用，也避免之后误命中过期预载。
        cancelPreload()

        val holder = findViewHolderByPosition(position)
        if (holder == null) {
            mBinding.viewpage2.postDelayed({
                if (playRequests.current?.mediaId == mediaId || playRequests.current == null) {
                    startPlay(position, force)
                }
            }, 50)
            return
        }

        // 上一集进度必须在 mCurPos 改变、player reset/release 之前落盘。
        val previousPosition = mCurPos
        if (previousPosition in mVideoList.indices) {
            saveProgressAt(previousPosition, force = true)
        }
        val previousUrl = mVideoList.getOrNull(previousPosition)?.videoUrl
        val previousPos = if (::mVideoView.isInitialized) mVideoView.currentPosition else 0L
        val previousDuration = if (::mVideoView.isInitialized) mVideoView.duration else 0L

        playJob?.cancel()
        val request = playRequests.begin(mediaId)
        mediaPlaybackRecorder.cancelLastPlayedUpdate()
        mVideoView.pause()

        // 显式替换活跃组件，不依赖 release() 的 IDLE 回调；解析中连续切页也不会残留监听。
        activeShortVideoControlView?.let { old ->
            old.setOnVideoTransformChangedListener(null)
            old.hidePlayButton()
            mController.removeControlComponent(old)
        }
        activeShortVideoControlView = null
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        subtitleControlView.clearSubtitleLayoutBounds()

        val nextBackend = backendKey(videoBean)
        if (activeBackendKey != null && activeBackendKey != nextBackend) {
            // 后端/画质配置改变时不能复用不同内核的 player。
            mVideoView.release()
        }
        activeBackendKey = nextBackend
        applyPlaybackCoreFor(videoBean)

        if (previousUrl != null && previousDuration > 0 &&
            previousPos >= previousDuration - COMPLETION_THRESHOLD
        ) {
            (VideoViewManager.getConfig().mProgressManager as? ProgressManagerImpl)
                ?.clearSavedProgressByUrl(previousUrl)
        }
        if (!spUtil.shortAutoLoadProgress) {
            (VideoViewManager.getConfig().mProgressManager as? ProgressManagerImpl)
                ?.clearSavedProgressByUrl(videoBean.videoUrl)
        }


        // D1: 移动前禁用视频输出，清旧帧避免 surface 重建时左右条残影；loadfile 后自动恢复

        mVideoView.suppressVideoForSwitch()
        removeViewFormParent(mVideoView)
        holder.playerContainer.addView(mVideoView, 0)


        val displayTitle = formatShortTitle(videoBean.name)
        mController.setTitle(displayTitle)
        holder.shortVideoControlView.setTitle(displayTitle)
        holder.shortVideoControlView.setPage(position, mVideoList.size)
        mController.addShortVideoControlView(holder.shortVideoControlView)
        holder.shortVideoControlView.setOnVideoTransformChangedListener {
            updateSubtitleDocking()
        }
        holder.shortVideoControlView.setAutoImmersiveEnabled(spUtil.showSysBar.not())
        holder.shortVideoControlView.resetScale(1.0f)
        holder.shortVideoControlView.hidePlayButton()
        activeShortVideoControlView = holder.shortVideoControlView
        mCurPos = position
        pagerSelectedItem = position
        cancelImmersive()
        if (ShortVideoControlView.isGlobalImmersive()) {
            // 沉浸中切页保持隐藏，滑动不退出沉浸，仅单击才退出
            holder.shortVideoControlView.enterImmersive()
        } else {
            scheduleImmersive()
        }
        saveMediaInfo(videoBean.videoUrl)
        lifecycleScope.launch {
            val commentCount = withContext(Dispatchers.IO) {
                shortCommentController.loadCommentCount(videoBean.id)
            }
            if (playRequests.isCurrent(request) && currentMediaId() == mediaId) {
                holder.shortVideoControlView.setCommentCount(commentCount)
            }
        }

        playlistPlayJob(videoBean, request)
    }

    private fun playlistPlayJob(videoBean: VideoData, request: PlayRequest) {
        val mediaId = request.mediaId
        playJob = lifecycleScope.launch {
            val source = try {
                withContext(Dispatchers.IO) { resolvePlaySource(videoBean) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logAndCache(TAG, "E", "解析播放地址失败: ${e.message}")
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (!playRequests.isCurrent(request) || isFinishing || isDestroyed) return@withContext
                if (currentMediaId() != mediaId) return@withContext
                logAndCache(TAG, "D", "提交播放: ${request.mediaId}, url=${source.first}, type=${videoBean.type}")
                mVideoView.setUrl(source.first, source.second?.toMutableMap())
                // 单一提交点：MPV 同后端复用实例，其他后端仍走 release+start。
                mVideoView.startOrReplace()

                // exo 内核 startOrReplace 走 release+start，release 触发 STATE_IDLE，
                // BaseVideoController.onPlayStateChanged(STATE_IDLE) 会调 removeAllDissociateComponents()
                // 移除 isDissociate=true 的 controlView，导致后续 STATE_PAUSED 不通知 controlView，
                // playBtn 保持 GONE。这里在 startOrReplace 后重新添加，确保 controlView 接收后续状态。
                // LinkedHashMap.put 对已存在的 key 只更新值，mpv 内核下重复添加也安全。
                activeShortVideoControlView?.let { cv ->
                    mController.addShortVideoControlView(cv)
                }
            }
        }
    }

    private fun findViewHolderByPosition(position: Int): ShortVideoAdapter.ViewHolder? {
        if (!::mViewPagerImpl.isInitialized) return null
        for (i in 0 until mViewPagerImpl.childCount) {
            val viewHolder = mViewPagerImpl.getChildAt(i).tag as? ShortVideoAdapter.ViewHolder ?: continue
            if (viewHolder.bindingAdapterPosition == position) return viewHolder
        }
        return null
    }

    private fun attachSubtitle() {
        if (!::subtitleControlView.isInitialized || mCurPos !in mVideoList.indices) {
            return
        }
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        val request = playRequests.current ?: return
        val videoData = mVideoList[mCurPos]
        lifecycleScope.launch(Dispatchers.IO) {
            val subtitleFile = PlayHelper.resolveSubtitleFile(this@ShortVideoActivity, videoData)
            withContext(Dispatchers.Main) {
                if (!playRequests.isCurrent(request) || request.mediaId != stableMediaId(videoData)) {
                    return@withContext
                }
                if (subtitleFile != null && subtitleFile.exists() && subtitleFile.isFile) {
                    logAndCache(TAG, "D", "字幕加载: ${subtitleFile.path}")
                    addExternalSubtitle(subtitleFile)
                    updateSubtitleDocking()
                }
            }
        }
    }

    private fun addExternalSubtitle(file: File): Boolean {
        // 外挂字幕接入走共享 helper，长短视频页保持一致的选轨与 cue 绑定行为。
        return ExternalSubtitleLoader.addSubtitle(
            externalTrackController = getExternalTrackController(),
            subtitleCueProvider = getSubtitleCueProvider(),
            subtitleControlView = subtitleControlView,
            uri = file.toUri(),
            title = file.name
        )
    }

    private fun clearSubtitleCueListener() {
        ExternalSubtitleLoader.clearCueListener(getSubtitleCueProvider())
    }

    private fun updateSubtitleDocking() {
        if (!::subtitleDockController.isInitialized) {
            return
        }
        // Activity 只决定刷新时机，几何判断与 trace 输出都由 controller 负责。
        subtitleDockController.update()
    }

    private fun applySubtitleTextSizeForPlayerState(playerState: Int) {
        if (!::subtitleDockController.isInitialized) {
            return
        }
        subtitleDockController.applyTextSizeForPlayerState(
            playerState = playerState,
            normalScale = subtitleScaleNormal,
            fullScreenScale = subtitleScaleFullScreen
        )
    }

    private fun findCurrentShortVideoControlView(): ShortVideoControlView? {
        return findShortVideoControlViewByPosition(mCurPos)
    }

    private fun findShortVideoControlViewByPosition(position: Int): ShortVideoControlView? {
        return (mViewPagerImpl.findViewHolderForAdapterPosition(position) as? ShortVideoAdapter.ViewHolder)?.shortVideoControlView
    }

    /**
     * 滑动期间联动「参与滑动的两页」的控件透明度，让画面优先。
     *
     * ViewPager2 的 onPageScrolled 语义：前滑时 position = dragStartItem（offset 0→1），
     * 回滑时 position = dragStartItem - 1（offset 1→0），因此参与滑动的两页恒为
     * position 与 position + 1。两页同步跟随滑动进度变淡，避免只压暗当页、
     * 而进入页的顶部返回 / 电量时间仍保持全亮（视觉上最扎眼）。
     * 回弹或 settle 结束后由 resetAllControlViewAlpha 恢复。
     */
    private fun updateControlAlphaForPageScroll(position: Int, positionOffset: Float, dragStartItem: Int) {
        if (positionOffset <= 0f) {
            // 前滑回弹到起点：两页一起恢复，避免进入页残留半透明
            setControlAlphaForPosition(dragStartItem, CONTROL_ALPHA_FULL)
            setControlAlphaForPosition(dragStartItem + 1, CONTROL_ALPHA_FULL)
            return
        }

        val progress = if (position == dragStartItem) {
            positionOffset
        } else {
            1f - positionOffset
        }
        val ratio = (progress / CONTROL_ALPHA_DIM_PROGRESS).coerceIn(0f, 1f)
        val alpha = CONTROL_ALPHA_FULL - ratio * (CONTROL_ALPHA_FULL - CONTROL_ALPHA_DIMMED)
        setControlAlphaForPosition(position, alpha)
        setControlAlphaForPosition(position + 1, alpha)
    }

    private fun setControlAlphaForPosition(position: Int, alpha: Float) {
        val view = findShortVideoControlViewByPosition(position) ?: return
        view.animate().cancel()
        view.alpha = alpha
    }

    private fun resetAllControlViewAlpha(animated: Boolean) {
        val count = mViewPagerImpl.childCount
        for (i in 0 until count) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as? ShortVideoAdapter.ViewHolder ?: continue
            val view = viewHolder.shortVideoControlView
            view.animate().cancel()
            if (animated) {
                view.animate()
                    .alpha(CONTROL_ALPHA_FULL)
                    .setDuration(CONTROL_ALPHA_RESTORE_DURATION)
                    .start()
            } else {
                view.alpha = CONTROL_ALPHA_FULL
            }
        }
    }

    fun addData(list: List<VideoData>) {
        val start = mVideoList.size
        mVideoList.addAll(list)
        // 使用插入通知，不能对已存在数据发 Change 造成 RecyclerView 状态不一致。
        mShortVideoAdapter.notifyItemRangeInserted(start, list.size)
    }

    /** 删除 UI 条目；调用方必须提供稳定媒体 ID，避免等待期间翻页导致位置失效。 */
    fun removeItemById(mediaId: String) {
        val position = mVideoList.indexOfFirst { stableMediaId(it) == mediaId }
        if (position < 0) return
        val deletingCurrent = position == mCurPos
        val deletingBeforeCurrent = position < mCurPos

        mVideoList.removeAt(position)
        mShortVideoAdapter.notifyItemRemoved(position)
        if (mVideoList.isNotEmpty()) {
            mShortVideoAdapter.notifyItemRangeChanged(
                position,
                (mVideoList.size - position).coerceAtLeast(0)
            )
        }

        if (!deletingCurrent) {
            if (deletingBeforeCurrent) {
                mCurPos--
                pagerSelectedItem = mCurPos
                // 非当前项删除不得释放/重建当前 player，只同步 Pager 下标。
                if (mCurPos in mVideoList.indices && mBinding.viewpage2.currentItem != mCurPos) {
                    mBinding.viewpage2.setCurrentItem(mCurPos, false)
                }
            }
            return
        }

        playJob?.cancel()
        playRequests.invalidate()
        cancelPreload()
        activeShortVideoControlView?.let { old ->
            old.setOnVideoTransformChangedListener(null)
            mController.removeControlComponent(old)
        }
        activeShortVideoControlView = null
        mVideoView.release()
        AppUtil.removeViewFormParent(mVideoView)

        if (mVideoList.isEmpty()) {
            finish()
            return
        }
        val newPos = position.coerceAtMost(mVideoList.size - 1)
        mCurPos = newPos
        pagerSelectedItem = newPos
        mBinding.viewpage2.post {
            if (mBinding.viewpage2.currentItem != newPos) {
                mBinding.viewpage2.setCurrentItem(newPos, false)
            }
            // 同一下标可能已经是不同媒体，必须显式 force 一次。
            startPlay(newPos, force = true)
        }
    }

    private fun onPlayStart() {
        val position = mCurPos
        mBinding.root.post {
            if (onHistory) {
                return@post
            }
            val videoBean = mVideoList[position]
            PlayHelper.saveHistory(videoBean, historySource())
        }
        startProgressTicker()
        doScreenShot(position)
    }

    /**
     * 历史来源按进入本页的媒体上下文派生：
     * 媒体库进入 → lib:{mediaId}（归入历史页对应分组）；播放列表 → pl:{id}；
     * 文件浏览/喜欢聚合等无 mediaData 的入口 → standalone。
     * 与 LongVideoActivity 的 HistorySource.fromMediaData 取值同源，
     * 保证同一视频在长/短视频页之间切换播放写的是同一条 (video_id, source_key)。
     */
    private fun historySource(): HistorySource = HistorySource.fromMediaData(mediaData)

    // ---- 播放进度落盘 ----
    // 短视频页没有弹幕控制器，永远走不到 saveInfo/updateInfo，
    // 若不单独落库 play_history 进度/时长将恒为 0（长视频有真实进度，两侧表现不一致）。
    // 这里用独立 ticker 周期写入：与长视频一致受 PlayHelper 的 6 秒节流约束。

    private val progressHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var progressTicking = false

    private val progressTicker = object : Runnable {
        override fun run() {
            saveProgress(force = false)
            progressHandler.postDelayed(this, PROGRESS_TICK_MS)
        }
    }

    private fun startProgressTicker() {
        if (progressTicking) return
        progressTicking = true
        progressHandler.removeCallbacks(progressTicker)
        progressHandler.postDelayed(progressTicker, PROGRESS_TICK_MS)
    }

    private fun stopProgressTicker() {
        progressHandler.removeCallbacks(progressTicker)
        progressTicking = false
    }

    /** 把指定页位置/时长写入 play_history（不写 PlayInfo 文件，避免覆盖长视频弹幕轨道）。 */
    private fun saveProgressAt(position: Int, force: Boolean) {
        if (onHistory || !::mVideoView.isInitialized) return
        if (position !in mVideoList.indices) return
        PlayHelper.updateHistoryProgress(
            mVideoList[position],
            historySource(),
            mVideoView.currentPosition,
            mVideoView.duration.takeIf { it > 0 } ?: 0L,
            force
        )
    }

    private fun saveProgress(force: Boolean) = saveProgressAt(mCurPos, force)

    fun doScreenShot(position: Int) {
        val request = playRequests.current ?: return
        mBinding.root.postDelayed({
            if (!playRequests.isCurrent(request) || position != mCurPos) return@postDelayed
            val videoData = mVideoList.getOrNull(position) ?: return@postDelayed
            if (videoData.videoUrl.isLocal()) return@postDelayed
            val target = File(
                externalCacheDir,
                ".thumb/${videoData.md5()}.${AppUtil.THUMB_TYPE}"
            )
            lifecycleScope.launch(Dispatchers.IO) {
                if (target.exists() && target.length() >= 20480) return@launch
                var temp: File? = null
                try {
                    // 截图/解码/压缩/写盘全部离开主线程；null 是明确失败，不再 NPE 后静默。
                    val bitmap = mVideoView.doScreenShot() ?: return@launch
                    if (!playRequests.isCurrent(request)) return@launch
                    target.parentFile?.mkdirs()
                    temp = File.createTempFile("thumb_", ".tmp", target.parentFile)
                    FileOutputStream(temp).use { fos ->
                        if (!bitmap.compress(AppUtil.COMPRESS_FORMAT, 70, fos)) {
                            throw IllegalStateException("bitmap compress failed")
                        }
                        fos.flush()
                    }
                    if (target.exists()) target.delete()
                    if (!temp.renameTo(target)) {
                        throw IllegalStateException("rename thumbnail failed")
                    }
                    temp = null
                } catch (e: Exception) {
                    Log.d(TAG, "doScreenShot failed: ${e.message}")
                } finally {
                    temp?.delete()
                    if (target.exists() && target.length() == 0L) target.delete()
                }
            }
        }, 1000)
    }

    private fun saveMediaInfo(url: String) {
        mediaPlaybackRecorder.saveLastPlayedUrl(mediaData, url)
    }

    private fun scheduleMediaLastPlayedUpdate(position: Int) {
        mediaPlaybackRecorder.scheduleLastPlayedAtUpdate(
            scope = lifecycleScope,
            mediaData = mediaData,
            position = position,
            currentPosition = { mCurPos },
            currentPlayState = { mVideoView.currentPlayState }
        )
    }

    override fun onResume() {
        super.onResume()
        hideSysBar()
        if (::mVideoView.isInitialized) {
            mVideoView.resume()
            scheduleImmersive()
        }
        timerCloseController.onResume()
        // 注册超分开关广播接收器（让设置页和 adb 都能在短视频页切换 FSR）
        registerSuperResolutionReceiver()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::mVideoView.isInitialized) {
            return
        }
        val playerState = if (mVideoView.isFullScreen || newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            VideoView.PLAYER_FULL_SCREEN
        } else {
            VideoView.PLAYER_NORMAL
        }
        applySubtitleTextSizeForPlayerState(playerState)
        updateSubtitleDocking()
    }

    override fun onPause() {
        super.onPause()
        if (::mVideoView.isInitialized) {
            mVideoView.pause()
        }
        mNextVideoView?.pause()
        timerCloseController.onPause()
    }

    override fun onStart() {
        super.onStart()
        isForeground = true
        // 短视频不做后台播放：回到前台时若处于暂停态，恢复播放。
        if (::mVideoView.isInitialized && !userInitiatedExit && mVideoView.hasPlayer() && !mVideoView.isPlaying) {
            mVideoView.start()
        }
        if (::mVideoView.isInitialized && mVideoView.isPlaying) {
            scheduleImmersive()
        }
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
        cancelImmersive()
        if (userInitiatedExit) {
            userInitiatedExit = false
            return
        }
        if (isChangingConfigurations) return
        playRequests.invalidate()
        playJob?.cancel()
        cancelPreload()
        if (::mVideoView.isInitialized && mVideoView.hasPlayer()) {
            mVideoView.pause()
        }
        stopProgressTicker()
        saveProgress(force = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        cancelImmersive()
        immersiveHandler.removeCallbacksAndMessages(null)
        playRequests.invalidate()
        playJob?.cancel()
        cancelPreload()
        unregisterSuperResolutionReceiver()
        stopProgressTicker()
        saveProgress(force = true)
        mediaPlaybackRecorder.release()
        timerCloseController.release()
        activeShortVideoControlView?.setOnVideoTransformChangedListener(null)
        if (::mVideoView.isInitialized) {
            mVideoView.release()
        }
        clearSurfaceTrace()
        playbackLogCache.clear()
    }

    private fun closeApp() {
        cancelPreload()
        if (::mVideoView.isInitialized) {
            mVideoView.release()
        }
        finishAffinity()
    }

}
