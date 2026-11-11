package me.lingci.lib.player.view

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.ViewGroup
import android.view.animation.Animation
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.loader.android.BiliDanmakuLoader
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.DanmakuTimer
import master.flame.danmaku.danmaku.model.IDisplayer.DANMAKU_STYLE_STROKEN
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.ui.widget.DanmakuView
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.dm.view.controller.KeywordFilter
import me.lingci.lib.dm.view.controller.RegexFilter
import me.lingci.lib.dm.view.entity.DmLoadOptions
import me.lingci.lib.dm.view.parser.BiliDanmakuParser
import me.lingci.lib.dm.view.parser.DanmakuParserBuilder
import me.lingci.lib.dm.view.widget.GradientViewCacheStuffer
import me.lingci.lib.player.danmaku.PlayerInitializer
import xyz.doikki.videoplayer.controller.ControlWrapper
import xyz.doikki.videoplayer.controller.IControlComponent
import xyz.doikki.videoplayer.player.VideoView
import java.io.File
import java.io.InputStream
import kotlin.math.max

/**
 * Created by xyoye on 2020/11/17.
 * bullet screen
 */
class DanMuView(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DanmakuView(context, attrs, defStyleAttr), IControlComponent {
    companion object {
        private const val DAN_MU_MAX_TEXT_SIZE = 2.4f
        private const val DAN_MU_MAX_TEXT_ALPHA = 1f
        private const val DAN_MU_MAX_TEXT_SPEED = 2.5f
        private const val DAN_MU_MAX_TEXT_STOKE = 20f

        private const val SYNC_THRESHOLD_MS = 500L
        private const val SYNC_DEBOUNCE_MS = 500L

        private const val INVALID_VALUE = -1L
    }

    private lateinit var mControlWrapper: ControlWrapper

    private val mDanMaKuContext = DanmakuContext.create()
    private val mDanMaKuLoader = BiliDanmakuLoader.instance()
    private val mKeywordFilter = KeywordFilter()
    private val mRegexFilter = RegexFilter()

    private var mSeekPosition = INVALID_VALUE

    private var mUrl: String? = null
    private var isDanMuLoaded = false
    private var strokeMultiple: Float = 1f
    private var dmLoadOptions = DmLoadOptions()
    private var lastSyncTargetTime = INVALID_VALUE
    private var lastSyncRequestTime = 0L

    init {
        showFPS(false)

        initDanMuContext()

        setCallback(object : DrawHandler.Callback {
            override fun drawingFinished() {

            }

            override fun danmakuShown(danmaku: BaseDanmaku?) {

            }

            override fun prepared() {
                post {
                    isDanMuLoaded = true
                    if (mControlWrapper.isPlaying) {
                        seekTo(mControlWrapper.currentPosition, mControlWrapper.isPlaying)
                    }
                }
            }

            override fun updateTimer(timer: DanmakuTimer?) {

            }
        })
    }

    override fun attach(controlWrapper: ControlWrapper) {
        mControlWrapper = controlWrapper
    }

    override fun onVisibilityChanged(isVisible: Boolean, anim: Animation?) {

    }

    override fun onPlayStateChanged(playState: Int) {
        Log.d(TAG, "onPlayStateChanged: $playState  ${mControlWrapper.isPlaying}")
        when (playState) {
            VideoView.STATE_IDLE -> {
                release()
            }
            // 准备
            VideoView.STATE_PREPARING,
            VideoView.STATE_PREPARED -> {

            }
            // 播放
            VideoView.STATE_PLAYING -> {
                if (isPrepared) {
                    if (isPaused) {
                        resume()
                    } else {
                        updateOffsetTime()
                    }
                }
            }
            // 暂停加载失败等
            VideoView.STATE_PLAYBACK_COMPLETED,
            VideoView.STATE_ERROR,
            VideoView.STATE_PAUSED -> {
                if (isPrepared) {
                    pause()
                }
            }
            // 预加载：弹幕随画面一起停住，避免弹幕时间轴跑在画面之前
            VideoView.STATE_BUFFERING -> {
                updateOffsetTime()
                if (isPrepared && !isPaused) {
                    pause()
                }
            }
            // 预加载完成：播放器恢复播放时同步恢复弹幕
            VideoView.STATE_BUFFERED -> {
                if (isPrepared && isPaused && mControlWrapper.isPlaying) {
                    resume()
                }
            }
        }
    }

    override fun onPlayerStateChanged(playerState: Int) {
        Log.d(TAG, "onPlayerStateChanged: $playerState")
        when (playerState) {
            VideoView.PLAYER_NORMAL -> {

            }

            VideoView.PLAYER_FULL_SCREEN -> {

            }

            VideoView.PLAYER_TINY_SCREEN -> {

            }
        }

    }

    override fun setProgress(duration: Int, position: Int) {
        if (duration <= 0 || !mControlWrapper.isPlaying || !isPrepared) {
            return
        }
        syncTimeIfNeeded(position.toLong())
    }

    override fun onLockStateChanged(isLocked: Boolean) {

    }

    override fun resume() {
        if (mSeekPosition != INVALID_VALUE) {
            seekTo(mSeekPosition)
            mSeekPosition = INVALID_VALUE
        }
        super.resume()
    }

    override fun release() {
        super.release()
        mUrl = null
        lastSyncTargetTime = INVALID_VALUE
        lastSyncRequestTime = 0L
        clear()
        clearDanmakusOnScreen()
    }

    fun setStrokeMultiple(multiple: Float) {
        this.strokeMultiple = multiple
        mDanMaKuContext.setCacheStuffer(GradientViewCacheStuffer(strokeMultiple), null)
    }

    fun seekTo(timeMs: Long, isPlaying: Boolean) {
        if (isPlaying.and(config != null)) {
            val targetTime = timeMs + PlayerInitializer.Danmu.offsetPosition
            markSyncTarget(targetTime)
            seekTo(targetTime)
        } else {
            mSeekPosition = timeMs + PlayerInitializer.Danmu.offsetPosition
        }
    }

    fun loadDanMu(url: String) {
        loadDanMu(url, dmLoadOptions)
    }

    fun loadDanMu(url: String, options: DmLoadOptions) {
        PlayerInitializer.Danmu.offsetPosition = 0L
        if (url.isBlank())
            return
        val danMuFile = File(url)
        if (!danMuFile.exists())
            return
        mUrl = url
        mDanMaKuLoader.load(url)
        if (mDanMaKuLoader.dataSource != null) {
            val danMaKuParser =
                BiliDanmakuParser(options)
            danMaKuParser.load(mDanMaKuLoader.dataSource)
            prepare(danMaKuParser, mDanMaKuContext)
            isDanMuLoaded = false
        } else {
            (context as? Activity)?.runOnUiThread {
                ToastUtil.showToast(context, "弹幕加载失败")
            }
        }
    }

    fun loadDanMu(stream: InputStream?) {
        loadDanMu(stream, dmLoadOptions)
    }

    fun loadDanMu(stream: InputStream?, options: DmLoadOptions) {
        PlayerInitializer.Danmu.offsetPosition = 0L
        if (null == stream) {
            return
        }
        try {
            val danMaKuParser = DanmakuParserBuilder.createParser(stream, options)
            prepare(danMaKuParser, mDanMaKuContext)
            isDanMuLoaded = false
        } catch (e: Exception) {
            (context as? Activity)?.runOnUiThread {
                ToastUtil.showToast(context, "弹幕加载失败")
            }
        }
    }

    fun toggleVis() {
        if (isShown) {
            hide()
        } else {
            show()
        }
    }

    fun toggleVis(show: Boolean) {
        if (show) {
            show()
        } else {
            hide()
        }
    }

    private fun initDanMuContext() {
        // 设置禁止重叠
        val overlappingPair: MutableMap<Int, Boolean> = HashMap()
        overlappingPair[BaseDanmaku.TYPE_SCROLL_LR] = true
        overlappingPair[BaseDanmaku.TYPE_SCROLL_RL] = true
        overlappingPair[BaseDanmaku.TYPE_FIX_TOP] = true
        overlappingPair[BaseDanmaku.TYPE_FIX_BOTTOM] = true

        // 弹幕更新方式, 0:Choreographer, 1:new Thread, 2:DrawHandler
        val danMuUpdateMethod: Byte =
            if (PlayerInitializer.Danmu.updateInChoreographer) 0 else 2

        mDanMaKuContext.apply {
            // 合并重复弹幕
            isDuplicateMergingEnabled = true
            // 弹幕view开启绘制缓存
            enableDanmakuDrawingCache(true)
            // 设置禁止重叠
            mDanMaKuContext.preventOverlapping(overlappingPair)
            // 使用DrawHandler驱动刷新，避免在高刷新率时时间轴错位
            updateMethod = danMuUpdateMethod
            // 添加关键字过滤器
            registerFilter(mKeywordFilter)
            // 添加正则过滤器
            registerFilter(mRegexFilter)
            // 自定义绘制
            setCacheStuffer(GradientViewCacheStuffer(strokeMultiple), null)
            setDanmakuMargin(PlayerInitializer.Danmu.margin)
        }
        prepare(BiliDanmakuParser(), mDanMaKuContext)

        updateDanmuSize()
        updateDanmuSpeed()
        updateDanmuAlpha()
        updateDanmuStroke()
        updateMobileDanmuState()
        updateTopDanmuState()
        updateBottomDanmuState()
        updateMaxLine()
        updateMaxScreenNum()
    }

    fun updateTextBold(bold: Boolean) {
        Log.d(this@DanMuView, "textBold", bold)
        mDanMaKuContext.setDanmakuBold(bold)
    }

    fun updateDanmuSize() {
        val progress = PlayerInitializer.Danmu.size / 100f
        val size = progress * DAN_MU_MAX_TEXT_SIZE
        mDanMaKuContext.setScaleTextSize(size)
    }

    fun updateDanmuSpeed() {
        val progress = PlayerInitializer.Danmu.speed / 100f
        var speed = DAN_MU_MAX_TEXT_SPEED * (1 - progress)
        speed = max(0.1f, speed)
        Log.d(this@DanMuView, "speed", speed, "progress", progress)
        mDanMaKuContext.setScrollSpeedFactor(speed)
        mDanMaKuContext.setSpeed(PlayerInitializer.Player.videoSpeed)
    }

    fun updateDanmuAlpha() {
        val progress = PlayerInitializer.Danmu.alpha / 100f
        val alpha = progress * DAN_MU_MAX_TEXT_ALPHA
        mDanMaKuContext.setDanmakuTransparency(alpha)
    }

    fun updateDanmuStroke() {
        val progress = PlayerInitializer.Danmu.stoke / 100f
        val stoke = progress * DAN_MU_MAX_TEXT_STOKE
        Log.d(this@DanMuView, "stroke", stoke)
        mDanMaKuContext.setDanmakuStyle(DANMAKU_STYLE_STROKEN, stoke)
    }

    fun updateDanmuMargin() {
        mDanMaKuContext.setDanmakuMargin(PlayerInitializer.Danmu.margin)
    }

    fun updateMobileDanmuState() {
        mDanMaKuContext.r2LDanmakuVisibility = PlayerInitializer.Danmu.mobileDanmu
    }

    fun updateTopDanmuState() {
        mDanMaKuContext.ftDanmakuVisibility = PlayerInitializer.Danmu.topDanmu
    }

    fun updateBottomDanmuState() {
        mDanMaKuContext.fbDanmakuVisibility = PlayerInitializer.Danmu.bottomDanmu
    }

    fun updateOffsetTime() {
        syncTimeIfNeeded(mControlWrapper.currentPosition)
    }

    private fun syncTimeIfNeeded(positionMs: Long) {
        val targetTime = positionMs + PlayerInitializer.Danmu.offsetPosition
        val difference = kotlin.math.abs(targetTime - currentTime)
        Log.d(TAG, "syncTimeIfNeeded: $currentTime $positionMs $difference")
        if (difference > SYNC_THRESHOLD_MS) {
            if (isDuplicateSyncTarget(targetTime)) {
                Log.d(TAG, "skip duplicate sync: $targetTime")
                return
            }
            seekTo(positionMs, mControlWrapper.isPlaying)
        }
    }

    private fun isDuplicateSyncTarget(targetTime: Long): Boolean {
        val now = SystemClock.uptimeMillis()
        return lastSyncTargetTime != INVALID_VALUE &&
            kotlin.math.abs(targetTime - lastSyncTargetTime) <= SYNC_THRESHOLD_MS &&
            now - lastSyncRequestTime < SYNC_DEBOUNCE_MS
    }

    private fun markSyncTarget(targetTime: Long) {
        lastSyncTargetTime = targetTime
        lastSyncRequestTime = SystemClock.uptimeMillis()
    }

    fun updateMaxLine() {
        val scroll: Int? = if (PlayerInitializer.Danmu.maxLine == -1) null else PlayerInitializer.Danmu.maxLine
        val top: Int? = if (PlayerInitializer.Danmu.maxTopLine == -1) null else PlayerInitializer.Danmu.maxTopLine
        val bottom: Int? = if (PlayerInitializer.Danmu.maxBottomLine == -1) null else PlayerInitializer.Danmu.maxBottomLine
        mDanMaKuContext.setMaximumLines(
            mutableMapOf(
                BaseDanmaku.TYPE_SCROLL_LR to scroll,
                BaseDanmaku.TYPE_SCROLL_RL to scroll,
                BaseDanmaku.TYPE_FIX_TOP to top,
                BaseDanmaku.TYPE_FIX_BOTTOM to bottom,
            )
        )
    }

    fun updateMaxScreenNum() {
        mDanMaKuContext.setMaximumVisibleSizeInScreen(PlayerInitializer.Danmu.maxNum)
    }

    fun addBlackList(isRegex: Boolean, vararg keyword: String) {
        keyword.forEach {
            if (isRegex) {
                mRegexFilter.addRegex(it)
            } else {
                mKeywordFilter.addKeyword(it)
            }
        }
        notifyFilterChanged()
    }

    fun removeBlackList(isRegex: Boolean, keyword: String) {
        if (isRegex) {
            mRegexFilter.removeRegex(keyword)
        } else {
            mKeywordFilter.removeKeyword(keyword)
        }
        notifyFilterChanged()
    }

    fun setCloudBlockData(cloudBlockData: MutableList<String>) {
        if (PlayerInitializer.Danmu.cloudBlock && cloudBlockData.isNotEmpty()) {
            cloudBlockData.forEach { entity ->
                if (entity.isNotBlank()) {
                    mRegexFilter.addRegex(entity)
                } else {
                    mKeywordFilter.addKeyword(entity)
                }
            }
            notifyFilterChanged()
        }
    }

    fun allowSendDanmu(): Boolean {
        return isDanMuLoaded
    }

    fun addDanmuToView(isScroll: Boolean, isTop: Boolean, textStr: String, color: Int) {
        val type = when {
            isScroll -> BaseDanmaku.TYPE_SCROLL_RL
            isTop -> BaseDanmaku.TYPE_FIX_TOP
            else -> BaseDanmaku.TYPE_FIX_BOTTOM
        }

        val danMaKu = mDanMaKuContext.mDanmakuFactory.createDanmaku(type).apply {
            text = textStr
            padding = 5
            isLive = false
            priority = 0
            textColor = color
            underlineColor = Color.GREEN
            time = this@DanMuView.currentTime + 500
        }
        addDanmaku(danMaKu)
    }

    fun setSpeed(speed: Float) {
        mDanMaKuContext.setSpeed(speed)
    }

    fun setDmGradient(dmGradient: Boolean, gradientRatio: Int) {
        this.dmLoadOptions.whiteToGradient = dmGradient
        this.dmLoadOptions.gradientRatio = gradientRatio
    }

    fun setGradientWithTextColor(withTextColor: Boolean) {
        this.dmLoadOptions.gradientWithTextColor = withTextColor
    }

    fun setTypeface(typeface: Typeface) {
        mDanMaKuContext.setTypeface(typeface)
        repairStyle()
    }

    fun clearTypeface() {
        mDanMaKuContext.setTypeface(null)
        repairStyle()
    }

    private fun repairStyle() {
        val size = PlayerInitializer.Danmu.size
        PlayerInitializer.Danmu.size = size + 1
        updateDanmuSize()
        PlayerInitializer.Danmu.size = size
        updateDanmuSize()
    }

    fun updateLayoutMargin() {
        Log.d(this@DanMuView, layoutParams, width, height)
        val lp: ViewGroup.MarginLayoutParams = if (layoutParams == null) {
            ViewGroup.MarginLayoutParams(
                ViewGroup.MarginLayoutParams.MATCH_PARENT,
                ViewGroup.MarginLayoutParams.MATCH_PARENT
            )
        } else {
            layoutParams as ViewGroup.MarginLayoutParams
        }
        lp.bottomMargin = 50
        layoutParams = lp
    }

    private fun notifyFilterChanged() {
        // 该方法内部会调用弹幕刷新，能达到相应效果
        mDanMaKuContext.addUserHashBlackList()
    }

}
