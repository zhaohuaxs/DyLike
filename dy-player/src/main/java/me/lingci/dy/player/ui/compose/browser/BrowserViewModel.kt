package me.lingci.dy.player.ui.compose.browser

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.isVideo

/** 浏览页需要的两项偏好，原先由 Composable 里直接 new SpUtil 读取。 */
@Immutable
data class BrowserPrefs(
    val showHidden: Boolean = false,
    val longVideoMode: Boolean = true
)

/** 「添加到媒体库」的三种结果，用于给用户不同的提示。 */
enum class AddLibraryResult { Added, Exists, Failed }

/**
 * 资源库浏览页的 ViewModel。
 *
 * 只承载「需要落库 / 需要读 SP」的两类动作；目录列举仍然是 Screen 侧的 storage 协程，
 * 因为 IStorage 生命周期跟着页面走（DisposableEffect 里 release）。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "BrowserViewModel"
    }

    private val _prefs = MutableStateFlow(BrowserPrefs())
    val prefs: StateFlow<BrowserPrefs> = _prefs.asStateFlow()

    init {
        reloadPrefs()
    }

    /** 进入页面时刷新一次：播放模式可能在设置页被改过。 */
    fun reloadPrefs() {
        val sp = SpUtil(getApplication())
        _prefs.value = BrowserPrefs(
            showHidden = sp.browserShowHide,
            longVideoMode = sp.longVideoMode
        )
    }

    /**
     * 把浏览到的目录加成一个媒体库。
     *
     * 判重口径与旧版 FileBrowserActivity.addToMedia 一致（id 稳定 → 相同目录生成相同 id），
     * 但不再只扫前 200 条做比对：直接按 id 查一行，超量媒体库下也不会误判。
     *
     * storageId 只在 WebDAV / SMB 时写入：内置的本地源不落 source_data，
     * 写 "__builtin_local__" 会撞 media_data.storage_id 的外键（SET_NULL），插入直接失败。
     */
    fun addToLibrary(
        source: SourceData,
        item: FileEntity,
        onResult: (AddLibraryResult) -> Unit
    ) {
        viewModelScope.launch {
            val result = try {
                val sourceType = source.type ?: StorageType.WEBDAV
                val media = MediaData().apply {
                    title = item.name.trim()
                    path = item.path
                    type = MediaLibType.fromStorage(sourceType)
                    storageType = sourceType
                    storageId = if (sourceType == StorageType.LOCAL_STORAGE) null else source.id
                }
                media.id = LibraryCompat.mediaId(media, DatabaseManager.sourceDataDao.list())
                if (DatabaseManager.mediaDataDao.getById(media.id) != null) {
                    AddLibraryResult.Exists
                } else {
                    DatabaseManager.mediaDataDao.insert(media)
                    AddLibraryResult.Added
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "addToLibrary failed: ${item.path}", e)
                AddLibraryResult.Failed
            }
            onResult(result)
        }
    }

    /** 收集目录下所有视频，供「添加到播放列表」使用；空列表由调用方提示。 */
    fun videosInFolder(
        storage: IStorage,
        folder: FileEntity,
        source: SourceData,
        onResult: (List<VideoData>) -> Unit
    ) {
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                val out = mutableListOf<VideoData>()
                runCatching {
                    storage.listFile(folder.path, false).collect { child ->
                        if (child.isFile && child.name.isVideo()) {
                            out.add(child.toVideoData(storage, source, folder.name))
                        }
                    }
                }
                out
            }
            onResult(videos)
        }
    }
}

/**
 * FileEntity → 播放用的 VideoData。旧版在「列表点击播放」和「加入播放列表」两处各写了一遍，
 * 这里收敛成一处，避免 parentPath / token 的取值规则漂移。
 */
internal fun FileEntity.toVideoData(
    storage: IStorage,
    source: SourceData,
    parentName: String? = null
): VideoData = VideoData().apply {
    name = this@toVideoData.name
    videoUrl = storage.fullPath(path)
    type = source.type ?: StorageType.WEBDAV
    parentPath = parentName ?: path.substringBeforeLast("/").substringAfterLast("/")
    putToken(storage.getToken())
}
