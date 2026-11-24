package me.lingci.dy.player.ui.compose.shell

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import me.lingci.lib.base.util.PermissionUtil
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

    private val sourceDao = DatabaseManager.sourceDataDao
    private val sp = SpUtil(app)

    private val _events = Channel<MediaLibraryEvent>(Channel.BUFFERED)
    val events: Flow<MediaLibraryEvent> = _events.receiveAsFlow()

    /**
     * 存储权限从 Compose 的 `remember` 收回到这里。
     *
     * 以前授权后靠 `key(storageGranted)` 重建整棵内容树来让 Room 重订阅，副作用是
     * 导航回退栈、滚动位置、输入态一起被清掉。现在权限是数据层的输入，
     * 变化时由查询侧 `flatMapLatest` 重订阅，UI 树保持不动。
     */
    private val _storageAccess = MutableStateFlow(PermissionUtil.hasStorageAccess(app))
    val storageAccess: StateFlow<Boolean> = _storageAccess.asStateFlow()

    fun refreshStorageAccess() {
        _storageAccess.value = PermissionUtil.hasStorageAccess(getApplication())
    }

    /**
     * 首次进入时自动扫描一次，与 View 侧 `MediaFragment.scanMedia()` 共用 `firstScanMovie`：
     * 谁先触发谁消费，另一条链路不会重复弹扫描页。
     *
     * 用 StateFlow 而非一次性事件：本 ViewModel 是 Activity 作用域，配置变化（旋转、深色切换）
     * 后 Activity 重建、Compose 侧重新收集，状态不会丢；消费时立刻写回 SP 并置 false，
     * 重建后也不会二次导航。
     */
    private val _firstScanPending = MutableStateFlow(sp.firstScanMovie)
    val firstScanPending: StateFlow<Boolean> = _firstScanPending.asStateFlow()

    fun consumeFirstScan() {
        if (!_firstScanPending.value) return
        sp.firstScanMovie = false
        _firstScanPending.value = false
    }

    /** 新增本地媒体库：扫目录建分集；判重命中提示已存在 */
    fun addLocal(media: MediaData) {
        viewModelScope.launch {
            val sourceList = sourceDao.list()
            if (media.id.isBlank()) {
                media.id = LibraryCompat.mediaId(media, sourceList)
            }
            val items = if (media.items.isNotEmpty()) media.items else scanLocalItems(media.path)
            val result = LibraryCompat.insertMedia(media, sourceList, items)
            if (result == LibraryCompat.MediaSaveResult.Duplicate) {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_exist))
            }
        }
    }

    /**
     * 编辑媒体库（本地/远端共用）：不动既有分集，
     * 读改写保留来源（type/storageType/storageId）与置顶/上次播放等运行时状态。
     */
    fun editMedia(media: MediaData) {
        viewModelScope.launch {
            val result = LibraryCompat.updateMedia(media, sourceDao.list())
            if (result == LibraryCompat.MediaSaveResult.Duplicate) {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_exist))
            }
        }
    }

    /** 新增在线媒体库：分集来自解析结果 */
    fun addOnline(media: MediaData) {
        viewModelScope.launch {
            val sourceList = sourceDao.list()
            if (media.id.isBlank()) {
                media.id = LibraryCompat.mediaId(media, sourceList)
            }
            val result = LibraryCompat.insertMedia(media, sourceList, media.items)
            if (result == LibraryCompat.MediaSaveResult.Duplicate) {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_exist))
            }
        }
    }

    /** 编辑在线媒体库：链接变更时按新集合重建分集 */
    fun editOnline(media: MediaData) {
        viewModelScope.launch {
            val result = LibraryCompat.updateMedia(media, sourceDao.list(), media.items)
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
                addOnline(media)
            } else {
                _events.send(MediaLibraryEvent.Message(R.string.media_add_failed))
            }
        }
    }

    fun togglePin(mediaId: String) {
        viewModelScope.launch { runCatching { LibraryCompat.togglePin(mediaId) } }
    }

    /**
     * 删除媒体库卡片；多季卡片会展开全部季，若它正是「启动自动播放」目标则同步清空偏好。
     *
     * 失败会发一次提示：以前整段 `runCatching` 把异常吞掉，用户点完删除什么反馈都没有。
     */
    fun delete(mediaId: String, seriesKey: String? = null) {
        viewModelScope.launch {
            val deleted = try {
                LibraryCompat.deleteMediaCard(mediaId, sp, seriesKey)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Exception) {
                false
            }
            if (!deleted) {
                _events.send(MediaLibraryEvent.Message(R.string.media_delete_failed))
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
