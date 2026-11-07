package me.lingci.dy.player.ui.compose.browser

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.notStartDot

/** 浏览页需要的偏好，原先由 Composable 里直接 new SpUtil 读取。 */
@Immutable
data class BrowserPrefs(
    val showHidden: Boolean = false,
    val longVideoMode: Boolean = true,
    val sortKey: FileSortKey = FileSortKey.Name,
    val sortAscending: Boolean = true
)

/** 页面级阶段：连接中 / 可浏览 / 连接失败。目录本身「读取中」由 [BrowserUiState.loading] 表达。 */
enum class BrowserPhase { Loading, Content, Error }

/**
 * 连接失败的原因。文案留在资源里：ViewModel 不持有 Context，也不该拿着 `getString` 的结果做状态。
 */
enum class BrowserError { UnsupportedSource, ConnectFailed, StoragePermission }

/**
 * 浏览页状态。
 *
 * [breadcrumbs] 首项恒为当前卷的根，末项就是正在浏览的目录；[files] 是该目录的原始列表，
 * 搜索与排序属于纯派生，仍由 Screen 的 `remember` 算，换排序不重新读盘。
 */
@Immutable
data class BrowserUiState(
    val phase: BrowserPhase = BrowserPhase.Loading,
    val breadcrumbs: List<FileEntity> = emptyList(),
    val files: List<FileEntity> = emptyList(),
    val loading: Boolean = false,
    val error: BrowserError? = null
) {
    val currentPath: String get() = breadcrumbs.lastOrNull()?.path ?: "/"
}

/** 「添加到媒体库」的三种结果，用于给用户不同的提示。 */
enum class AddLibraryResult { Added, Exists, Failed }

/**
 * 资源库浏览页的 ViewModel。
 *
 * [IStorage] 由这里持有：连接、列举目录、释放都在 `viewModelScope` 内完成，Screen 只接
 * [state] + 回调。之前连接与目录列举留在 Screen，理由是「storage 生命周期跟着页面走」，
 * 实际用 `onCleared` 释放同样准确，还能省掉「去详情再返回要重连一次」。
 */
