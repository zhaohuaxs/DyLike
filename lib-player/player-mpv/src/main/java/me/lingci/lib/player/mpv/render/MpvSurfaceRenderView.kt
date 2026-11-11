package me.lingci.lib.player.mpv.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import `is`.xyz.mpv.MPV
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.mpv.MpvMediaPlayer
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.MeasureHelper
import me.lingci.lib.player.util.SurfaceRenderTrace
import java.text.SimpleDateFormat
import java.util.concurrent.CountDownLatch
import java.util.Date
import java.util.Locale

class MpvSurfaceRenderView(
    context: Context, attrs: AttributeSet? = null
) : SurfaceView(context, attrs), IRenderView, SurfaceHolder.Callback {

    private var mMeasureHelper: MeasureHelper = MeasureHelper()
    private var mMediaPlayer: AbstractPlayer? = null
    private var mIsSurfaceCreated = false
    private var mIsReleased = false
    private var mHasBoundDisplay = false
    private var screenshotOptionsConfigured = false

    var mpv: MPV?= null

    init {
        holder.setFormat(PixelFormat.RGBA_8888)
        holder.addCallback(this)
    }

    override fun attachToPlayer(player: AbstractPlayer) {
        Log.d(this, "MPV attachToPlayer")
        SurfaceRenderTrace.d("MpvSurfaceRenderView", "attachToPlayer player=${player::class.java.simpleName} created=$mIsSurfaceCreated released=$mIsReleased")
        mIsReleased = false
        this.mMediaPlayer = player
        mpv = (player as? MpvMediaPlayer)?.mpv
        mHasBoundDisplay = false
        // 如果Surface已经创建，立即设置给播放器
        if (mIsSurfaceCreated) {
            val attachedPlayer = mMediaPlayer ?: return
            SurfaceRenderTrace.d("MpvSurfaceRenderView", "attachToPlayer immediate setDisplay surface=${holder.surface} valid=${holder.surface?.isValid}")
            attachedPlayer.setDisplay(holder)
        }
    }

    override fun setVideoSize(videoWidth: Int, videoHeight: Int) {
        if (videoWidth > 0 && videoHeight > 0) {
            // MPV already keeps the video aspect inside the Android surface. Keep this SurfaceView
            // at the container size to avoid a second MeasureHelper aspect pass causing first-frame
            // jumps or portrait videos being constrained by a landscape-sized surface.
            Log.d("MpvSurfaceRenderView", "Video size observed: $videoWidth x $videoHeight")
        }
    }

    override fun setVideoRotation(degree: Int) {
        // MPV内部已通过gpu-next VO根据视频元数据自动旋转画面
        // SurfaceView在独立窗口中渲染，不支持View rotation属性
        // 设置rotation会导致Surface渲染区域与View边界错位，造成画面偏移
        // resolveDisplayVideoSize()已返回旋转后的正确显示尺寸，MeasureHelper无需再处理旋转
        Log.d("MpvSurfaceRenderView", "Video rotation ignored: $degree (handled by MPV internally)")
    }

    override fun setScaleType(scaleType: Int) {
        // Let MPV handle fit/crop/stretch in the full surface instead of resizing SurfaceView.
        Log.d("MpvSurfaceRenderView", "Scale type ignored: $scaleType (handled by MPV)")
    }

    override fun getView(): View {
        return this
    }

    override fun doScreenShot(): Bitmap? {
        val player = mpv
        if (mMediaPlayer == null || player == null || mIsReleased) {
            Log.d("MpvSurfaceRenderView", "MPV is not initialized, cannot take screenshot")
            return null
        }

        try {
            // 创建临时文件保存截图
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val screenshotFile =
                FileOperator.buildPictureFile("DyLike", "Mpv_Screenshot_$timeStamp.png")
            val screenshotDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/DyLike/")
            if (screenshotDir.exists().not()) {
                screenshotDir.mkdirs()
            }

            Log.d(
                this,
                "screenshot-directory", player.getPropertyString("screenshot-directory"),
                "screenshot-dir", player.getPropertyString("screenshot-dir"),
                "screenshot-template", player.getPropertyString("screenshot-template"),
                screenshotDir.path
            )
            //mpv?.setOptionString("screenshot-directory", screenshotDir.path)
            // 0: 最快（压缩率最低，文件最大）1: 快速（压缩率略高，文件略小） 6: 默认值（平衡速度和压缩率）
            // 9: 最佳（压缩率最高，文件最小，但耗时最长）
            //mpv?.setOptionString("screenshot-png-compression", "9")
            //mpv?.setOptionString("screenshot-png-filter", "5")
            //mpv?.setOptionString("screenshot-format", "png")
            // --- 优化体积的关键配置 ---
            player.setOptionString("screenshot-format", "png")
            if (!screenshotOptionsConfigured) {
                player.setOptionString("screenshot-png-compression", "7") // 提高压缩率
                player.setOptionString("screenshot-png-filter", "5")      // 优化过滤
                screenshotOptionsConfigured = true
            }
            //mpv?.setOptionString("screenshot-high-bit-depth", "no") // 强制8bit
            //mpv?.setOptionString("screenshot-tag-colorspace", "no") // 忽略色彩空间
            // mpv-shot%n
            //mpv?.setOptionString("screenshot-template", "Mpv_Screenshot_%h_%p")
            // 使用MPV的截图命令
            //mpv?.command("screenshot", "video")
            //mpv?.setOptionString("screenshot-jpeg-quality", "100")
            // MPV command 必须在主线程投递；调用方一般在 IO 线程，用 latch 同步等待命令提交完成。
            // 命令本身只触发 native 截图，不等待文件落盘——文件轮询在调用线程做。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // 已在主线程：不能再 post + await。那样等于主线程等待自己投递的任务，
                // 必然走满 2s 超时，期间 UI（包括同一主线程驱动的弹幕绘制）整体冻结。
                // 这里只投递命令，截图结果交给调用方在后台线程重新读取。
                player.command("screenshot-to-file", screenshotFile.path, "video")
                Log.d("MpvSurfaceRenderView", "screenshot dispatched on main thread, return null")
                return null
            }
            val cmdLatch = CountDownLatch(1)
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    player.command("screenshot-to-file", screenshotFile.path, "video")
                } catch (cmdErr: Exception) {
                    Log.d("MpvSurfaceRenderView", "screenshot command failed: ${cmdErr.message}")
                } finally {
                    cmdLatch.countDown()
                }
            }
            if (!cmdLatch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                Log.d("MpvSurfaceRenderView", "screenshot command dispatch timed out")
                return null
            }

            repeat(10) {
                if (screenshotFile.exists() && screenshotFile.length() > 0L) {
                    return BitmapFactory.decodeFile(screenshotFile.path)
                }
                Thread.sleep(50)
            }
            Log.d("MpvSurfaceRenderView", "Screenshot file not created or empty")
        } catch (e: Exception) {
            Log.d("MpvSurfaceRenderView", "Error taking screenshot: ${e.message}")
        }

        return null
    }

    override fun release() {
        Log.d("MpvSurfaceRenderView", "Releasing render view")
        SurfaceRenderTrace.d("MpvSurfaceRenderView", "release created=$mIsSurfaceCreated bound=$mHasBoundDisplay")
        mIsReleased = true
        mIsSurfaceCreated = false
        mHasBoundDisplay = false
        holder.removeCallback(this)
        mMediaPlayer = null
        mpv = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredSize = mMeasureHelper.doMeasure(widthMeasureSpec, heightMeasureSpec)
        setMeasuredDimension(measuredSize[0], measuredSize[1])
        Log.d("MpvSurfaceRenderView", "Measured dimensions: ${measuredSize[0]}x${measuredSize[1]}")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("MpvSurfaceRenderView", "Surface created")
        SurfaceRenderTrace.d("MpvSurfaceRenderView", "surfaceCreated surface=${holder.surface} valid=${holder.surface?.isValid} frame=${holder.surfaceFrame} released=$mIsReleased")
        mIsSurfaceCreated = true

        val player = mMediaPlayer ?: return
        if (mIsReleased) return

        // Avoid hard-gating by isValid here. Some ROMs report false right after callback and then
        // never recover without a bind attempt.
        player.setDisplay(holder)
        mHasBoundDisplay = true
        post {
            if (!mIsReleased && mIsSurfaceCreated) {
                SurfaceRenderTrace.d("MpvSurfaceRenderView", "surfaceCreated fallback setDisplay surface=${holder.surface} valid=${holder.surface?.isValid}")
                mMediaPlayer?.setDisplay(holder)
            }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("MpvSurfaceRenderView", "Surface changed: ${width}x$height, format: $format")
        SurfaceRenderTrace.d("MpvSurfaceRenderView", "surfaceChanged width=$width height=$height format=$format surface=${holder.surface} valid=${holder.surface?.isValid} bound=$mHasBoundDisplay")
        val player = mpv ?: return
        if (mIsReleased) return
        try {
            player.setPropertyString("android-surface-size", "${width}x$height")
            SurfaceRenderTrace.d("MpvSurfaceRenderView", "surfaceChanged refresh setDisplay")
            mMediaPlayer?.setDisplay(holder)
            mHasBoundDisplay = true
        } catch (e: Exception) {
            Log.d("MpvSurfaceRenderView", "Error setting surface size: ${e.message}")
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("MpvSurfaceRenderView", "Surface destroyed")
        SurfaceRenderTrace.d("MpvSurfaceRenderView", "surfaceDestroyed surface=${holder.surface} valid=${holder.surface?.isValid} bound=$mHasBoundDisplay released=$mIsReleased")
        val player = mMediaPlayer
        if (!mIsReleased) {
            player?.setDisplay(null)
        }
        mHasBoundDisplay = false
        mIsSurfaceCreated = false
    }

}
