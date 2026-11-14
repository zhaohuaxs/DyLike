package me.lingci.dy.player.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.thumb.RemoteThumbSource
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.deleteExists
import java.io.File
import java.io.FileOutputStream

/**
 *   @author : happyc
 *   time    : 2025/01/20
 *   desc    : 视频抽帧与缩略图落盘
 */
object MediaManger {

    /** 列表缩略图统一宽度：moto XT2125-4 实测 1920 宽 WEBP 编码 153ms/张，480 宽仅 14ms。 */
    const val THUMB_MAX_WIDTH = 480
    const val THUMB_QUALITY = 70

    /** 保留给外部按时间点取帧用；批量缩略图请走 [extractThumbnail]，避免反复重建 MMR。 */
    fun getVideoFrame(
        videoPath: String,
        timeUs: Long,
        option: Int = MediaMetadataRetriever.OPTION_CLOSEST
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            retriever.getFrameAtTime(timeUs, option)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }

    suspend fun getMediaInfo(filePath: String) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            println("Duration: $duration Width: $width Height: $height Artist: $artist Title: $title")
            println("${retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)}")
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }
    }

    fun getVideoFirstFrame(videoPath: String): Bitmap? =
        getVideoFrame(videoPath, 0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

    /** 等比缩到 [maxWidth]；原图不回收，由调用方决定。 */
    fun scaleForThumb(bitmap: Bitmap, maxWidth: Int = THUMB_MAX_WIDTH): Bitmap {
        if (bitmap.width <= maxWidth) return bitmap
        val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val newHeight = (maxWidth / ratio).toInt().coerceAtLeast(1)
        return bitmap.scale(maxWidth, newHeight)
    }

    /**
     * 取一张可用缩略图：一次 setDataSource 内按「首帧 → 片长 10% → 1s → 3s」尝试，
     * 用 [ThumbQuality.isMostlyBlack] 过滤黑屏片头，返回前统一缩到 [maxWidth]。
     *
     * 原实现固定取第 0 帧且不判黑，真机缓存中 21.1% 是纯黑首帧；片头 10% 的候选
     * 来自 FormaUIChatDemo 的做法（同设备黑帧率 3.8%）。全部候选都黑时保留一帧，
     * 避免文件始终不存在导致每次进页面重复抽帧。
     */
    fun extractThumbnail(videoPath: String, maxWidth: Int = THUMB_MAX_WIDTH): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            pickFrame(retriever, maxWidth)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 远程视频抽帧：`MediaMetadataRetriever` 从 [android.media.MediaDataSource] 随机读，
     * 不经过播放器，因此 mpv / Exo / SurfaceView 下行为一致，也不受起播缓冲影响。
     */
    fun extractThumbnail(source: MediaDataSource, maxWidth: Int = THUMB_MAX_WIDTH): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source)
            pickFrame(retriever, maxWidth)
        } catch (e: Exception) {
            Log.d(this, "remote extract failed", e.message)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 按视频来源自动选择抽帧方式：本地走路径，WebDAV/HTTP 走 Range 数据源，SMB 走 SMB 数据源。
     *
     * 返回 null 的两种情况：HLS/DASH 清单无法被 MediaMetadataRetriever 解复用；
     * 数据源打开或解复用失败。调用方应回退到播放器截图。
     *
     * 必须在 IO 线程调用（内部有网络/磁盘读）。
     */
    fun extractThumbnail(video: VideoData, maxWidth: Int = THUMB_MAX_WIDTH): Bitmap? {
        val url = video.videoUrl
        val localPath = when {
            url.startsWith("/") -> url
            url.startsWith("file://") -> runCatching { Uri.parse(url).path }.getOrNull()
            else -> null
        }
        if (localPath != null) return extractThumbnail(localPath, maxWidth)

        val source = RemoteThumbSource.open(video) ?: return null
        val start = SystemClock.elapsedRealtime()
        return try {
            extractThumbnail(source, maxWidth)?.also {
                Log.d(this, "remote thumb", url.substringBefore('?'), "cost=${SystemClock.elapsedRealtime() - start}ms")
            }
        } finally {
            runCatching { source.close() }
        }
    }

    /** 在已打开的 retriever 上按候选时间点挑一帧非黑画面。 */
    private fun pickFrame(retriever: MediaMetadataRetriever, maxWidth: Int): Bitmap? {
        var fallback: Bitmap? = null
        try {
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // 微秒 = durationMs * 1000 / 10；至少 0.5s，无时长信息时取 10s
            val tenPercentUs = if (durationMs > 0L) {
                (durationMs * 100L).coerceAtLeast(500_000L)
            } else {
                10_000_000L
            }
            val candidates = linkedSetOf(0L, tenPercentUs, 1_000_000L, 3_000_000L)
            for (timeUs in candidates) {
                val raw = retriever.getFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: continue
                if (!ThumbQuality.isMostlyBlack(raw)) {
                    fallback?.recycle()
                    return scaleAndRecycle(raw, maxWidth)
                }
                if (fallback == null) fallback = raw else raw.recycle()
            }
            val last = fallback ?: return null
            fallback = null
            return scaleAndRecycle(last, maxWidth)
        } catch (e: Exception) {
            fallback?.recycle()
            return null
        }
    }

    private fun scaleAndRecycle(bitmap: Bitmap, maxWidth: Int): Bitmap {
        val scaled = scaleForThumb(bitmap, maxWidth)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * 生成本地视频的列表缩略图。
     *
     * - 已存在的缓存要过一遍黑帧校验，旧版留下的纯黑图会被删掉重生成；
     * - 先写 .tmp 再原子改名，避免失败留下半截文件被当成有效缓存；
     * - 抽帧与压缩都在 IO，逐条串行，页面切换时按 isActive 提前退出。
     */
    suspend fun scanVideoThumb(context: Context, videos: List<VideoData>) =
        withContext(Dispatchers.IO) {
            var generated = 0
            var replaced = 0
            var reused = 0
            for (video in videos) {
                if (!isActive) return@withContext
                // HLS/DASH 清单无法被 MediaMetadataRetriever 解复用，跳过。
                // 其余本地/WebDAV/SMB 都走同一套抽帧，列表不再只有本地视频有缩略图。
                if (RemoteThumbSource.isManifest(video.videoUrl)) continue
                val file = AppUtil.buildThumbFile(context, video.md5())
                if (ThumbQuality.isUsableThumbFile(file)) {
                    reused++
                    continue
                }
                if (file.exists()) replaced++
                val bitmap = extractThumbnail(video) ?: continue
                val tmp = File(file.parentFile, "${file.name}.tmp")
                try {
                    file.parentFile?.mkdirs()
                    FileOutputStream(tmp).use { fos ->
                        if (!bitmap.compress(AppUtil.COMPRESS_FORMAT, THUMB_QUALITY, fos)) {
                            error("thumbnail compress failed")
                        }
                        fos.flush()
                    }
                    if (tmp.length() > 0L) {
                        if (file.exists()) file.delete()
                        if (tmp.renameTo(file)) generated++ else file.deleteExists()
                    }
                } catch (e: Exception) {
                    file.deleteExists()
                } finally {
                    if (tmp.exists()) tmp.delete()
                    bitmap.recycle()
                }
            }
            Log.d(this, "scanVideoThumb", "generated=$generated replaced=$replaced reused=$reused")
        }
}
