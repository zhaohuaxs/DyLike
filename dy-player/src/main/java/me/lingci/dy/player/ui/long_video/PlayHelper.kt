package me.lingci.dy.player.ui.long_video

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import master.flame.danmaku.danmaku.util.IOUtils
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.okhttp.OkUtil
import me.lingci.lib.base.okhttp.httpGet
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.ImageConverter
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.createNew
import me.lingci.lib.base.util.deleteExists
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.md5
import me.lingci.lib.base.util.readJsonEntity
import me.lingci.lib.base.util.writeJsonEntity
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.entity.xml.DmItem
import me.lingci.lib.player.exo.Media3Helper
import me.lingci.lib.player.widget.videoview.CustomVideoView
import java.io.File
import java.io.FileOutputStream

/**
 *   @author : happyc
 *   time    : 2025/07/22
 *   desc    :
 *   version : 1.0
 */
object PlayHelper {

    private fun infoFile(context: Context, videoData: VideoData): File {
        return AppFile(context).buildCustom("info", "${videoData.videoUrl.md5()}.json")
    }

    fun loadInfoSync(context: Context, videoData: VideoData): PlayInfo? {
        return try {
            infoFile(context, videoData).readJsonEntity<PlayInfo>()
        } catch (e: Exception) {
            logD("loadInfoSync failed", videoData.videoUrl, e)
            null
        }
    }

    fun saveInfoSync(context: Context, videoData: VideoData, info: PlayInfo): Boolean {
        return try {
            infoFile(context, videoData).writeJsonEntity(info)
        } catch (e: Exception) {
            logD("saveInfoSync failed", videoData.videoUrl, e)
            false
        }
    }

    fun saveHistory(spUtil: SpUtil, videoData: VideoData, update: Boolean = false) {
        videoData.id = videoData.md5()
        val list = JsonUtil.toList<VideoData>(
            spUtil.historyJson!!
        ).toMutableList()
        val existIndex = list.indexOfFirst { it.id == videoData.id }
        if (existIndex > -1) {
            list.removeAt(existIndex)
            list.add(videoData)
        } else {
            if (list.size >= 100) {
                list.removeAt(0)
            }
            list.add(videoData)
        }
        spUtil.historyJson = JsonUtil.toJsonString(list)
    }

