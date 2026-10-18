package me.lingci.dy.player.ui.media

import android.app.Activity
import kotlinx.coroutines.flow.first
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.Log

/**
 *   @author : happyc
 *   time    : 2025/05/02
 *   desc    : 媒体库辅助类
 *   version : 1.0
 */
object MediaHelper {

    fun createMediaFromString(linkString: String): MediaData {
        val mediaData = MediaData().apply {
            type = MediaLibType.ONLINE
            storageType = StorageType.ONLINE_LINK
        }
        var start = ""
        val videoList = mutableListOf<VideoData>()
        val headers = mutableMapOf<String, String>()
        linkString.replace("\r\n", "\n").split("\n").forEach { line ->
            if (line.startsWith("#")) {
                start = line.substringAfterLast("#")
                return@forEach
            }
            if (line.trim().isBlank()) {
                return@forEach
            }
            when (start.trim().uppercase()) {
                "EP" -> {
                    Log.d(this@MediaHelper, start, line)
                    val video = VideoData().apply {
                        type = StorageType.ONLINE_LINK
                    }
                    if (line.contains(";")) {
                        video.name = line.substringBefore(";")
                        video.videoUrl = line.substringAfter(";")
                        videoList.add(video)
                    } else {
                        if (line.trim().startsWith("http")) {
                            video.videoUrl = line.trim()
                            videoList.add(video)
                        }
                    }
                }

                "NAME" -> {
                    mediaData.title = line.trim()
                }

                "IMAGE" -> {
                    mediaData.showFile = line.trim()
                    mediaData.coverType = CoverType.CUSTOM
                }

                "SOURCE" -> {
                    mediaData.playType = line.trim()
                }

                "HEAD" -> {
                    line.count { it == ':' }.let { count ->
                        if (line.startsWith(":") && count == 2) {
                            headers[line.substringBeforeLast(":")] =
                                line.substringAfterLast(":")
                        } else {
                            headers[line.substringBefore(":")] = line.substringAfter(":")
                        }
                    }
                }
            }
        }
        Log.d(this@MediaHelper, mediaData, videoList)
        if (videoList.isNotEmpty()) {
            videoList.forEach {
                it.headers = headers
            }
        }
        mediaData.items = videoList
        mediaData.id = LibraryCompat.mediaId(mediaData)
        return mediaData
    }

    suspend fun addMedia(spUtil: SpUtil, mediaData: MediaData): Boolean {
        // Room3：sources/media 改为 DAO 异步读取（spUtil 参数保留以兼容既有调用点）
        val sources = DatabaseManager.sourceDataDao.list()
        if (mediaData.id.isBlank()) {
            mediaData.id = LibraryCompat.mediaId(mediaData, sources)
        }
        val list = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first().toMutableList()
        val index = list.indexOfFirst { media ->
            LibraryCompat.sameMedia(media, mediaData, sources)
        }
        if (index == -1) {
            // 单条落库，items 非空时重建剧集子表
            DatabaseManager.mediaDataDao.insert(mediaData)
            saveMediaVideos(mediaData)
            return true
        }
        return false
    }

    suspend fun updateMedia(spUtil: SpUtil, mediaData: MediaData) {
        // Room3：sources/media 改为 DAO 异步读取
        val sources = DatabaseManager.sourceDataDao.list()
        if (mediaData.id.isBlank()) {
            mediaData.id = LibraryCompat.mediaId(mediaData, sources)
        }
        val list = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first().toMutableList()
        val index = list.indexOfFirst { media ->
            LibraryCompat.sameMedia(media, mediaData, sources)
        }
        if (index != -1) {
            val old = list[index]
            DatabaseManager.inTransaction {
                // id 不同时先删旧行，避免同语义不同 id 产生重复（旧 items 子表随 FK 级联清理）
                if (old.id != mediaData.id) {
                    DatabaseManager.mediaDataDao.delete(old)
                }
                DatabaseManager.mediaDataDao.upsert(mediaData)
            }
            // items 非空时重建剧集子表
            saveMediaVideos(mediaData)
        }
    }

    /**
     * 媒体落库后重建剧集子表（MediaData.items 为 @Ignore 字段，Room 不会自动保存）
     */
    private suspend fun saveMediaVideos(media: MediaData) {
        if (media.items.isEmpty()) return
        // 子表按 parentPath 排序写入 sort_index，与扫描/迁移的落库规则一致
        val videos = LibraryCompat.sortByParentPath(media.items).mapIndexed { idx, v ->
            v.apply {
                sortIndex = idx
                if (id.isBlank()) id = beanId()
                if (mediaId.isBlank()) mediaId = media.id
            }
        }
        DatabaseManager.inTransaction {
            DatabaseManager.videoDataDao.deleteByMedia(media.id)
            DatabaseManager.videoDataDao.insertAll(videos)
        }
    }

    fun startDetail(activity: Activity, mediaData: MediaData, showDialog: Boolean = false) {

    }

}
