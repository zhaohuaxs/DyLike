package me.lingci.dy.player.ui.short_video

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityShortVideoBinding
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.DyPlayerCoreRegistry
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.AppUtil.removeViewFormParent
import me.lingci.dy.player.util.ExternalSubtitleLoader
import me.lingci.dy.player.util.MediaPlaybackRecorder
import me.lingci.dy.player.util.MediaManger
import me.lingci.dy.player.util.PlaybackErrorLogger
import me.lingci.dy.player.util.PlaybackLogCache
import me.lingci.dy.player.util.PlaybackTraceHelper
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.ShortTitleFormatter
import me.lingci.dy.player.util.VideoListTempStore
import me.lingci.dy.player.view.ShortVideoControlView
import me.lingci.dy.player.view.ShortVideoController
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.HttpUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.createNew
import me.lingci.lib.base.util.isLocal
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.md5
import me.lingci.lib.base.util.safeGetParcelable
import me.lingci.lib.base.util.safeGetParcelableArrayList
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.widget.component.SubtitleControlView
import me.lingci.lib.player.widget.render.ShortVideoRenderViewFactory
import me.lingci.lib.player.widget.videoview.CustomVideoView
import xyz.doikki.videoplayer.player.BaseVideoView.OnStateChangeListener
import xyz.doikki.videoplayer.player.VideoView
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
        private const val CONTROL_ALPHA_RESTORE_VISIBLE_PROGRESS = 0.5f
        private const val CONTROL_ALPHA_RESTORE_DURATION = 160L

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

    /** 沉浸体验定时器(Activity 级,确保操作的是活跃的 ControlView)。 */
    private val immersiveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var immersiveScheduled = false
    private val enterImmersiveRunnable = Runnable {
        immersiveScheduled = false
        // 暂停态不自动进沉浸(用户主动暂停,需保留控制层);恢复播放后由 onPlayStateChanged 重新计时
        if (::mVideoView.isInitialized && mVideoView.isPlaying) {
            activeShortVideoControlView?.enterImmersive()
        }
    }
    private fun scheduleImmersive() {
        if (!spUtil.showSysBar.not()) return
        immersiveHandler.removeCallbacks(enterImmersiveRunnable)
        // 视频暂停时不调度进沉浸(用户主动暂停,需保留控制层)
        if (::mVideoView.isInitialized && !mVideoView.isPlaying) return
        immersiveScheduled = true
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
            removeItem = ::removeItem
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
        mBinding = ActivityShortVideoBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        init()
    }

    override fun onNewIntent(intent: Intent?) {
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
        initData(0, false, false, list)
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
            initData(index, customRandom, random, it)
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

    private fun initData(
        index: Int,
        customRandom: Boolean,
        random: Boolean,
        list: MutableList<VideoData>
    ) {
        //val map = JsonUtil.toList<VideoData>(spUtil.likeJson!!).associateBy { it.id }
        val ids = JsonUtil.toList<VideoData>(spUtil.likeJson!!).map { it.md5() }
        logD("likes ${ids.joinToString(",")}")
        list.forEach {
            it.id = it.md5()
            it.like = ids.contains(it.id)
        }
        var currentIndex = index
        if ((customRandom && random) || (!customRandom && spUtil.shortRandom)) {
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

        initViewPager()
        initVideoView()

        mBinding.viewpage2.post {
            logD("currentIndex", currentIndex)
            if (currentIndex != 0) {
                isUserScroll.set(false)
                mBinding.viewpage2.setCurrentItem(currentIndex, false)
                mBinding.viewpage2.postDelayed({ startPlay(currentIndex) }, 30)
            } else {
                startPlay(currentIndex)
            }
        }
        shortSettingsDialog.onChange {
            mVideoView.setLooping(PlayerInitializer.Player.shortAutoNext.not())
            mVideoView.changeSysBar(PlayerInitializer.Player.shortShowSysBar)
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

    private fun initVideoView() {
        mVideoView = CustomVideoView(this)
        applyConfiguredPlaybackCore()
        mVideoView.setLooping(PlayerInitializer.Player.shortAutoNext.not())
        mController = ShortVideoController(this)
        subtitleControlView = SubtitleControlView(this)
        // 停靠策略依赖当前 render view 和方向信息，因此在字幕组件初始化后立刻创建 controller。
        subtitleDockController = SubtitleDockController(
            context = this,
            videoView = mVideoView,
            subtitleControlView = subtitleControlView,
            currentPosition = { mCurPos }
        )
        //subtitleControlView.setSubtitleAbsoluteTextSize(32f)
        subtitleControlView.setSubtitleScale(subtitleScaleNormal)
        mController.addControlComponent(subtitleControlView)
        mVideoView.setVideoController(mController)
        // 音频渐入渐出配置
        mVideoView.setEnableAudioFade(spUtil.audioFadeEnabled)
        mVideoView.setAudioFadeInDuration(spUtil.audioFadeInDuration)
        mVideoView.setAudioFadeOutDuration(spUtil.audioFadeOutDuration)
        mVideoView.addOnStateChangeListener(object : OnStateChangeListener {

            private var isLandscape = false

            override fun onPlayerStateChanged(playerState: Int) {
                when (playerState) {
                    VideoView.PLAYER_NORMAL -> {
                        applySubtitleTextSizeForPlayerState(VideoView.PLAYER_NORMAL)
                        mVideoView.changeSysBar(PlayerInitializer.Player.shortShowSysBar)
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
                    // 恢复播放 → 重新计时沉浸(scheduleImmersive 内部先 remove 再 post,幂等)
                    scheduleImmersive()
                }
                if (playState == VideoView.STATE_PAUSED) {
                    logAndCache(TAG, "D", "STATE_PAUSED: pos=$mCurPos")
                    // 暂停 → 取消已挂的沉浸计时(用户主动暂停,不应再被吞掉控制层)
                    cancelImmersive()
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

                    removeItem(mCurPos)
                }
            }
        })
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
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (position == mCurItem) return
                if (!isUserScroll.get()) return
                mBinding.viewpage2.post { startPlay(position) }
            }

            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    mCurItem = mBinding.viewpage2.currentItem
                    dragStartItem = mCurItem
                }
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    isUserScroll.set(false)
                    resetAllControlViewAlpha(animated = true)
                    activeShortVideoControlView?.setAutoImmersiveEnabled(spUtil.showSysBar.not())
                } else {
                    isUserScroll.set(true)
                }
            }
        })
        mShortVideoAdapter.setOnShortVideoListener(object : ShortVideoControlView.OnShortVideoListener {

            override fun onLike(like: Boolean) {
                val videoData = mVideoList[mCurPos]
                videoData.id = videoData.md5()
                val list = JsonUtil.toList<VideoData>(spUtil.likeJson!!).toMutableList()
                val index = list.indexOfFirst { it.id == videoData.id }
                if (index > -1) {
                    list.removeAt(index)
                }
                if (like) {
                    list.add(videoData)
                }
                spUtil.likeJson = JsonUtil.toJsonString(list)
                logD(list)
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
                if (mCurPos < mShortVideoAdapter.itemCount) {
                    isUserScroll.set(false)
                    val currentIndex = mCurPos + 1
                    mBinding.viewpage2.setCurrentItem(currentIndex, false)
                    mBinding.viewpage2.postDelayed({ startPlay(currentIndex) }, 30)
                }
            }

            override fun onChangeSysBar(show: Boolean) {
                mVideoView.changeSysBar(show)
            }

            override fun onSingleTap() {
                // 沉浸体验:点击屏幕退出沉浸并重新计时
                exitAndRescheduleImmersive()
            }

        })

        // ViewPage2内部是通过RecyclerView去实现的，它位于ViewPager2的第0个位置
        mViewPagerImpl = mBinding.viewpage2.getChildAt(0) as RecyclerView
        //mViewPagerImpl.setScrollingTouchSlop(RecyclerView.TOUCH_SLOP_PAGING)
    }

    private fun applyPlaybackCoreFor(videoBean: VideoData) {
        if (isSmbVideo(videoBean)) {
            applyShortVideoRenderFactory()
            DyPlayerCoreRegistry.applyCore(mVideoView, DyPlayerCore.EXO, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
            if (!spUtil.sortRender) {
                mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            }
            return
        }
        applyConfiguredPlaybackCore()
    }

    private fun applyConfiguredPlaybackCore() {
        val currentCore = DyPlayerCoreRegistry.resolveCore(spUtil.shortDyPlayerCore)
        if (currentCore == DyPlayerCore.MPV) {
            if (!spUtil.labMpvSpecialRender) {
                mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())
            }
            DyPlayerCoreRegistry.applyCore(mVideoView, spUtil.shortDyPlayerCore, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
            // Do not replace MPV's required Surface renderer with the short-video renderer. MPV keeps
            // default scaling here until fast-swipe Surface reuse is validated across devices.
            //mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_CENTER_CROP);
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
            return
        }
        if (spUtil.sortRender) {
            applyShortVideoRenderFactory()
        }
        DyPlayerCoreRegistry.applyCore(mVideoView, spUtil.shortDyPlayerCore, spUtil.labMpvSpecialRender, spUtil.labMpvSuperResolution, false)
        // 画质增强开启时保留 applyCore 设的 GlRenderViewFactory，不要被 TextureRenderView 覆盖
        if (!spUtil.sortRender && !spUtil.labMpvSuperResolution && !false) {
            mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())
            mVideoView.setScreenScaleType(VideoView.SCREEN_SCALE_DEFAULT)
        }
    }

    private fun applyShortVideoRenderFactory() {
        if (spUtil.sortRender) {
            mVideoView.setRenderViewFactory(ShortVideoRenderViewFactory.create())
        } else {
            mVideoView.setRenderViewFactory(TextureRenderViewFactory.create())
        }
    }

    private fun isSmbVideo(videoBean: VideoData): Boolean {
        return videoBean.type == StorageType.SMB || videoBean.videoUrl.startsWith("smb://", ignoreCase = true)
    }

    private fun startPlay(position: Int) {
        mediaPlaybackRecorder.cancelLastPlayedUpdate()
        val count = mViewPagerImpl.childCount
        for (i in 0 until count) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as ShortVideoAdapter.ViewHolder
            if (viewHolder.bindingAdapterPosition == position) {
                // 旧 ControlView 设为非活跃
                activeShortVideoControlView?.setOnVideoTransformChangedListener(null)
                clearSubtitleCueListener()
                mVideoView.release()
                removeViewFormParent(mVideoView)
                subtitleControlView.clearText()
                subtitleControlView.clearSubtitleLayoutBounds()
                // mVideoList[position]
                val videoBean = viewHolder.mVideoData
                applyPlaybackCoreFor(videoBean)
                logAndCache(TAG, "D", "开始播放: pos=$position, url=${videoBean.videoUrl}, type=${videoBean.type}")
                if (videoBean.type == StorageType.WEBDAV) {
                    logAndCache(TAG, "D", "URL类型: webdav, url=${videoBean.videoUrl}")
                    mVideoView.setUrl(videoBean.videoUrl, videoBean.headers)
                    mVideoView.start()
                } else {
                    if (videoBean.videoUrl.startsWith("http")) {
                        logAndCache(TAG, "D", "URL类型: http(302重定向), url=${videoBean.videoUrl}")
                        lifecycleScope.launch(Dispatchers.IO) {
                            videoBean.videoUrl = HttpUtil.getRedirectUrl(videoBean.videoUrl)
                            withContext(Dispatchers.Main) {
                                mVideoView.setUrl(videoBean.videoUrl)
                                mVideoView.start()
                            }
                        }
                    } else if (videoBean.videoUrl.startsWith("smb")) {
                        logAndCache(TAG, "D", "URL类型: smb, url=${videoBean.videoUrl}")
                        mVideoView.setUrl(videoBean.videoUrl, videoBean.headers)
                        mVideoView.start()
                    } else {
                        logAndCache(TAG, "D", "URL类型: local, url=${videoBean.videoUrl}")
                        mVideoView.setUrl(videoBean.videoUrl)
                        mVideoView.start()
                    }
                }
                val displayTitle = formatShortTitle(videoBean.name)
                mController.setTitle(displayTitle)
                viewHolder.shortVideoControlView.setTitle(displayTitle)
                viewHolder.shortVideoControlView.setPage(position, mVideoList.size)
                mController.addShortVideoControlView(viewHolder.shortVideoControlView)
                viewHolder.shortVideoControlView.setOnVideoTransformChangedListener {
                    updateSubtitleDocking()
                }
                activeShortVideoControlView = viewHolder.shortVideoControlView
                viewHolder.shortVideoControlView.setAutoImmersiveEnabled(spUtil.showSysBar.not())
                // 切视频后重新调度沉浸(Activity 级定时器,确保操作的是活跃的 ControlView)
                cancelImmersive()
                scheduleImmersive()
                viewHolder.playerContainer.addView(mVideoView, 0)
                viewHolder.shortVideoControlView.resetScale(1.0f)
                mCurPos = position

                lifecycleScope.launch(Dispatchers.IO) {
                    val commentCount = shortCommentController.loadCommentCount(videoBean.id)
                    withContext(Dispatchers.Main) {
                        viewHolder.shortVideoControlView.setCommentCount(commentCount)
                    }
                }

                saveMediaInfo(videoBean.videoUrl)
                break
            }
        }
    }

    private fun attachSubtitle() {
        if (!::subtitleControlView.isInitialized || mCurPos !in mVideoList.indices) {
            return
        }
        clearSubtitleCueListener()
        subtitleControlView.clearText()
        val position = mCurPos
        val videoData = mVideoList[position]
        lifecycleScope.launch(Dispatchers.IO) {
            val subtitleFile = PlayHelper.resolveSubtitleFile(this@ShortVideoActivity, videoData)
            withContext(Dispatchers.Main) {
                if (position != mCurPos || position !in mVideoList.indices) {
                    return@withContext
                }
                if (mVideoList[position].videoUrl != videoData.videoUrl) {
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
        val count = mViewPagerImpl.childCount
        for (i in 0 until count) {
            val itemView = mViewPagerImpl.getChildAt(i)
            val viewHolder = itemView.tag as? ShortVideoAdapter.ViewHolder ?: continue
            if (viewHolder.bindingAdapterPosition == position) {
                return viewHolder.shortVideoControlView
            }
        }
        return null
    }

    private fun updateControlAlphaForPageScroll(position: Int, positionOffset: Float, dragStartItem: Int) {
        if (positionOffset <= 0f) {
            findShortVideoControlViewByPosition(dragStartItem)?.alpha = CONTROL_ALPHA_FULL
            return
        }

        val alpha = when {
            position == dragStartItem -> {
                val ratio = (positionOffset / CONTROL_ALPHA_DIM_PROGRESS).coerceIn(0f, 1f)
                CONTROL_ALPHA_FULL - ratio * (CONTROL_ALPHA_FULL - CONTROL_ALPHA_DIMMED)
            }

            position < dragStartItem -> {
                val visibleProgress = 1f - positionOffset
                val ratio = (visibleProgress / CONTROL_ALPHA_RESTORE_VISIBLE_PROGRESS).coerceIn(0f, 1f)
                CONTROL_ALPHA_DIMMED + ratio * (CONTROL_ALPHA_FULL - CONTROL_ALPHA_DIMMED)
            }

            else -> CONTROL_ALPHA_FULL
        }

        findShortVideoControlViewByPosition(position)?.let { view ->
            view.animate().cancel()
            view.alpha = alpha
        }
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
        val size = mVideoList.size
        mVideoList.addAll(list)
        // 使用此方法添加数据，使用notifyDataSetChanged会导致正在播放的视频中断
        mShortVideoAdapter.notifyItemRangeChanged(size, mVideoList.size)
    }

    fun removeItem(position: Int) {
        if (position < 0 || position >= mVideoList.size) return
        // 先释放当前视频并从容器移除
        mVideoView.release()
        AppUtil.removeViewFormParent(mVideoView)
        activeShortVideoControlView?.setOnVideoTransformChangedListener(null)
        activeShortVideoControlView = null
        mVideoList.removeAt(position)
        mShortVideoAdapter.notifyItemRemoved(position)
        mShortVideoAdapter.notifyItemRangeChanged(position, mVideoList.size)
        if (position == mCurPos) {
            if (mVideoList.isNotEmpty()) {
                val newPos = position.coerceAtMost(mVideoList.size - 1)
                mCurPos = newPos
                // 延迟执行，等待ViewPager2完成布局刷新后再播放
                mBinding.viewpage2.post { startPlay(newPos) }
            } else {
                finish()
            }
        } else if (position < mCurPos) {
            mCurPos--
        }
    }

    private fun onPlayStart() {
        val position = mCurPos
        mBinding.root.post {
            if (onHistory) {
                return@post
            }
            val videoBean = mVideoList[position]
            PlayHelper.saveHistory(spUtil, videoBean)
        }
        doScreenShot(position)
    }

    fun doScreenShot(position: Int) {
        mBinding.root.postDelayed({
            if (position != mCurPos) {
                return@postDelayed
            }
            if (mVideoList[position].videoUrl.isLocal()) {
                return@postDelayed
            }
            runOnUiThread {
                val file = File(
                    externalCacheDir,
                    ".thumb/${mVideoList[mCurPos].md5()}.${AppUtil.THUMB_TYPE}"
                )
                if (file.exists() && file.length() < 20480) {
                    file.delete()
                }
                if (!file.exists()) {
                    try {
                        file.createNew()
                        FileOutputStream(file).use { fos ->
                            mVideoView.doScreenShot().compress(AppUtil.COMPRESS_FORMAT, 70, fos)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "doScreenShot failed: ${e.message}")
                    }
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
        if (::mVideoView.isInitialized) {
            mVideoView.resume()
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
        timerCloseController.onPause()
    }

    override fun onStart() {
        super.onStart()
        // 短视频不做后台播放：回到前台时若处于暂停态，恢复播放。
        if (::mVideoView.isInitialized && !userInitiatedExit && mVideoView.hasPlayer() && !mVideoView.isPlaying) {
            mVideoView.start()
        }
    }

    override fun onStop() {
        super.onStop()
        if (userInitiatedExit) {
            userInitiatedExit = false
            return
        }
        if (isChangingConfigurations) return
        // 短视频不做后台播放：不可见时直接暂停。
        if (::mVideoView.isInitialized && mVideoView.hasPlayer()) {
            mVideoView.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 注销超分广播接收器
        unregisterSuperResolutionReceiver()
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
        mVideoView.release()
        finishAffinity()
    }

}
