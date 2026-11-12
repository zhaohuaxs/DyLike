package me.lingci.lib.player.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.player.listener.OnLongVideoListener
import me.lingci.lib.player.view.DanMuView
import me.lingci.lib.player.widget.component.CustomControlView
import me.lingci.lib.player.widget.component.DmConfControlView
import me.lingci.lib.player.widget.component.DmSelectControlView
import xyz.doikki.videocontroller.StandardVideoController
import xyz.doikki.videocontroller.component.CompleteView
import xyz.doikki.videocontroller.component.ErrorView
import xyz.doikki.videocontroller.component.GestureView
import xyz.doikki.videocontroller.component.PrepareView
import xyz.doikki.videocontroller.component.TitleView
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.player.VideoViewManager

/**
 * 页面以及播放器共有逻辑封装
 * @param <T>
</T> */
abstract class BaseVideoActivity<T : VideoView> : BaseActivity(), OnLongVideoListener {

    protected lateinit var controller: StandardVideoController
    protected lateinit var controlView: CustomControlView
    protected lateinit var titleView: TitleView
    protected lateinit var danMuView: DanMuView
    protected lateinit var dmSelectView: DmSelectControlView
    protected lateinit var dmConfView: DmConfControlView

    protected var mVideoView: T? = null
    private val titleResId: Int
        get() = R.string.app_name

    private val layoutResId: Int
        get() = 0

    private val contentView: View?
        get() = null

    private fun enableBack(): Boolean {
        return true
    }

    protected val videoViewManager: VideoViewManager
        get() = VideoViewManager.instance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (layoutResId != 0) {
            setContentView(layoutResId)
        } else if (contentView != null) {
            setContentView(contentView)
        }
        // 标题栏设置
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setTitle(titleResId)
            if (enableBack()) {
                actionBar.setDisplayHomeAsUpEnabled(true)
            }
        }
        initView()
    }

    protected fun setTitle(title: String?) {
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.title = title
        }
    }

    protected open fun initView() {

    }

    protected open fun initBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            @SuppressLint("SourceLockedOrientationActivity")
            override fun handleOnBackPressed() {
                if (mVideoView == null) {
                    finish()
                } else {
                    if (::controller.isInitialized && controller.isLocked) {
                        controller.show()
                        ToastUtil.showToast(this@BaseVideoActivity, R.string.dkplayer_lock_tip)
                        return
                    }
                    if (mVideoView?.isFullScreen!!) {
                        mVideoView?.stopFullScreen()
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        dmSelectView.visibility = View.GONE
                        dmConfView.visibility = View.GONE
                        return
                    }
                    finish()
                }
            }
        })
    }

    /**
     * 控制器工厂，子类可替换为定制控制器（如方向逻辑定制）
     */
    protected open fun createController(): StandardVideoController = StandardVideoController(this)

    /**
     * 视频控制器
     */
    protected open fun initCustomVideoControlComponent() {
        controller = createController()
        controller.setCanChangePosition(true)
        // 根据屏幕方向自动进入/退出全屏
        controller.setEnableOrientation(false)
        // 竖屏手势
        controller.setEnableInNormal(false)
        // 弹幕搜索
        dmSelectView = DmSelectControlView(this)
        controller.addControlComponent(dmSelectView)
        // 弹幕样式
        dmConfView = DmConfControlView(this)
        controller.addControlComponent(dmConfView)
        titleView = TitleView(this)
        titleView.setTitle("")
        titleView.findViewById<ImageView>(R.id.back).setOnClickListener {
            if (mVideoView != null && mVideoView!!.isFullScreen) {
                mVideoView?.onBackPressed()
            } else {
                finish()
            }
        }
        controller.addControlComponent(titleView)
        // VodControlView
        controlView = CustomControlView(this)
        // 是否显示底部进度条。默认显示
        controlView.showBottomProgress(false)
        controller.addControlComponent(controlView)
        // 滑动控制视图
        controller.addControlComponent(GestureView(this))
        // 弹幕
        danMuView = DanMuView(this)
        controller.addControlComponent(danMuView)
        // 准备播放界面 val thumb = prepareView.findViewById<ImageView>(R.id.thumb) //封面图
        val prepareView = PrepareView(this)
        prepareView.setClickStart()
        controller.addControlComponent(prepareView)
        // 自动完成播放界面
        controller.addControlComponent(CompleteView(this))
        // 错误界面
        controller.addControlComponent(ErrorView(this))
        // 弹幕设置
        dmConfView.setOnValueChangeListener { type, value ->
            danMuView.updateDanmuSize()
            if (type == DmConfControlView.TYPE_STYLE && value is Boolean) {
                danMuView.updateTextBold(value)
            }
        }
        controlView.setOnLongVideoListener(this)
    }

    /**
     * 把状态栏设成透明
     */
    protected fun setStatusBarTransparent() {
        contentView?.let {
            ViewCompat.setOnApplyWindowInsetsListener(it) { view, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    topMargin = 0
                    leftMargin = insets.left
                    bottomMargin = insets.bottom
                    rightMargin = insets.right
                }
                WindowInsetsCompat.CONSUMED
            }
            @Suppress("DEPRECATION")
            window.statusBarColor = ContextCompat.getColor(this, android.R.color.transparent)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        mVideoView?.resume()
    }

    override fun onPause() {
        super.onPause()
        mVideoView?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mVideoView?.release()
    }

    override fun onSelectDm() {
        controller.hide()
        dmSelectView.switchVib()
    }

    override fun onConfDm() {
        controller.hide()
        dmConfView.switchVib()
    }

    override fun onConfVideo() {

    }

    override fun onDmShow(isShow: Boolean) {

    }

    override fun onShowDmTrack() {

    }

    override fun onShowTrackPanel() {

    }

    override fun onShowEpisodeSelect() {

    }

    override fun onSpeedChange() {

    }

    override fun onTimeSync() {

    }

    override fun onShowMediaInfo() {

    }

}
