package me.lingci.dy.player.util

import android.content.Context
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.isVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App 启动自动播放助手
 *
 * 当实验室开关 [SpUtil.autoPlayOnLaunch] 打开，且设置了自动播放媒体库 [SpUtil.autoPlayMediaId]
 * 或自动播放播放列表 [SpUtil.autoPlayPlaylistId] 时（两者互斥，仅一个非空），
 * 冷启动自动进入该媒体库/播放列表的上次播放视频，并恢复到上次播放位置。
 *
 * - 长视频：由 [LongVideoActivity] 内部的 PlayHelper.loadInfo 自动恢复 playSeek
 * - 短视频：仅定位到上次播放的视频 index，不恢复进度（遵循现有短视频设计）
 * - 失败时静默处理，用户正常进入首页
 */
object AutoPlayOnLaunch {

    private const val TAG = "AutoPlayOnLaunch"

    /**
     * 进程内静态标志，防止从播放页返回 MainActivity 后再次触发自动播放。
     * 仅在冷启动时由 [tryTrigger] 重置为已触发状态。
     */
    @Volatile
    private var triggered = false

    /**
     * 尝试触发启动自动播放。应在 MainActivity.onCreate 中冷启动时调用一次。
     *
     * @param context 上下文
     * @param scope   协程作用域，用于异步加载视频列表
     * @return true 表示已决定触发（开关开且有设置）；false 表示未触发
     */
    fun tryTrigger(context: Context, scope: CoroutineScope): Boolean {
        if (triggered) return false
        val spUtil = SpUtil(context)
        val mediaId = spUtil.autoPlayMediaId
        val playlistId = spUtil.autoPlayPlaylistId
        // 总开关关闭，或媒体库与播放列表均未设置，直接返回
        if (!spUtil.autoPlayOnLaunch ||
            (mediaId.isNullOrEmpty() && playlistId.isNullOrEmpty())) return false

        // 标记已触发，无论后续是否成功，本进程内不再重复触发
        triggered = true
        // 直接使用传入的 Activity context 启动 Activity；若改用 applicationContext 会触发
        // Framework 检查：非 Activity context 调用 startActivity 必须加 FLAG_ACTIVITY_NEW_TASK。
        // lifecycleScope 与 Activity 绑定，Activity 销毁时协程自动取消，不会泄漏 context。
        scope.launch(Dispatchers.Main) {
            if (!mediaId.isNullOrEmpty()) {
                triggerMedia(context, spUtil, mediaId)
            } else {
                triggerPlaylist(context, spUtil, playlistId)
            }
        }
        return true
    }

    /**
     * 媒体库路径：查找媒体库 → 加载视频列表 → 启动播放页。
     */
    private suspend fun triggerMedia(
        context: Context, spUtil: SpUtil, mediaId: String?
    ) {
        val media = withContext(Dispatchers.IO) {
            // 从 SP 查找目标媒体库
            runCatching {
                LibraryCompat.loadMedia(spUtil).find { it.id == mediaId }
            }.getOrNull()
        }
        if (media == null) {
            // 媒体库已被删除，清空无效设置
            spUtil.autoPlayMediaId = ""
            Log.d(TAG, "autoPlayMediaId not found, cleared")
            return
        }

        // 计算长/短视频模式（复用现有判断逻辑：全局开且 playMode==0，或强制 playMode==2）
        val longVideoMode =
            (spUtil.longVideoMode && media.playMode == 0) || media.playMode == 2

        // 异步加载视频列表（按媒体库类型分发，复用 MediaFragment 的加载方式）
        val videos = withContext(Dispatchers.IO) {
            runCatching { loadVideosForMedia(context, spUtil, media) }.getOrNull()
        }
        if (videos.isNullOrEmpty()) {
            Log.d(TAG, "media has no videos: ${media.title}")
            return
        }

        // 定位上次播放的视频索引（根据 playLast URL 匹配）
        val lastIndex = media.playLast.takeIf { it.isNotBlank() }?.let { url ->
            videos.indexOfFirst { it.videoUrl == url }
        } ?: -1
        val index = if (lastIndex in videos.indices) lastIndex else 0

        // 拉起对应播放页；长视频内部会通过 PlayHelper.loadInfo 自动恢复 playSeek
        if (longVideoMode) {
            LongVideoActivity.start(context, media, ArrayList(videos), index, false)
        } else {
            // 短视频显式传递 shortRandom，使随机开关明确作用于自动播放（媒体库路径）
            ShortVideoActivity.start(context, media, ArrayList(videos), index, false, spUtil.shortRandom)
        }
    }

