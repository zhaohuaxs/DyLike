package me.lingci.dy.player.ui.compose.shell

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.FileOperator
import java.io.File

/** 一次性提示：只回传资源 id，文案由外壳用 `getString` 解析。 */
sealed interface MediaLibraryEvent {
    data class Message(@param:StringRes val textRes: Int) : MediaLibraryEvent
}

/**
 * 媒体库增删改。
 *
 * 这些操作以前写在 `MainComposeActivity` 的方法里（`lifecycleScope.launch` + 直接调 DAO），
 * Activity 既是界面又是数据层；搬进 ViewModel 后，删除/置顶/新建都只在 `viewModelScope`
 * 上跑，页面销毁不会留下半途而废的写操作。
 */
class MediaLibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val mediaDao = DatabaseManager.mediaDataDao
    private val sourceDao = DatabaseManager.sourceDataDao
    private val sp = SpUtil(app)

    private val _events = Channel<MediaLibraryEvent>(Channel.BUFFERED)
    val events: Flow<MediaLibraryEvent> = _events.receiveAsFlow()

    /** 新建 / 编辑本地媒体库：新增时扫目录建分集，编辑时不动既有分集（父行删改由 FK CASCADE 清理） */
    fun saveLocal(media: MediaData, update: Boolean) {
        viewModelScope.launch {
            val sourceList = sourceDao.list()
            if (media.id.isBlank()) {
                media.id = LibraryCompat.mediaId(media, sourceList)
            }
            val items = when {
                media.items.isNotEmpty() -> media.items
                update -> emptyList()
                else -> scanLocalItems(media.path)
            }
            val result = LibraryCompat.saveMedia(media, sourceList, items, isEdit = update)
            if (result == LibraryCompat.MediaSaveResult.Duplicate) {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_exist))
            }
        }
    }

    /** 新建 / 编辑在线媒体库：分集来自解析结果，链接变更时按新集合重建 */
    fun saveOnline(media: MediaData, updated: Boolean) {
        viewModelScope.launch {
            val sourceList = sourceDao.list()
            if (media.id.isBlank()) {
                media.id = LibraryCompat.mediaId(media, sourceList)
            }
            val result = LibraryCompat.saveMedia(media, sourceList, media.items, isEdit = updated)
            if (result == LibraryCompat.MediaSaveResult.Duplicate) {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_exist))
            }
        }
    }

    /**
     * 从导出的文本文件导入在线媒体库。
     *
     * 读文件、解析、落库全在 IO 线程：以前这段在 ActivityResult 回调里用 `lifecycleScope` 跑，
     * 页面退出后回调仍在写库。
     */
    fun importOnlineFile(path: String) {
        viewModelScope.launch {
            val media = withContext(Dispatchers.IO) {
                runCatching { MediaHelper.createMediaFromString(FileOperator.readText(path)) }.getOrNull()
            }
            if (media != null && media.items.isNotEmpty()) {
                saveOnline(media, false)
            } else {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_failed))
            }
        }
    }

    fun togglePin(mediaId: String) {
        viewModelScope.launch { runCatching { LibraryCompat.togglePin(mediaId) } }
    }

    /** 删除媒体库；若它正是「启动自动播放」目标，同步清空该偏好，避免下次启动指向已删除的 id */
    fun delete(mediaId: String) {
        viewModelScope.launch {
            runCatching {
                // 界面只传 id：实体在这里取，顺带解决「弹窗期间已被别处删掉」的竞态
                val media = mediaDao.getById(mediaId) ?: return@runCatching
                mediaDao.delete(media)
                if (sp.autoPlayMediaId == mediaId) {
                    sp.autoPlayMediaId = ""
                }
            }
        }
    }

    /** 扫描媒体目录，产出待落库的分集（VideoData.id 由 saveMedia 统一归一为 beanId） */
    private suspend fun scanLocalItems(path: String): List<VideoData> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        FileOperator.getSortedFiles(dir, FileOperator.VIDEO_EXTENSIONS).map { VideoData(it) }
    }
}
