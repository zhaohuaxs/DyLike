package me.lingci.lib.player.exo.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.View
import me.lingci.lib.base.util.Log
import android.util.Log as AndroidLog
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.MeasureHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * GLSurfaceView 渲染视图，支持自定义 GLSL shader 处理 ExoPlayer 解码的每一帧。
 *
 * 设计参考：
 * - `MpvSurfaceRenderView`：IRenderView 的 SurfaceView 子类模板
 * - Google ExoPlayer demo `VideoProcessingGLSurfaceView`：GLSurfaceView + Renderer 分离模式
 *
 * 工作流：
 * 1. attachToPlayer: 创建 SharpenVideoRenderer，setRenderer/setRenderMode
 * 2. SharpenVideoRenderer.onSurfaceCreated (GL 线程): 创建 SurfaceTexture
 * 3. 通过 onSurfaceTextureReady 回调（主线程）: player.setVideoSurface(Surface(st))
 * 4. ExoPlayer 解码帧写入 SurfaceTexture → onFrameAvailable → requestRender
 * 5. SharpenVideoRenderer.onDrawFrame (GL 线程): 跑 shader，画到屏幕
 *
 * 截图通过 queueEvent + glReadPixels 实现（Phase 3）。
 */
class GlRenderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), IRenderView {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val measureHelper = MeasureHelper()

    private var mediaPlayer: AbstractPlayer? = null
    private var videoRenderer: SharpenVideoRenderer? = null
    private var isReleased = false
    // GLSurfaceView.setRenderer 只能调一次。后台播放恢复时 attachToPlayer 会再次调用，
    // 用此标记避免重复 setRenderer 抛 IllegalStateException: setRenderer has already been called。
    private var isRendererSet = false

    init {
        // GLSurfaceView EGL 配置（参考 Google demo）
        setEGLContextClientVersion(2)
        setEGLConfigChooser(
            /* redSize */ 8,
            /* greenSize */ 8,
            /* blueSize */ 8,
            /* alphaSize */ 8,
            /* depthSize */ 0,
            /* stencilSize */ 0
        )
        // 注意：不在这里 setRenderer，等 attachToPlayer 才设
    }

    override fun attachToPlayer(player: AbstractPlayer) {
        mediaPlayer = player
        // 读 NCNN 超分开关
        val useNcnn = try {
            val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            sp.getBoolean("labNeuralSuperResolution", false)
        } catch (_: Throwable) { false }
        // 后台播放恢复复用同一 GLSurfaceView：setRenderer 只能调一次，已设过则跳过。
        // 通过 requestRender 触发 onSurfaceCreated 重建 SurfaceTexture → setSurface。
        if (isRendererSet) {
            Log.d("GlRenderView", "attachToPlayer: renderer already set, requestRender to rebuild surface")
            requestRender()
            return
        }
        videoRenderer = SharpenVideoRenderer(context, { surfaceTexture ->
            // 此回调在 GL 线程触发，切回主线程调 player.setVideoSurface
            mainHandler.post {
                if (!isReleased) {
                    try {
                        mediaPlayer?.setSurface(Surface(surfaceTexture))
                        Log.d("GlRenderView", "setSurface OK")
                    } catch (e: Exception) {
                        AndroidLog.e("GlRenderView", "setSurface failed: ${e.message}")
                    }
                }
            }
        }, useNcnn).also {
            it.bindGlSurfaceView(this)
        }
        setRenderer(videoRenderer)
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY)
        isRendererSet = true
    }

    override fun getView(): View = this

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            measureHelper.setVideoSize(videoWidth, videoHeight)
            videoRenderer?.onVideoSizeChanged(videoWidth, videoHeight)
            requestLayout()
        }
    }

    override fun setVideoRotation(degree: Int) {
        measureHelper.setVideoRotation(degree)
        invalidate()
    }

    override fun setScaleType(scaleType: Int) {
        measureHelper.setScreenScale(scaleType)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredSize = measureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredSize[0], measuredSize[1])
    }

    override fun doScreenShot(): Bitmap? {
        // glReadPixels 必须在 GL 线程跑
        val renderer = videoRenderer ?: return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        queueEvent {
            bitmap = renderer.readFramebuffer()
            latch.countDown()
        }
        try {
            latch.await(500, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {}
        return bitmap
    }

    override fun release() {
        isReleased = true
        mainHandler.post {
            try {
                mediaPlayer?.setSurface(null)
            } catch (_: Exception) {}
        }
        mediaPlayer = null
        // GLSurfaceView 自己管 EGL 释放
        try {
            onPause()
        } catch (_: Exception) {}
        videoRenderer?.release()
        videoRenderer = null
    }
}