class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        const val TAG = "BrowserViewModel"
    }

    private val _prefs = MutableStateFlow(BrowserPrefs())
    val prefs: StateFlow<BrowserPrefs> = _prefs.asStateFlow()

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    /**
     * 本地存储的卷列表（内部存储 / 存储卡 / U 盘）。
     *
     * 资源库把本地与外部存储合并成了一个入口，卷的差异只能在这里体现；检测要读 StatFs，
     * 所以放在 IO 协程里跑，Screen 只拿结果渲染切换菜单。
     */
    private val _volumes = MutableStateFlow<List<StorageVolume>>(emptyList())
    val volumes: StateFlow<List<StorageVolume>> = _volumes.asStateFlow()

    private var volumesLoaded = false

    /** 当前连接；[open] 换根时先释放旧的。 */
    private var storage: IStorage? = null

    /** 连接使用的源（`siteUrl` 已按所选卷替换过），列举与构造播放列表都靠它。 */
    private var openedSource: SourceData? = null

    /** 已连接的身份；重新进组合时命中即跳过重连，避免从详情页返回把目录状态清掉。 */
    private var openedKey: String? = null

    /** 目录列举任务；切目录时取消，防止慢速 WebDAV 的上一次结果覆盖当前目录。 */
    private var browseJob: Job? = null

    init {
        reloadPrefs()
        loadVolumes()
        // 显示隐藏文件是重新列举的口径，不是本地过滤：IStorage 在源头就把它们筛掉了
        viewModelScope.launch {
            _prefs.map { it.showHidden }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    if (storage != null) loadDir(_state.value.currentPath)
                }
        }
    }

    /** 进入页面时刷新一次：播放模式可能在设置页被改过。 */
    fun reloadPrefs() {
        val sp = SpUtil(getApplication())
        _prefs.value = BrowserPrefs(
            showHidden = sp.browserShowHide,
            longVideoMode = sp.longVideoMode,
            sortKey = sp.browserSortKey
                ?.let { name -> FileSortKey.entries.firstOrNull { it.name == name } }
                ?: FileSortKey.Name,
            sortAscending = sp.browserSortAscending
        )
    }

    fun setSortKey(key: FileSortKey) {
        if (_prefs.value.sortKey == key) return
        SpUtil(getApplication()).browserSortKey = key.name
        _prefs.value = _prefs.value.copy(sortKey = key)
    }

    fun toggleSortAscending() {
        val next = !_prefs.value.sortAscending
        SpUtil(getApplication()).browserSortAscending = next
        _prefs.value = _prefs.value.copy(sortAscending = next)
    }

    /** 首次进入 / 插拔存储卡后重新检测卷；重复调用时只在必要时重扫。 */
    fun loadVolumes(force: Boolean = false) {
        if (volumesLoaded && !force) return
        volumesLoaded = true
        viewModelScope.launch {
            _volumes.value = withContext(Dispatchers.IO) {
                StorageVolumes.detect(getApplication())
            }
        }
    }

    /**
     * 用给定源（卷根已替换）建立连接并从根目录开始浏览。
     *
     * 幂等：同一个源重复调用直接返回，配置变更或从详情页返回都不会重建连接。
     * 换卷 / 换源会先拆掉旧连接，再整体重置状态——否则新根路径还没接上时，
     * 旧 IStorage 会按 "/" 把上一个卷的内容列出来。
     */
    fun open(source: SourceData, force: Boolean = false) {
        val key = "${source.id}|${source.storageType()}|${source.siteUrl}|${source.schema}"
        if (!force && key == openedKey && storage != null) return
        openedKey = key
        openedSource = source

        browseJob?.cancel()
        storage?.release()
        storage = null
        _state.value = BrowserUiState()

        // 本地源没有这一步就只会列出空目录：READ/WRITE 未授予时 File.listFiles 返回 null，
        // 用户看到的是「此目录为空」而不是权限提示。
        if (source.storageType() == StorageType.LOCAL_STORAGE && !hasStoragePermission()) {
            fail(BrowserError.StoragePermission)
            return
        }

        browseJob = viewModelScope.launch { connect(source) }
    }

    private fun hasStoragePermission(): Boolean =
        PermissionUtil.hasStorageAccess(getApplication())

    private suspend fun connect(source: SourceData) {
        val created = source.toStorage()
        if (created == null) {
            fail(BrowserError.UnsupportedSource)
            return
        }
        val ok = withContext(Dispatchers.IO) {
            runCatching { created.testConnect() }.getOrDefault(false)
        }
        if (!ok) {
            created.release()
            fail(BrowserError.ConnectFailed)
            return
        }
        storage = created
        val root = created.rootFile()
        _state.value = BrowserUiState(
            phase = BrowserPhase.Content,
            breadcrumbs = listOf(root),
            loading = true
        )
        loadDir(root.path)
    }

    private fun fail(error: BrowserError) {
        _state.value = BrowserUiState(phase = BrowserPhase.Error, error = error)
    }

    /** 进入子目录（面包屑追加一项）。 */
    fun openFolder(item: FileEntity) {
        if (storage == null) return
        val crumbs = _state.value.breadcrumbs + item
        browseJob?.cancel()
        _state.value = _state.value.copy(breadcrumbs = crumbs)
        browseJob = viewModelScope.launch { loadDir(item.path) }
    }

    /** 点面包屑任意一级回退到该层。 */
    fun navigateTo(index: Int) {
        val crumbs = _state.value.breadcrumbs
        if (index < 0 || index >= crumbs.lastIndex || storage == null) return
        val target = crumbs.take(index + 1)
        browseJob?.cancel()
        _state.value = _state.value.copy(breadcrumbs = target)
        browseJob = viewModelScope.launch { loadDir(target.last().path) }
    }

    /** 系统返回：退回上一层返回 true；已在根目录返回 false，由 Screen 退出页面。 */
    fun navigateUp(): Boolean {
        val crumbs = _state.value.breadcrumbs
        if (crumbs.size <= 1) return false
        navigateTo(crumbs.size - 2)
        return true
    }

    private suspend fun loadDir(path: String) {
        val current = storage ?: return
        if (openedSource?.storageType() == StorageType.LOCAL_STORAGE && !hasStoragePermission()) {
            fail(BrowserError.StoragePermission)
            return
        }
        val showHidden = _prefs.value.showHidden
        _state.update { it.copy(loading = true, files = emptyList()) }
        val items = withContext(Dispatchers.IO) {
            buildList {
                runCatching {
                    current.listFile(path, false).collect { item ->
                        val visible = !item.isFile && (showHidden || item.name.notStartDot())
                        if (visible || item.name.isVideo()) add(item)
                    }
                }.onFailure {
                    android.util.Log.e(TAG, "listDir failed path=$path", it)
                }
            }
        }
        _state.update { it.copy(loading = false, files = items) }
    }

    /**
     * 把当前目录列表转成播放用的列表，返回「列表 + 点击项下标」。
     *
     * `FileEntity → VideoData` 要读 [IStorage] 的 `fullPath` / `getToken`，Screen 拿不到连接，
     * 所以这里算好再交给它起播；没连上时返回 null。
     */
    fun buildPlayList(
        allFiles: List<FileEntity>,
        clicked: FileEntity
    ): Pair<ArrayList<VideoData>, Int>? {
        val current = storage ?: return null
        val source = openedSource ?: return null
        val list = ArrayList<VideoData>(allFiles.size)
        var position = 0
        allFiles.forEach { file ->
            if (!file.isFile) return@forEach
            list += file.toVideoData(current, source)
            if (file.path == clicked.path) position = list.size - 1
        }
        return list to position
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
    fun addToLibrary(item: FileEntity, onResult: (AddLibraryResult) -> Unit) {
        val source = openedSource ?: return
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
    fun videosInFolder(folder: FileEntity, onResult: (List<VideoData>) -> Unit) {
        val current = storage ?: return
        val source = openedSource ?: return
        viewModelScope.launch {
            val videos = withContext(Dispatchers.IO) {
                val out = mutableListOf<VideoData>()
                runCatching {
                    current.listFile(folder.path, false).collect { child ->
                        if (child.isFile && child.name.isVideo()) {
                            out.add(child.toVideoData(current, source, folder.name))
                        }
                    }
                }
                out
            }
            onResult(videos)
        }
    }

    override fun onCleared() {
        browseJob?.cancel()
        storage?.release()
        storage = null
        super.onCleared()
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
