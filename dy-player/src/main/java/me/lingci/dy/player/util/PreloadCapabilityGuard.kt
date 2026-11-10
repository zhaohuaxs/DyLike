package me.lingci.dy.player.util

import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 预载能力诊断与失败规格传播。
 *
 * 不按尺寸一刀切拒绝预载：本机（骁龙 845）硬解声明上限是 4096x2304，
 * 但 1440x2560 这类边界规格实测「有时能解、有时失败」，直接跳过会损失本可拿到的首帧。
 * 这里的职责是：
 *   1) 读出并缓存视频规格，供日志诊断与「快滑阶段是否跳过边界规格」判断；
 *   2) 某个规格预载失败后，把失败传播到同规格的其它视频，避免快滑时逐个重试硬解。
 *
 * 规格读取仅限本地文件；网络源 / SMB / WebDAV 或读取失败一律按「未知」处理，
 * 由 ShortVideoActivity 的 url 级冷却兜底，不会误伤正常视频。
 */
object PreloadCapabilityGuard {

    private const val UNKNOWN = "-"
    private const val MAX_HW_WIDTH = 4096
    private const val MAX_HW_HEIGHT = 2304
    private const val SAFE_WIDTH = 1920
    private const val SAFE_HEIGHT = 1920
    private const val FAILURE_COOLDOWN_MS = 30_000L

    /** url -> "宽x高"，读不到记为 UNKNOWN。 */
    private val urlSpecs = ConcurrentHashMap<String, String>()
    /** "宽x高" -> 最近一次预载失败时刻，用于跨视频传播。 */
    private val failedSpecs = ConcurrentHashMap<String, Long>()

    /** IO 线程调用：读取并缓存规格，返回 "WxH" 或 null。 */
    fun inspect(url: String): String? {
        urlSpecs[url]?.let { cached -> return cached.takeIf { it != UNKNOWN } }
        val key = if (!isLocalPath(url)) {
            null
        } else {
            val spec = readSpec(url)
            if (spec == null) null else "${spec.first}x${spec.second}"
        }
        urlSpecs[url] = key ?: UNKNOWN
        Log.d(
            "lcDev",
            "preload capability: ${key ?: "unknown"}, " +
                "oversize=${key != null && isOversizeKey(key)}"
        )
        return key
    }

    /** 预载失败时调用：把失败记录传播到该 url 对应的规格。 */
    fun markFailure(url: String) {
        val key = urlSpecs[url] ?: return
        if (key == UNKNOWN) return
        failedSpecs[key] = SystemClock.elapsedRealtime()
    }

    /** 该 url 所属规格近期失败过：不再重复初始化解码器。 */
    fun isKnownUnsupported(url: String): Boolean {
        val key = urlSpecs[url] ?: return false
        if (key == UNKNOWN) return false
        val failedAt = failedSpecs[key] ?: return false
        if (SystemClock.elapsedRealtime() - failedAt < FAILURE_COOLDOWN_MS) return true
        failedSpecs.remove(key)
        return false
    }

    /** 规格是否超出主流硬解声明上限，仅用于日志标注，不据此跳过预载。 */
    private fun isOversizeKey(key: String): Boolean {
        val parts = key.split("x")
        val width = parts.getOrNull(0)?.toIntOrNull() ?: return false
        val height = parts.getOrNull(1)?.toIntOrNull() ?: return false
        // 常规规格（含竖屏 1080x1920）不算边界
        if (width <= SAFE_WIDTH && height <= SAFE_HEIGHT) return false
        return width > MAX_HW_WIDTH || height > MAX_HW_HEIGHT
    }

    private fun isLocalPath(url: String): Boolean =
        url.startsWith("/") || url.startsWith("file://")

    private fun readSpec(url: String): Pair<Int, Int>? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url.removePrefix("file://"))
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width > 0 && height > 0) width to height else null
        } catch (e: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Throwable) {
            }
        }
    }
}
