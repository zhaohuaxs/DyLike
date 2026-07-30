package me.lingci.lib.player.exo.ncnn

import android.content.Context
import android.content.res.AssetManager
import android.util.Log

/**
 * NCNN 神经网络超分封装。
 *
 * 使用 Real-ESRGANv3-anime x2 模型，通过 NCNN Vulkan GPU 推理。
 * 单帧推理 ~51ms（Adreno 840）。
 *
 * 使用方式：
 *   val sr = NcnnSuperResolution()
 *   sr.init(context)  // 加载模型
 *   val output = sr.infer(inputRgba, width, height)  // 推理
 *   sr.release()  // 释放
 */
class NcnnSuperResolution {
    companion object {
        private const val TAG = "NcnnSuperResolution"
        private const val PARAM_PATH = "models/realesr-animevideov3-x2.param"
        private const val BIN_PATH = "models/realesr-animevideov3-x2.bin"

        init {
            System.loadLibrary("ncnn-sr")
        }
    }

    var initialized = false
        private set

    fun init(context: Context): Boolean {
        if (initialized) return true
        try {
            initialized = nativeInit(context.assets, PARAM_PATH, BIN_PATH)
            Log.i(TAG, "init result: $initialized")
        } catch (e: Throwable) {
            Log.e(TAG, "init failed: ${e.message}")
            initialized = false
        }
        return initialized
    }

    /**
     * 对一帧 RGBA 数据做 2x 超分。
     * 返回 Triple<数据, 宽, 高>，或 null 如果失败。
     */
    fun infer(input: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int>? {
        if (!initialized) return null
        return try {
            val result = nativeInferAlloc(input, width, height)
            if (result != null && result.size > 8) {
                // JNI 返回格式: [outW(int32 LE)][outH(int32 LE)][RGBA pixels...]
                val buf = java.nio.ByteBuffer.wrap(result, 0, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val outW = buf.int
                val outH = buf.int
                val pixels = ByteArray(result.size - 8)
                System.arraycopy(result, 8, pixels, 0, pixels.size)
                Triple(pixels, outW, outH)
            } else null
        } catch (e: Throwable) {
            Log.e(TAG, "infer failed: ${e.message}")
            null
        }
    }

    fun release() {
        if (initialized) {
            nativeRelease()
            initialized = false
        }
    }

    private external fun nativeInit(
        assetManager: AssetManager,
        paramPath: String,
        binPath: String
    ): Boolean

    /**
     * 推理并返回结果。JNI 内部分配正确大小的 buffer。
     * 返回 ByteArray 格式：[outW(4bytes)][outH(4bytes)][RGBA pixels...]
     * 或 null 如果失败。
     */
    private external fun nativeInferAlloc(
        input: ByteArray, width: Int, height: Int
    ): ByteArray?

    private external fun nativeRelease()
}