    /**
     * 播放列表路径：同步清理无效项 → 直接使用 items 作为视频列表 → 启动播放页。
     *
     * 与媒体库路径的区别：播放列表的 [MediaData.items] 直接存储 VideoData，无需扫描；
     * 调用 [LibraryCompat.syncPlaylistVideos] 清理 LOCAL_STORAGE 类型但文件不存在的项，
     * 与 PlaylistActivity 点击进入详情页的行为对齐。
     */
    private suspend fun triggerPlaylist(
        context: Context, spUtil: SpUtil, playlistId: String?
    ) {
        val playlist = withContext(Dispatchers.IO) {
            runCatching {
                LibraryCompat.syncPlaylistVideos(spUtil, playlistId.orEmpty())
            }.getOrNull()
        }
        if (playlist == null) {
            // 播放列表已被删除，清空无效设置
            spUtil.autoPlayPlaylistId = ""
            Log.d(TAG, "autoPlayPlaylistId not found, cleared")
            return
        }

        val longVideoMode =
            (spUtil.longVideoMode && playlist.playMode == 0) || playlist.playMode == 2

        val videos = playlist.items
        if (videos.isNullOrEmpty()) {
            Log.d(TAG, "playlist has no videos: ${playlist.title}")
            return
        }

        // 定位上次播放的视频索引（根据 playLast URL 匹配）
        val lastIndex = playlist.playLast.takeIf { it.isNotBlank() }?.let { url ->
            videos.indexOfFirst { it.videoUrl == url }
        } ?: -1
        val index = if (lastIndex in videos.indices) lastIndex else 0

        // 拉起对应播放页；长视频内部会通过 PlayHelper.loadInfo 自动恢复 playSeek
        if (longVideoMode) {
            LongVideoActivity.start(context, playlist, ArrayList(videos), index, false)
        } else {
            // 短视频显式传递 shortRandom，使随机开关明确作用于自动播放（播放列表路径）
            ShortVideoActivity.start(context, playlist, ArrayList(videos), index, false, spUtil.shortRandom)
        }
    }

    /**
     * 按媒体库类型加载视频列表，与 MediaFragment.handleMediaLocal/handleMediaRemote 逻辑对齐。
     */
    private suspend fun loadVideosForMedia(
        context: Context, spUtil: SpUtil, media: MediaData
    ): List<VideoData> = when (media.type) {
        MediaLibType.DEFAULT, MediaLibType.LOCAL -> {
            // 本地：扫描路径下视频文件
            var path = media.path
            if (path.isBlank()) path = FileOperator.movieFolder.path
            FileOperator.getSortedFiles(File(path), FileOperator.VIDEO_EXTENSIONS)
                .map { VideoData(it) }
        }
        MediaLibType.ONLINE -> {
            // 在线：直接使用媒体库内置的 items
            media.items
        }
        MediaLibType.WEBDAV, MediaLibType.SMB -> {
            // 远程：连接资源源并列举视频文件
            loadRemoteVideos(spUtil, media)
        }
        else -> emptyList()
    }

    /**
     * 加载远程媒体库（WebDAV/SMB）的视频列表，与 MediaFragment.loadRemoteVideos 逻辑一致。
     */
    private suspend fun loadRemoteVideos(spUtil: SpUtil, item: MediaData): List<VideoData> {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = LibraryCompat.effectiveStorageId(item, sourceList) ?: return emptyList()
        val source = sourceList.find { it.id == storageId } ?: return emptyList()
        val storage: IStorage = source.toStorage() ?: return emptyList()
        // 测试连接失败直接返回空
        if (!storage.testConnect()) return emptyList()
        val videos = mutableListOf<VideoData>()
        storage.listFile(item.path, false).collect { file ->
            if (file.isFile && file.name.isVideo()) {
                videos.add(VideoData().apply {
                    name = file.name
                    videoUrl = storage.fullPath(file.path)
                    type = source.storageType()
                    parentPath = file.path.substringBeforeLast("/").substringAfterLast("/")
                    putToken(storage.getToken())
                })
            }
        }
        return videos
    }
}
