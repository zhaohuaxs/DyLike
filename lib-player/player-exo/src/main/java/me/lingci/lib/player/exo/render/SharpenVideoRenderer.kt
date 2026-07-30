package me.lingci.lib.player.exo.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import me.lingci.lib.base.util.Log
import android.util.Log as AndroidLog
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 双 pass 锐化 GL Renderer（Mali GPU 优化版）。
 *
 * Pass 1 (blit):  OES 外部纹理 → RGBA FBO（1 次采样，YUV→RGB 只做 1 次）
 * Pass 2 (SGSR1): RGBA FBO → SGSR1 shader → 屏幕（~25 次采样，读快速 sampler2D）
 *
 * 这样避免了 SGSR1 的 25 次采样都走 OES 外部纹理（每次都做 YUV→RGB 转换），
 * 对 Mali GPU（麒麟处理器）性能提升 30-50%。
 *
 * 移植自 Google ExoPlayer demo + SGSR1 (Qualcomm) + Arm Mali 优化建议。
 */
class SharpenVideoRenderer(
    private val context: Context,
    private val onSurfaceTextureReady: (SurfaceTexture) -> Unit,
    private val useNcnn: Boolean = false
) : GLSurfaceView.Renderer {

    companion object {
        private const val SP_KEY_STRENGTH = "labSuperResolutionStrength"
        private const val SP_STRENGTH_DEFAULT = 1.0f
        private const val STRENGTH_MIN = 0.0f
        private const val STRENGTH_MAX = 3.0f
    }

    private val frameAvailable = AtomicBoolean(false)
    private val transformMatrix = FloatArray(16)

    // OES 外部纹理（ExoPlayer 写入）
    private var textureId = 0
    private var surfaceTexture: SurfaceTexture? = null

    // Pass 1: blit program (OES → RGBA FBO)
    private var blitProgram: GlProgram? = null

    // Pass 2: SGSR1 program (RGBA FBO → screen)
    private var sgsrProgram: GlProgram? = null

    // RGBA FBO（Pass 1 输出，Pass 2 输入）
    private var fboId = 0
    private var fboTextureId = 0
    private var fboWidth = 0
    private var fboHeight = 0

    private var glSurfaceViewRef: WeakReference<GLSurfaceView>? = null
    private var videoWidth = 0
    private var videoHeight = 0

    // ===== NCNN 字段 =====
    private var ncnnSr: me.lingci.lib.player.exo.ncnn.NcnnSuperResolution? = null
    private var frameCount = 0
    private var ncnnOutputTexId = 0
    private var blitProgramForNcnn: GlProgram? = null

    // GPU 缩放 FBO（320×180，减少 glReadPixels 数据量）
    private var smallFboId = 0
    private var smallFboTexId = 0
    private val SMALL_W = 320
    private val SMALL_H = 180
    // 用于 CPU 读取像素的 reusable buffer(同步路径 fallback)
    private var smallPixelBuffer: java.nio.ByteBuffer? = null

    // PBO 双缓冲（异步 glReadPixels）
    private val pboIds = IntArray(2)
    private var pboIndex = 0  // 当前写入的 PBO
    private var pboInitialized = false
    private val pboSize = SMALL_W * SMALL_H * 4
    private var pboReadPending = false  // 上一帧的 PBO 是否可以读取

    // 异步推理
    private val ncnnExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var released = false
    @Volatile private var ncnnInferInProgress = false
    @Volatile private var ncnnResultReady = false
    @Volatile private var ncnnResultBytes: ByteArray? = null
    @Volatile private var ncnnResultWidth = 0
    @Volatile private var ncnnResultHeight = 0
    private var ncnnHasValidResult = false

    private val sharpenAmount: Float = try {
        val sp = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sp.getFloat(SP_KEY_STRENGTH, SP_STRENGTH_DEFAULT)
        raw.coerceIn(STRENGTH_MIN, STRENGTH_MAX)
    } catch (_: Throwable) {
        SP_STRENGTH_DEFAULT
    }

    fun bindGlSurfaceView(view: GLSurfaceView) {
        glSurfaceViewRef = WeakReference(view)
    }

    /**
     * 后台播放恢复复用：暴露已创建的 SurfaceTexture，供 GlRenderView 重新 setSurface。
     * GL 线程未销毁时 surfaceTexture 仍有效；返回 null 表示尚未 onSurfaceCreated。
     */
    fun getSurfaceTexture(): SurfaceTexture? = surfaceTexture

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // 1. 创建 OES external texture
            textureId = GlUtil.createExternalTexture()
            // 2. 创建 SurfaceTexture
            surfaceTexture = SurfaceTexture(textureId).apply {
                setOnFrameAvailableListener { _ ->
                    frameAvailable.set(true)
                    glSurfaceViewRef?.get()?.requestRender()
                }
            }
            // 3. 通知外层
            onSurfaceTextureReady(surfaceTexture!!)

            // 4. 编译两个 shader program
            // Pass 1: blit (OES → RGBA)
            blitProgram = GlProgram(
                context,
                "shaders/video_vertex_es2.glsl",
                "shaders/blit_oes_to_rgba_es2.glsl"
            ).apply {
                setBufferAttribute("aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                setBufferAttribute("aTexCoords",
                    GlUtil.getTextureCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

            // Pass 2: SGSR1 (RGBA → screen)
            sgsrProgram = GlProgram(
                context,
                "shaders/video_vertex_es2.glsl",
                "shaders/sgsr_fragment_es2.glsl"
            ).apply {
                setBufferAttribute("aFramePosition",
                    GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                setBufferAttribute("aTexCoords",
                    GlUtil.getTextureCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }

            Log.d("SharpenVideoRenderer", "onSurfaceCreated OK (dual-pass), textureId=$textureId")

            // ===== NCNN 初始化 =====
            if (useNcnn) {
                ncnnSr = me.lingci.lib.player.exo.ncnn.NcnnSuperResolution().also {
                    val ok = it.init(context)
                    Log.d("SharpenVideoRenderer", "NCNN init: $ok")
                }
                // 创建 NCNN 输出纹理（2x 分辨率）
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                ncnnOutputTexId = texIds[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                // blit shader 用于渲染 NCNN 输出（跟 Pass 1 的 blit 类似，但读 sampler2D 而不是 OES）
                blitProgramForNcnn = GlProgram(
                    context,
                    "shaders/video_vertex_es2.glsl",
                    "shaders/blit_rgba_es2.glsl"
                ).apply {
                    setBufferAttribute("aFramePosition",
                        GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                    setBufferAttribute("aTexCoords",
                        GlUtil.getTextureCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
                }
            }
        } catch (e: Exception) {
            AndroidLog.e("SharpenVideoRenderer", "onSurfaceCreated failed: ${e.message}")
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // 更新 OES 纹理（如果有新帧）
        if (frameAvailable.compareAndSet(true, false)) {
            try {
                surfaceTexture?.updateTexImage()
                surfaceTexture?.getTransformMatrix(transformMatrix)
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "updateTexImage failed: ${e.message}")
            }
        }

        // 确保 FBO 尺寸匹配视频尺寸
        ensureFbo()

        // ===== Pass 1: OES → RGBA FBO（总是执行） =====
        blitOesToFbo()

        if (useNcnn && ncnnSr?.initialized == true && fboId != 0) {
            // 渲染优先（每帧都渲染，保证流畅）
            drawNcnnResultToScreen()

            // 异步提交：PBO 异步读取，几乎不阻塞 GL 线程
            if (!ncnnInferInProgress || pboReadPending) {
                submitNcnnInference()
            }
        } else {
            drawFrameWithSgsr1()
        }
        frameCount++
    }

    /** 渲染 NCNN 超分结果到屏幕（只做渲染，不做读取/推理） */
    private fun drawNcnnResultToScreen() {
        // 如果有新推理结果，上传到纹理
        if (ncnnResultReady && ncnnResultBytes != null) {
            ncnnResultReady = false
            ncnnHasValidResult = true
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    ncnnResultWidth, ncnnResultHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    java.nio.ByteBuffer.wrap(ncnnResultBytes))
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "NCNN upload failed: ${e.message}")
            }
        }

        // 渲染
        if (ncnnHasValidResult) {
            val view = glSurfaceViewRef?.get()
            if (view != null) GLES20.glViewport(0, 0, view.width, view.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            blitProgramForNcnn?.let { p ->
                p.use()
                p.setSamplerTexIdUniform("uTexSampler", ncnnOutputTexId, 0)
                p.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
        } else {
            drawFrameWithSgsr1()
        }
    }

    /** 异步提交 NCNN 推理（PBO 异步读取 + GPU 降采样） */
    private fun submitNcnnInference() {
        val sr = ncnnSr ?: return
        if (!sr.initialized || fboWidth <= 0) return

        ncnnInferInProgress = true
        try {
            ensureSmallFbo()
            ensurePbo()

            // === 第一步：读取上一帧的 PBO 数据（如果就绪）===
            // PBO 读取是异步的：上一帧 glReadPixels 已经发起 DMA，现在数据应该在内存了
            if (pboReadPending) {
                val prevPbo = pboIds[(pboIndex + 1) % 2]
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, prevPbo)
                // glMapBufferRange 几乎不等待（DMA 已完成）
                val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, pboSize,
                    GLES30.GL_MAP_READ_BIT)
                if (mapped != null) {
                    (mapped as java.nio.ByteBuffer).rewind()
                    val inputBytes = ByteArray(pboSize)
                    (mapped as java.nio.ByteBuffer).get(inputBytes)
                    GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)

                    // 提交到后台线程推理
                    ncnnExecutor.execute {
                        // release() 已触发：不要再触碰 native 资源（避免 use-after-free）
                        if (released) {
                            ncnnInferInProgress = false
                            return@execute
                        }
                        try {
                            val ncnnOut = sr.infer(inputBytes, SMALL_W, SMALL_H)
                            if (ncnnOut != null) {
                                ncnnResultBytes = ncnnOut.first
                                ncnnResultWidth = ncnnOut.second
                                ncnnResultHeight = ncnnOut.third
                                ncnnResultReady = true
                            }
                        } catch (e: Exception) {
                            AndroidLog.e("SharpenVideoRenderer", "NCNN async infer failed: ${e.message}")
                        } finally {
                            ncnnInferInProgress = false
                        }
                    }
                    pboReadPending = false
                    // 不要在这里 return，继续做 GPU blit + 发起新的 PBO 写入
                } else {
                    GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
                    pboReadPending = false
                    ncnnInferInProgress = false
                    return
                }
            } else {
                ncnnInferInProgress = false
            }

            // === 第二步：GPU blit 到小 FBO（320×180，GPU 操作不阻塞）===
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFboId)
            GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            blitProgramForNcnn!!.use()
            blitProgramForNcnn!!.setSamplerTexIdUniform("uTexSampler", fboTextureId, 0)
            blitProgramForNcnn!!.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
            blitProgramForNcnn!!.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            // === 第三步：异步 PBO 写入（glReadPixels 到 PBO，立即返回）===
            val curPbo = pboIds[pboIndex]
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, curPbo)
            // 这个调用立即返回！DMA 异步传输，不阻塞 GL 线程
            GLES30.glReadPixels(0, 0, SMALL_W, SMALL_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

            pboIndex = (pboIndex + 1) % 2
            pboReadPending = true
        } catch (e: Exception) {
            AndroidLog.e("SharpenVideoRenderer", "NCNN submit failed: ${e.message}")
            ncnnInferInProgress = false
        }
    }

    /** 初始化 PBO 双缓冲 */
    private fun ensurePbo() {
        if (pboInitialized) return
        GLES30.glGenBuffers(2, pboIds, 0)
        for (i in 0..1) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i])
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, pboSize, null, GLES30.GL_DYNAMIC_READ)
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        pboInitialized = true
        Log.d("SharpenVideoRenderer", "PBO initialized: 2x ${SMALL_W}x${SMALL_H} = ${pboSize} bytes each")
    }

    /** Pass 1: OES → RGBA FBO */
    private fun blitOesToFbo() {
        if (fboId == 0 || blitProgram == null) return
        try {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
            GLES20.glViewport(0, 0, fboWidth, fboHeight)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            blitProgram!!.use()
            blitProgram!!.setSamplerTexIdUniform("uVideoTex", textureId, 0)
            blitProgram!!.setFloatsUniform("uTexTransform", transformMatrix)
            blitProgram!!.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        } catch (e: GlUtil.GlException) {
            AndroidLog.e("SharpenVideoRenderer", "Pass 1 blit failed: ${e.message}")
        }
    }

    /** Pass 2 (SGSR1): RGBA FBO → SGSR1 shader → 屏幕 */
    private fun drawFrameWithSgsr1() {
        if (fboId == 0 || fboTextureId == 0) return
        sgsrProgram?.let { p ->
            try {
                val view = glSurfaceViewRef?.get()
                if (view != null) {
                    GLES20.glViewport(0, 0, view.width, view.height)
                }
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                p.use()
                p.setSamplerTexIdUniform("uVideoTex", fboTextureId, 0)
                p.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                if (videoWidth > 0 && videoHeight > 0) {
                    p.setFloatsUniformIfPresent("uViewportInfo",
                        floatArrayOf(1f / videoWidth, 1f / videoHeight, videoWidth.toFloat(), videoHeight.toFloat()))
                }
                p.setFloatsUniformIfPresent("uSharpenAmount", floatArrayOf(sharpenAmount))
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            } catch (e: GlUtil.GlException) {
                AndroidLog.e("SharpenVideoRenderer", "Pass 2 SGSR1 failed: ${e.message}")
            }
        }
    }

    /**
     * 异步 NCNN 路径（无闪烁版）：
     * - 有新推理结果 → 上传 + 渲染超分帧
     * - 没有新结果但已有旧结果 → 重复渲染上一帧超分结果（不闪）
     * - 从未有过结果（前几帧）→ 用 SGSR1 过渡（只在最开始）
     * - 后台没在推理 → 提交当前帧
     */
    private fun drawFrameWithNcnn(): Boolean {
        val sr = ncnnSr
        if (sr == null || !sr.initialized || fboWidth <= 0 || fboHeight <= 0) {
            drawFrameWithSgsr1()
            return false
        }

        // 1. 如果有新推理结果，上传到纹理
        if (ncnnResultReady && ncnnResultBytes != null) {
            ncnnResultReady = false
            ncnnHasValidResult = true
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ncnnOutputTexId)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                    ncnnResultWidth, ncnnResultHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    java.nio.ByteBuffer.wrap(ncnnResultBytes))
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "NCNN upload failed: ${e.message}")
            }
        }

        // 2. 提交推理：GPU 缩放到 640×360 FBO → glReadPixels 小 FBO → NCNN
        if (!ncnnInferInProgress) {
            ncnnInferInProgress = true
            try {
                // GPU 缩放：用 RGBA blit shader（不是 OES blit）把源 FBO 纹理缩放到小 FBO
                ensureSmallFbo()
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFboId)
                GLES20.glViewport(0, 0, SMALL_W, SMALL_H)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // 用 blitProgramForNcnn（读 sampler2D，不是 OES）来缩放
                blitProgramForNcnn!!.use()
                blitProgramForNcnn!!.setSamplerTexIdUniform("uTexSampler", fboTextureId, 0)
                blitProgramForNcnn!!.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                blitProgramForNcnn!!.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                // 从小 FBO 读像素（640×360×4 = 0.9MB，比 1080p 的 8MB 快 9 倍）
                GLES20.glReadPixels(0, 0, SMALL_W, SMALL_H, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ensureSmallPixelBuffer())
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                smallPixelBuffer!!.rewind()
                val inputBytes = ByteArray(SMALL_W * SMALL_H * 4)
                smallPixelBuffer!!.get(inputBytes)

                ncnnExecutor.execute {
                    // release() 已触发：不要再触碰 native 资源（避免 use-after-free）
                    if (released) {
                        ncnnInferInProgress = false
                        return@execute
                    }
                    try {
                        val ncnnOut = sr.infer(inputBytes, SMALL_W, SMALL_H)
                        if (ncnnOut != null) {
                            ncnnResultBytes = ncnnOut.first
                            ncnnResultWidth = ncnnOut.second
                            ncnnResultHeight = ncnnOut.third
                            ncnnResultReady = true
                        }
                    } catch (e: Exception) {
                        AndroidLog.e("SharpenVideoRenderer", "NCNN async infer failed: ${e.message}")
                    } finally {
                        ncnnInferInProgress = false
                    }
                }
            } catch (e: Exception) {
                AndroidLog.e("SharpenVideoRenderer", "NCNN submit failed: ${e.message}")
                ncnnInferInProgress = false
            }
        }

        // 3. 渲染到屏幕（不再每帧重新上传纹理，只在有新结果时上传）
        if (ncnnHasValidResult) {
            val view = glSurfaceViewRef?.get()
            if (view != null) GLES20.glViewport(0, 0, view.width, view.height)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            blitProgramForNcnn?.let { p ->
                p.use()
                p.setSamplerTexIdUniform("uTexSampler", ncnnOutputTexId, 0)
                p.setFloatsUniform("uTexTransform", GlUtil.create4x4IdentityMatrix())
                p.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            return true
        } else {
            drawFrameWithSgsr1()
            return false
        }
    }

    /** 创建 640×360 小 FBO（GPU 缩放目标） */
    private fun ensureSmallFbo() {
        if (smallFboId != 0) return
        // 纹理
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        smallFboTexId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smallFboTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            SMALL_W, SMALL_H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        // FBO
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        smallFboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smallFboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, smallFboTexId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun ensureSmallPixelBuffer(): java.nio.ByteBuffer {
        val size = SMALL_W * SMALL_H * 4
        if (smallPixelBuffer == null || smallPixelBuffer!!.capacity() < size) {
            smallPixelBuffer = java.nio.ByteBuffer.allocateDirect(size)
        }
        smallPixelBuffer!!.rewind()
        return smallPixelBuffer!!
    }

    private fun ensurePixelBuffer(): java.nio.ByteBuffer {
        val size = fboWidth * fboHeight * 4
        if (smallPixelBuffer == null || smallPixelBuffer!!.capacity() < size) {
            smallPixelBuffer = java.nio.ByteBuffer.allocateDirect(size)
        }
        smallPixelBuffer!!.rewind()
        return smallPixelBuffer!!
    }

    /**
     * 简单的最近邻缩放 RGBA byte[]。
     * 在 CPU 后台线程调用，不占 GL 线程。
     */
    private fun scaleDownRgba(src: ByteArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        val dst = ByteArray(dstW * dstH * 4)
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            val sy = (dy * yRatio).toInt().coerceIn(0, srcH - 1)
            for (dx in 0 until dstW) {
                val sx = (dx * xRatio).toInt().coerceIn(0, srcW - 1)
                val srcIdx = (sy * srcW + sx) * 4
                val dstIdx = (dy * dstW + dx) * 4
                dst[dstIdx]     = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
                dst[dstIdx + 2] = src[srcIdx + 2]
                dst[dstIdx + 3] = src[srcIdx + 3]
            }
        }
        return dst
    }

    /** 创建或调整 RGBA FBO 尺寸以匹配视频分辨率。 */
    private fun ensureFbo() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        if (fboWidth == videoWidth && fboHeight == videoHeight && fboId != 0) return

        // 删旧 FBO
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }

        // 创建新 RGBA 纹理
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTextureId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            videoWidth, videoHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 创建 FBO
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        fboWidth = videoWidth
        fboHeight = videoHeight
        Log.d("SharpenVideoRenderer", "FBO created: ${fboWidth}x${fboHeight}")
    }

    fun onVideoSizeChanged(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
        }
    }

    fun readFramebuffer(): android.graphics.Bitmap? {
        return null  // Phase 3 加
    }

    fun release() {
        try {
            // 1. 先置位 released：阻止新的投递 + 让后台任务早退
            released = true

            // 2. 优雅关闭 executor，等待正在执行的 infer() 结束。
            //    不能用 shutdownNow()：它会立刻中断线程，但 NCNN/OpenMP 内部的
            //    pthread_mutex 并不响应 Java 中断，native 仍在跑，主线程随后调用
            //    nativeRelease() → sr_net.clear() 会触发 use-after-free / destroyed-mutex 崩溃。
            ncnnExecutor.shutdown()
            try {
                if (!ncnnExecutor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    // 兜底：3 秒还跑不完（理论上单帧 ~50-100ms），强制停
                    ncnnExecutor.shutdownNow()
                    ncnnExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: InterruptedException) {
                ncnnExecutor.shutdownNow()
                Thread.currentThread().interrupt()
            }

            // 3. 此时所有 native 推理已结束，安全释放
            surfaceTexture?.release()
            blitProgram?.delete()
            sgsrProgram?.delete()
            blitProgramForNcnn?.delete()
            if (fboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
                GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            }
            if (smallFboId != 0) {
                GLES20.glDeleteFramebuffers(1, intArrayOf(smallFboId), 0)
                GLES20.glDeleteTextures(1, intArrayOf(smallFboTexId), 0)
            }
            if (ncnnOutputTexId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(ncnnOutputTexId), 0)
            }
            ncnnSr?.release()
        } catch (_: Exception) {}
    }
}
