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
import `is`.xyz.mpv.MPVNode
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.mpv.MpvMediaPlayer
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.render.IRenderView
import xyz.doikki.videoplayer.render.MeasureHelper
import me.lingci.lib.player.util.SurfaceRenderTrace
import java.io.File
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
    private var swScreenshotConfigured = false

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
        // screenshot-raw 走 mpv_command_node，调用返回时像素已经在手里：
        // 不落盘、不做 PNG 编码/解码，也不再依赖「起播 1s 时 VO 是否已有帧」之外的时序。
        // 失败（VO 无帧 / 格式不支持）再回退到 screenshot-to-file。
        screenshotRaw(player)?.let { return it }
        return screenshotToFile(player)
    }

    /**
     * 用 `screenshot-raw` 同步取一帧。
     *
     * 返回 node 结构为 `{w, h, stride, format, data}`；format 常见 `rgb0`（每像素 4 字节、
     * alpha 恒 0）、`bgr0`、`rgba`/`bgra`，24 位时是 `rgb24`/`bgr24`。stride 可能大于
     * width*bpp，所以按行偏移读。
     */
    private fun screenshotRaw(player: MPV): Bitmap? {
        // mpv 的 command 系列在本项目里统一从主线程投递：直接在 IO 线程调 commandNode
        // 拿不到结果（实测静默返回 null，随后退回 screenshot-to-file）。这里 post 到主线程
        // 执行，再用 latch 把 node 同步回调用线程。
        val holder = arrayOfNulls<MPVNode>(1)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            enableSwScreenshot(player)
            holder[0] = runCatching { player.commandNode("screenshot-raw", "video") }.getOrNull()
        } else {
            val latch = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    enableSwScreenshot(player)
                    holder[0] = player.commandNode("screenshot-raw", "video")
                } catch (t: Throwable) {
                    Log.d("MpvSurfaceRenderView", "screenshot-raw invoke failed: ${t.message}")
                } finally {
                    latch.countDown()
                }
            }
            val done = try {
                latch.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                false
            }
            if (!done) {
                Log.d("MpvSurfaceRenderView", "screenshot-raw dispatch timeout")
                return null
            }
        }

        val node = holder[0]
        if (node == null) {
            Log.d("MpvSurfaceRenderView", "screenshot-raw returned null")
            return null
        }
        return try {
            val width = node.get("w")?.asInt()?.toInt()
            val height = node.get("h")?.asInt()?.toInt()
            val data = node.get("data")?.asByteArray()
            if (width == null || height == null || data == null) {
                Log.d("MpvSurfaceRenderView", "screenshot-raw missing fields keys=${node.keys()}")
                return null
            }
            val stride = node.get("stride")?.asInt()?.toInt() ?: (width * 4)
            val format = node.get("format")?.asString()?.lowercase(Locale.US) ?: "rgb0"
            if (width <= 0 || height <= 0 || stride <= 0) return null

            val bpp = if (format.startsWith("rgb24") || format.startsWith("bgr24")) 3 else 4
            if (data.size < stride * (height - 1) + width * bpp) {
                Log.d("MpvSurfaceRenderView", "screenshot-raw short buffer size=${data.size} fmt=$format")
                return null
            }
            val swapRedBlue = format.startsWith("bgr")
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                var src = y * stride
                var dst = y * width
                val end = dst + width
                while (dst < end) {
                    val c0 = data[src].toInt() and 0xFF
                    val c1 = data[src + 1].toInt() and 0xFF
                    val c2 = data[src + 2].toInt() and 0xFF
                    // rgb0/bgr0 的 alpha 恒为 0，直接当不透明处理
                    val alpha = if (bpp == 4) {
                        (data[src + 3].toInt() and 0xFF).let { if (it == 0) 0xFF else it }
                    } else {
                        0xFF
                    }
                    val r = if (swapRedBlue) c2 else c0
                    val b = if (swapRedBlue) c0 else c2
                    pixels[dst] = (alpha shl 24) or (r shl 16) or (c1 shl 8) or b
                    src += bpp
                    dst++
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also {
                Log.d(this, "screenshotRaw", width, height, format, stride)
            }
        } catch (t: Throwable) {
            Log.d("MpvSurfaceRenderView", "screenshot-raw failed: ${t.message}")
            null
        }
    }

    /**
     * gpu-next 下部分 mpv 构建的 screenshot-raw 只返回 `{w,h,format,stride}`、不带 `data` 像素，
     * 打开软截图（`screenshot-sw`）后才会走 CPU 读回并填充 data。只设一次。
     */
    private fun enableSwScreenshot(player: MPV) {
        if (swScreenshotConfigured) return
        runCatching { player.setPropertyString("screenshot-sw", "yes") }
            .onFailure { Log.d("MpvSurfaceRenderView", "enable screenshot-sw failed: ${it.message}") }
        swScreenshotConfigured = true
    }

    /** 兜底：命令投递 + 文件轮询。命令本身异步，最多等 500ms。 */
    private fun screenshotToFile(player: MPV): Bitmap? {
        try {
            // 缩略图临时文件写应用私有缓存：免存储权限、不污染相册，读写也更快
            val screenshotFile = File(
                context.cacheDir,
                "mpv-shot-${System.currentTimeMillis()}.png"
            )
            screenshotFile.parentFile?.mkdirs()
            player.setOptionString("screenshot-format", "png")
            if (!screenshotOptionsConfigured) {
                player.setOptionString("screenshot-png-compression", "7")
                player.setOptionString("screenshot-png-filter", "5")
                screenshotOptionsConfigured = true
            }
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

            // PNG 编码 1080p 约 100~300ms，给到 1s；读完即删，不占缓存
            repeat(20) {
                if (screenshotFile.exists() && screenshotFile.length() > 0L) {
                    val bitmap = BitmapFactory.decodeFile(screenshotFile.path)
                    screenshotFile.delete()
                    return bitmap
                }
                Thread.sleep(50)
            }
            screenshotFile.delete()
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
