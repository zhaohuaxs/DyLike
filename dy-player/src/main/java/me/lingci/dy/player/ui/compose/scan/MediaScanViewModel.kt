package me.lingci.dy.player.ui.compose.scan

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.FileOperator
import java.io.File

@Immutable
data class MediaScanUiState(
    val storageOptions: List<StorageRootOption> = emptyList(),
    val selectedStorage: StorageRootOption? = null,
    val scanNoMedia: Boolean = false,
    val scanHide: Boolean = false,
    val minFileSizeKb: String = "10",
    val isScanning: Boolean = false,
    val folders: List<ScanFolderResult> = emptyList(),
    val totalVideoCount: Int = 0
) {
    val existingCount: Int get() = folders.count { it.existsInLibrary }
    val newCount: Int get() = folders.size - existingCount
    val selectedNewCount: Int get() = folders.count { it.selected && !it.existsInLibrary }
}

/** 一次性提示：文案由 Route 用 `stringResource` 解析，ViewModel 不持有资源 id。 */
enum class MediaScanMessage {
    NoStorage, Scanning, Failed, NoSelection, NoNewMedia
}

sealed interface MediaScanEvent {
    data class Message(val message: MediaScanMessage) : MediaScanEvent

    /** 入库完成：Route 负责提示条数与返回上一页 */
    data class Saved(val addedCount: Int) : MediaScanEvent
}

/**
 * 扫描页 ViewModel：状态机（idle / scanning / result）+ 入库写库都在这里。
 *
 * 目录遍历与过滤规则在 [MediaScanEngine]（纯函数），这里只负责「什么时候扫、扫完跟库比对、
 * 选中的目录怎么落库」。
 */
class MediaScanViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(
        getStorageOptions(app).let { options ->
            MediaScanUiState(storageOptions = options, selectedStorage = options.firstOrNull())
        }
    )
    val state: StateFlow<MediaScanUiState> = _state.asStateFlow()

    private val _events = Channel<MediaScanEvent>(Channel.BUFFERED)
    val events: Flow<MediaScanEvent> = _events.receiveAsFlow()

    private var scanJob: Job? = null

    fun selectStorage(option: StorageRootOption) = _state.update { it.copy(selectedStorage = option) }

    fun setScanNoMedia(enabled: Boolean) = _state.update { it.copy(scanNoMedia = enabled) }

    fun setScanHide(enabled: Boolean) = _state.update { it.copy(scanHide = enabled) }

    fun setMinFileSize(value: String) =
        _state.update { it.copy(minFileSizeKb = value.filter { ch -> ch.isDigit() }) }

    fun toggleFolder(path: String) = _state.update { current ->
        current.copy(
            folders = current.folders.map { folder ->
                if (folder.path == path && !folder.existsInLibrary) {
                    folder.copy(selected = !folder.selected)
                } else {
                    folder
                }
            }
        )
    }

    fun selectAllNew() = _state.update { current ->
        current.copy(
            folders = current.folders.map { folder ->
                if (folder.existsInLibrary) folder else folder.copy(selected = true)
            }
        )
    }

    fun invertNewSelection() = _state.update { current ->
        current.copy(
            folders = current.folders.map { folder ->
                if (folder.existsInLibrary) folder else folder.copy(selected = !folder.selected)
            }
        )
    }

    /** 开始扫描：同一时刻只允许一个扫描任务，重复进入会取消上一次（与旧实现一致） */
    fun scan() {
        val snapshot = _state.value
        val storage = snapshot.selectedStorage
        if (storage == null) {
            viewModelScope.launch { _events.send(MediaScanEvent.Message(MediaScanMessage.NoStorage)) }
            return
        }
        if (snapshot.isScanning) {
            viewModelScope.launch { _events.send(MediaScanEvent.Message(MediaScanMessage.Scanning)) }
            return
        }

        scanJob?.cancel()
        val minBytes = (snapshot.minFileSizeKb.toIntOrNull() ?: 10).coerceAtLeast(0).toLong() * 1024
        _state.update { it.copy(isScanning = true, folders = emptyList(), totalVideoCount = 0) }

        scanJob = viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    if (!snapshot.scanNoMedia && !snapshot.scanHide) {
                        scanWithMediaStore(getApplication(), storage.rootPath, skipNoMedia = true, minFileSizeBytes = minBytes)
                    } else {
                        scanWithFileTree(storage.rootPath, skipNoMedia = !snapshot.scanNoMedia, minFileSizeBytes = minBytes)
                    }
                }
                val result = withContext(Dispatchers.IO) { diffWithLibrary(summary.folders) }
                _state.update {
                    it.copy(folders = result, totalVideoCount = summary.totalVideoCount, isScanning = false)
                }
            } catch (_: CancellationException) {
                // 被新的扫描或页面销毁取消：保持界面最后一次状态，不报错
            } catch (e: Exception) {
                _state.update { it.copy(isScanning = false) }
                _events.send(MediaScanEvent.Message(MediaScanMessage.Failed))
            }
        }
    }

    /** 选中的目录批量入库（媒体 + 分集一起写，只插父行会让详情页分集为空） */
    fun saveSelected() {
        val selected = _state.value.folders.filter { it.selected && !it.existsInLibrary }
        if (selected.isEmpty()) {
            viewModelScope.launch { _events.send(MediaScanEvent.Message(MediaScanMessage.NoSelection)) }
            return
        }
        viewModelScope.launch {
            val addedCount = withContext(Dispatchers.IO) {
                val sourceList = DatabaseManager.sourceDataDao.list()
                var added = 0
                selected.forEach { folder ->
                    val media = MediaData().apply {
                        title = folder.name
                        path = folder.path
                        type = MediaLibType.LOCAL
                        storageType = StorageType.LOCAL_STORAGE
                    }
                    media.id = LibraryCompat.mediaId(media, sourceList)
                    val items = FileOperator
                        .getSortedFiles(File(folder.path), FileOperator.VIDEO_EXTENSIONS)
                        .map { VideoData(it) }
                    val result = LibraryCompat.saveMedia(media, sourceList, items, isEdit = false)
                    if (result == LibraryCompat.MediaSaveResult.Inserted) {
                        added++
                    }
                }
                added
            }
            if (addedCount == 0) {
                _events.send(MediaScanEvent.Message(MediaScanMessage.NoNewMedia))
            } else {
                _events.send(MediaScanEvent.Saved(addedCount))
            }
        }
    }

    private suspend fun diffWithLibrary(scannedFolders: List<ScanFolderResult>): List<ScanFolderResult> {
        val sourceList = DatabaseManager.sourceDataDao.list()
        // 全库比对：用分页快照会把第 200 条之后的媒体误判为「不存在」，导致重复入库
        val mediaList = DatabaseManager.mediaDataDao.listLibrary()
        return scannedFolders.map { folder ->
            val media = MediaData().apply {
                title = folder.name
                path = folder.path
                type = MediaLibType.LOCAL
                storageType = StorageType.LOCAL_STORAGE
            }
            val exists = mediaList.any { LibraryCompat.sameMedia(it, media, sourceList) }
            folder.copy(existsInLibrary = exists, selected = !exists)
        }.sortedWith(
            compareBy<ScanFolderResult> { it.existsInLibrary }
                .thenByDescending { it.videoCount }
                .thenBy { it.name.lowercase() }
        )
    }
}