    fun saveThumb(
        context: Context,
        scope: CoroutineScope,
        videoView: CustomVideoView,
        videoData: VideoData
    ) {
        scope.launch(Dispatchers.IO) {
            val file = AppFile(context).buildCache(".thumb/${videoData.videoUrl.md5()}.${AppUtil.THUMB_TYPE}")
            if (file.exists() && file.length() < 20480) {
                file.delete()
            }
            if (!file.exists()) {
                try {
                    file.createNew()
                    scope.launch(Dispatchers.Main) {
                        videoView.doScreenShot()?.let { bitmap ->
                            scope.launch(Dispatchers.IO) {
                                FileOutputStream(file).use { fos ->
                                    bitmap.compress(AppUtil.COMPRESS_FORMAT, 75, fos)
                                    fos.flush()
                                    logD("save thumb", file.path)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    logD("doScreenShot failed: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 缓存远程文件
     */
    private suspend fun cacheRemoteFile(
        context: Context,
        url: String,
        headers: Map<String, String>,
        cacheDir: String,
        cacheName: String,
        validate: (File) -> Boolean = { file -> file.exists() && file.isFile && file.length() > 0L }
    ): File? = withContext(Dispatchers.IO) {
        if (!url.startsWith("http")) {
            return@withContext null
        }
        val file = AppFile(context).buildCache("$cacheDir/$cacheName")
        if (file.exists() && validate(file)) {
            return@withContext file
        }
        file.deleteExists()
        return@withContext try {
            httpGet(url)
                .unsafe()
                .headers(headers.toMutableMap())
                .download()?.use { input ->
                    file.parentFile?.mkdirs()
                    file.createNewFile()
                    file.outputStream().use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                } ?: return@withContext null
            if (validate(file)) {
                file
            } else {
                file.deleteExists()
                null
            }
        } catch (e: Exception) {
            logD("cache remote file failed", url, e)
            file.deleteExists()
            null
        }
    }

    /**
     * 获取字幕文件
     */
    suspend fun resolveSubtitleFile(context: Context, videoData: VideoData): File? {
        if (videoData.videoUrl.startsWith("/")) {
            val srtFile = File(videoData.srtLink).takeIf { it.exists() && it.isFile }
            if (srtFile != null) return srtFile
            val assFile = File(videoData.assLink).takeIf { it.exists() && it.isFile }
            if (assFile != null) return assFile
            return null
        }
        if (videoData.type != StorageType.WEBDAV) {
            return null
        }
        var cacheRemoteFile = cacheRemoteFile(
            context,
            videoData.srtLink,
            videoData.headers,
            ".subtitle",
            "${videoData.md5()}.srt"
        )
        if (cacheRemoteFile == null) {
            cacheRemoteFile = cacheRemoteFile(
                context,
                videoData.assLink,
                videoData.headers,
                ".subtitle",
                "${videoData.md5()}.ass"
            )
        }
        return cacheRemoteFile
    }

    private suspend fun resolveDavDmFile(context: Context, videoData: VideoData): File? {
        return cacheRemoteFile(
            context,
            videoData.dmLink,
            videoData.headers,
            ".xml",
            "${videoData.md5()}.xml"
        ) { file ->
            file.exists() && file.isFile && file.length() > 0L && runCatching {
                file.readText().contains("<?xml")
            }.getOrDefault(false)
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    fun doScreenShot(
        scope: CoroutineScope,
        videoView: CustomVideoView,
        item: VideoData,
        times: Long,
        onBack: (save: Boolean) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val folder = "DyLike"
            val fileName = "Screenshot_${AppUtil.formatNow()}.png"
            try {
                if (item.videoUrl.startsWith("/")) {
                    Log.d(this, "doScreenShot", times, item.videoUrl)
                    Media3Helper.getFrameFromVideo(
                        videoView.context,
                        File(item.videoUrl).toUri(),
                        emptyMap(),
                        times
                    ).let { bitmap ->
                        Log.d(this, "doScreenShot", bitmap == null)
                        if (bitmap == null) {
                            onBack.invoke(false)
                        } else {
                            ImageConverter().saveImageToMediaStore(
                                videoView.context, bitmap, folder, fileName
                            ).let {
                                onBack.invoke(it != null)
                            }
                        }
                    }
                } else {
                    scope.launch(Dispatchers.Main) {
                        videoView.doScreenShot().let { bitmap ->
                            if (bitmap == null) {
                                onBack.invoke(false)
                            } else {
                                scope.launch(Dispatchers.IO) {
                                    ImageConverter().saveImageToMediaStore(
                                        videoView.context, bitmap, folder, fileName
                                    ).let {
                                        onBack.invoke(it != null)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logD("doScreenShot failed: ${e.message}", e)
                onBack.invoke(false)
            }
        }
    }

    fun loadDavDm(
        context: Context,
        scope: CoroutineScope,
        videoData: VideoData,
        onBack: (file: File) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            resolveDavDmFile(context, videoData)?.let { file ->
                withContext(Dispatchers.Main) {
                    onBack.invoke(file)
                }
            }
        }
    }

    fun loadInfo(
        context: Context,
        scope: CoroutineScope,
        videoData: VideoData,
        onBack: (info: PlayInfo?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            val info = loadInfoSync(context, videoData)
            withContext(Dispatchers.Main) {
                onBack.invoke(info)
            }
        }
    }

    fun saveInfo(
        context: Context,
        scope: CoroutineScope,
        videoData: VideoData,
        playSeek: Long,
        dmTrack: MutableList<DmTrack>,
        lastDmTrack: DmTrack?
    ) {
        scope.launch(Dispatchers.IO) {
            saveInfoSync(context, videoData, PlayInfo(playSeek, dmTrack, lastDmTrack))
        }
    }

    fun updateInfo(
        context: Context,
        scope: CoroutineScope,
        videoData: VideoData,
        playSeek: Long,
        dmTrack: MutableList<DmTrack>,
        lastDmTrack: DmTrack?
    ) {
        scope.launch(Dispatchers.IO) {
            val file = infoFile(context, videoData)
            var info: PlayInfo? = null
            if (file.exists()) {
                if (System.currentTimeMillis() - file.lastModified() < 6000) {
                    return@launch
                }
                info = loadInfoSync(context, videoData)?.apply {
                    this.playSeek = playSeek
                }
            }
            if (info == null) {
                info = PlayInfo(playSeek, dmTrack, lastDmTrack)
            }
            saveInfoSync(context, videoData, info)
        }
    }

    /**
     * 播放完成时同步清零 playSeek，保留弹幕轨道信息。
     * 绕过 [updateInfo] 的 6 秒节流，确保下次点击"重新播放"时 onPlayStart 不会 seekTo 到历史位置。
     */
    fun resetPlaySeek(context: Context, videoData: VideoData) {
        try {
            val file = infoFile(context, videoData)
            if (!file.exists()) return
            // 读取现有 info，仅把 playSeek 置 0，保留弹幕轨道
            val info = file.readJsonEntity<PlayInfo>() ?: return
            if (info.playSeek == 0L) return
            info.playSeek = 0L
            file.writeJsonEntity(info)
        } catch (e: Exception) {
            logD("resetPlaySeek failed", videoData.videoUrl, e)
        }
    }

}

@OptIn(InternalSerializationApi::class)
@Serializable
data class PlayInfo(
    var playSeek: Long = 0L,
    var dmTrack: MutableList<DmTrack> = mutableListOf(),
    var lastDmTrack: DmTrack? = null,
    var comments: MutableList<DmItem> = mutableListOf()
)
