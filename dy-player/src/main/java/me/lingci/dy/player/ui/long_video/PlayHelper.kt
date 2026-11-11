package me.lingci.dy.player.ui.long_video

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import master.flame.danmaku.danmaku.util.IOUtils
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.LibraryCompat
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

    // 进程级 IO 作用域：saveHistory/resetPlaySeek 的 DB 写入是 fire-and-forget，与进程同生命周期
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 进度落库节流窗口，与 saveInfo/updateInfo 依赖 PlayInfo 文件 mtime 的 6 秒窗口保持一致 */
    private const val THROTTLE_MS = 6000L

    /** updateHistoryProgress 的进程内节流表，key = "videoId|sourceKey" */
    private val lastProgressSyncAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    /**
     * 保存播放历史（Room 版，替代 SP historyJson 整列表模式）：
     * 1. 确保视频父行落在 video_data（play_history.video_id FK 前提）
     * 2. play_history 打点：((video_id, source_key) 复合唯一自动去重，
     *    无 SP 时代的 100 条上限；仅刷新 updated_at 保序，不覆盖已有进度/时长）
     */
    fun saveHistory(videoData: VideoData, source: HistorySource) {
        videoData.id = videoData.md5()
        ioScope.launch {
            runCatching {
                ensureVideoParent(videoData)
                DatabaseManager.playHistoryDao.touch(
                    videoData.id, source.type.value, source.key, System.currentTimeMillis()
                )
            }.onFailure { e ->
                logD("saveHistory failed", videoData.videoUrl, e)
            }
        }
    }

    /**
     * 轻量进度同步：只写 play_history，不碰 PlayInfo 断点文件。
     *
     * 供没有弹幕控制器、因而永远不会走 saveInfo/updateInfo 的播放页（短视频页）使用，
     * 避免复用 updateInfo 时用空 dmTrack 覆盖长视频已保存的弹幕轨道。
     *
     * 节流：进程内 ConcurrentHashMap 按 "videoId|sourceKey" 记录上次落库时间，
     * [THROTTLE_MS] 窗口内丢弃。[force] = true 绕过节流（onStop/onDestroy 兜底落盘）。
     */
    fun updateHistoryProgress(
        videoData: VideoData,
        source: HistorySource,
        playSeek: Long,
        duration: Long,
        force: Boolean = false
    ) {
        if (videoData.id.isBlank()) {
            videoData.id = videoData.md5()
        }
        val throttleKey = "${videoData.id}|${source.key}"
        if (!force) {
            val last = lastProgressSyncAt[throttleKey] ?: 0L
            if (System.currentTimeMillis() - last < THROTTLE_MS) {
                return
            }
        }
        lastProgressSyncAt[throttleKey] = System.currentTimeMillis()
        val videoId = videoData.id
        val videoUrl = videoData.videoUrl
        ioScope.launch {
            runCatching {
                // 插入分支受 video_id 外键约束，父行缺失必须先补齐
                ensureVideoParent(videoData)
                DatabaseManager.playHistoryDao.upsertProgress(
                    videoId, source.type.value, source.key,
                    playSeek, duration, System.currentTimeMillis()
                )
            }.onFailure { e ->
                logD("updateHistoryProgress failed", videoUrl, e)
            }
        }
    }

    /**
     * 确保视频可安全落库（video_data 的 media_id 是 FK → media_data）：
     * 公共方法（播放历史 / 收藏 / 播放列表条目共用），实现收敛在 LibraryCompat
     */
    suspend fun ensureVideoParent(videoData: VideoData) = LibraryCompat.ensureVideoParent(videoData)

    fun saveThumb(
        context: Context,
        scope: CoroutineScope,
        videoView: CustomVideoView,
        videoData: VideoData
    ) {
        scope.launch(Dispatchers.IO) {
            val file = AppFile(context).buildCache(".thumb/${videoData.videoUrl.md5()}.${AppUtil.THUMB_TYPE}")
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() < 20480) {
                file.delete()
            }
            if (!file.exists()) {
                try {
                    // 截图读取必须在后台线程：MpvSurfaceRenderView.doScreenShot() 会把 mpv 命令
                    // post 到主线程再 await 提交完成，若本方法在主线程执行，就等于主线程等待
                    // 自己——每次都会卡到 2s 超时才返回（实测 Choreographer 掉 261 帧）。
                    // 这段卡顿发生在起播后约 1s，正好把同一主线程驱动的弹幕绘制循环一起冻住，
                    // 表现就是「弹幕滚几秒 → 定住 → 两秒后接着滚」。
                    // 压缩与写盘同在此线程；先写临时文件再原子改名，避免失败时留下空缩略图（F4-3）。
                    videoView.doScreenShot()?.let { bitmap ->
                        val tmp = File(file.parentFile, "${file.name}.tmp")
                        try {
                            FileOutputStream(tmp).use { fos ->
                                bitmap.compress(AppUtil.COMPRESS_FORMAT, 75, fos)
                                fos.flush()
                            }
                            if (tmp.length() > 0L) {
                                if (file.exists()) file.delete()
                                if (tmp.renameTo(file)) {
                                    logD("save thumb", file.path)
                                } else {
                                    logD("save thumb rename failed", file.path)
                                }
                            }
                        } catch (e: Exception) {
                            logD("save thumb failed: ${e.message}", e)
                        } finally {
                            if (tmp.exists()) tmp.delete()
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
                    // 截图读取（含 MPV 命令投递 + 文件轮询 + 解码）必须离开主线程，
                    // 否则 MPV 的 screenshot-to-file 等待会阻塞 UI 500ms+（F4-3）。
                    scope.launch(Dispatchers.IO) {
                        val bitmap = videoView.doScreenShot()
                        if (bitmap == null) {
                            scope.launch(Dispatchers.Main) { onBack.invoke(false) }
                        } else {
                            val saved = ImageConverter().saveImageToMediaStore(
                                videoView.context, bitmap, folder, fileName
                            ) != null
                            scope.launch(Dispatchers.Main) { onBack.invoke(saved) }
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
        lastDmTrack: DmTrack?,
        source: HistorySource? = null
    ) {
        scope.launch(Dispatchers.IO) {
            saveInfoSync(context, videoData, PlayInfo(playSeek, dmTrack, lastDmTrack))
            syncProgress(videoData, source, playSeek)
        }
    }

    fun updateInfo(
        context: Context,
        scope: CoroutineScope,
        videoData: VideoData,
        playSeek: Long,
        dmTrack: MutableList<DmTrack>,
        lastDmTrack: DmTrack?,
        source: HistorySource? = null,
        duration: Long = 0L
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
            // 与文件写同一节流窗口同步进度到 play_history（避免高频 DB 写）
            syncProgress(videoData, source, playSeek, duration)
        }
    }

    /** 把播放进度/时长同步到 play_history（仅 LIBRARY/PLAYLIST/STANDALONE 来源带 source 时写入）
     *  用 upsertProgress 而非裸 UPDATE：长视频开播打点(saveHistory)与本方法都跑在 Dispatchers.IO，
     *  先播的那条记录若还没插入，裸 UPDATE 会静默影响 0 行导致首帧进度丢失。 */
    private suspend fun syncProgress(
        videoData: VideoData,
        source: HistorySource?,
        playSeek: Long,
        duration: Long = 0L
    ) {
        if (source == null) {
            return
        }
        runCatching {
            DatabaseManager.playHistoryDao.upsertProgress(
                videoData.id, source.type.value, source.key,
                playSeek, duration, System.currentTimeMillis()
            )
        }.onFailure { e ->
            logD("syncProgress failed", videoData.videoUrl, e)
        }
    }

    /**
     * 播放完成时同步清零 playSeek，保留弹幕轨道信息。
     * 绕过 [updateInfo] 的 6 秒节流，确保下次点击"重新播放"时 onPlayStart 不会 seekTo 到历史位置。
     * 同时异步清零 play_history 进度（重播位置由上方文件 playSeek 决定，DB 写不阻塞回调）。
     */
    fun resetPlaySeek(context: Context, videoData: VideoData, source: HistorySource? = null) {
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
        if (source != null) {
            ioScope.launch {
                runCatching {
                    DatabaseManager.playHistoryDao.resetProgress(
                        videoData.id, source.type.value, source.key, System.currentTimeMillis()
                    )
                }
            }
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

/**
 * 播放历史来源上下文：播放页按进入来源派生（play_history.source_type/source_key 写入与查询同源）
 */
data class HistorySource(val type: PlaySource, val key: String) {

    companion object {

        fun standalone(): HistorySource = HistorySource(PlaySource.STANDALONE, PlaySource.STANDALONE_KEY)

        /** 从播放页持有的 MediaData 派生：PLAYLIST → pl:{id}；可落库媒体类型 → lib:{id}；其余/为空 → standalone */
        fun fromMediaData(mediaData: MediaData?): HistorySource {
            return when {
                mediaData == null -> standalone()
                mediaData.type == MediaLibType.PLAYLIST ->
                    HistorySource(PlaySource.PLAYLIST, PlaySource.playlistKey(mediaData.id))
                mediaData.type.isLibraryType ->
                    HistorySource(PlaySource.LIBRARY, PlaySource.libraryKey(mediaData.id))
                else -> standalone()
            }
        }
    }
}
