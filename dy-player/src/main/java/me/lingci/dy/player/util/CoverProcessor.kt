package me.lingci.dy.player.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.annotation.SuppressLint
import android.os.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.lib.base.util.FileOperator
import java.io.File
import java.io.FileOutputStream

object CoverProcessor {

    suspend fun processCovers(context: Context, list: List<MediaData>) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val coverUpdates = mutableMapOf<String, String>()
        for (media in list) {
            if (!isActive) return@withContext
            if (media.type != MediaLibType.LOCAL) continue
            // CUSTOM 是用户自选封面，绝不覆盖
            if (media.coverType == CoverType.CUSTOM) continue
            // DEFAULT 是「允许自动匹配」的初始态，必须处理；AUTO 且已有值才跳过。
            // 统一按「已有可用封面值」判断，避免把 DEFAULT 误当成已完成态。
            if (media.showFile.isNotBlank()) continue

            val coverFile = File(media.coverPath())
            if (coverFile.exists()) continue

            val videos = FileOperator.getSortedFiles(File(media.path), FileOperator.VIDEO_EXTENSIONS)
            if (videos.isEmpty()) continue

            val firstVideo = videos[0]
            val videoData = VideoData(firstVideo)
            val thumbFile = AppUtil.buildThumbFile(appContext, videoData.md5())

            if (thumbFile.exists()) {
                if (isUsableCoverThumb(thumbFile)) {
                    coverUpdates[media.id] = thumbFile.absolutePath
                    continue
                }
                thumbFile.delete()
            }

            try {
                val bitmap = selectCoverFrame(firstVideo.absolutePath)
                if (bitmap != null) {
                    thumbFile.parentFile?.mkdirs()
                    FileOutputStream(thumbFile).use { fos ->
                        bitmap.compress(AppUtil.COMPRESS_FORMAT, 70, fos)
                    }
                    bitmap.recycle()
                    coverUpdates[media.id] = thumbFile.absolutePath
                }
            } catch (e: Exception) {
                thumbFile.delete()
            }
        }

        if (coverUpdates.isNotEmpty()) {
            coverUpdates.forEach { (mediaId, showFile) ->
                val media = DatabaseManager.mediaDataDao.getById(mediaId) ?: return@forEach
                if (media.showFile != showFile || media.coverType != CoverType.AUTO) {
                    media.showFile = showFile
                    media.coverType = CoverType.AUTO
                    DatabaseManager.mediaDataDao.upsert(media)
                }
            }
        }
    }

    private fun selectCoverFrame(videoPath: String): Bitmap? {
        val fallbackTimes = longArrayOf(0L, 1_000_000L, 3_000_000L)
        for (timeUs in fallbackTimes) {
            val bitmap = if (timeUs == 0L) {
                MediaManger.getVideoFirstFrame(videoPath)
            } else {
                MediaManger.getVideoFrame(videoPath, timeUs)
            } ?: continue
            if (!isMostlyBlack(bitmap)) {
                return bitmap
            }
            bitmap.recycle()
        }
        return null
    }

    private fun isUsableCoverThumb(thumbFile: File): Boolean {
        val bitmap = BitmapFactory.decodeFile(thumbFile.absolutePath) ?: return false
        return try {
            !isMostlyBlack(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @SuppressLint("UseKtx")
    @Suppress("KotlinConstantConditions")
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val sampleColumns = 12
        val sampleRows = 12
        var blackPixels = 0
        var totalPixels = 0
        for (row in 0 until sampleRows) {
            val y = if (sampleRows == 1) 0 else row * (bitmap.height - 1) / (sampleRows - 1)
            for (column in 0 until sampleColumns) {
                val x = if (sampleColumns == 1) 0 else column * (bitmap.width - 1) / (sampleColumns - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 54 + Color.green(pixel) * 183 + Color.blue(pixel) * 19) / 256
                if (luminance < 24) {
                    blackPixels++
                }
                totalPixels++
            }
        }
        return blackPixels * 100 >= totalPixels * 85
    }
}