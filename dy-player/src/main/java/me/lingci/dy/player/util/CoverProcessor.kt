package me.lingci.dy.player.util

import android.content.Context
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
            if (media.showFile.isNotBlank()) continue

            val coverFile = File(media.coverPath())
            if (coverFile.exists()) continue

            val videos = FileOperator.getSortedFiles(File(media.path), FileOperator.VIDEO_EXTENSIONS)
            if (videos.isEmpty()) continue

            val videoData = VideoData(videos[0])
            val thumbFile = AppUtil.buildThumbFile(appContext, videoData.md5())

            // 与列表缩略图共用同一份缓存和同一套黑帧判定，避免两条路径互相覆写
            if (ThumbQuality.isUsableThumbFile(thumbFile)) {
                coverUpdates[media.id] = thumbFile.absolutePath
                continue
            }
            thumbFile.delete()

            try {
                // extractThumbnail 已完成黑帧回退与 480 宽缩放，这里只负责落盘
                val bitmap = MediaManger.extractThumbnail(videos[0].absolutePath) ?: continue
                try {
                    thumbFile.parentFile?.mkdirs()
                    FileOutputStream(thumbFile).use { fos ->
                        bitmap.compress(AppUtil.COMPRESS_FORMAT, MediaManger.THUMB_QUALITY, fos)
                    }
                    coverUpdates[media.id] = thumbFile.absolutePath
                } finally {
                    bitmap.recycle()
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
}
