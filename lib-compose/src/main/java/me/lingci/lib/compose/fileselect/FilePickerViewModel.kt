package me.lingci.lib.compose.fileselect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.childSize
import me.lingci.lib.base.util.isImage
import me.lingci.lib.base.util.isText
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.notHome
import me.lingci.lib.base.util.sizeFormat
import java.io.File

/** 常用目录持久化抽象：Activity 侧用 SpBase + JsonUtil 实现。ViewModel 不持有 Context。 */
interface FavoriteFoldersStore {
    fun load(): List<String>
    fun save(paths: List<String>)
}

private val DEFAULT_FAVORITES: List<String> = listOf(
    FileOperator.downloadFolder.path,
    FileOperator.buildDownFile("弹目").path,
)

private fun crumbOf(path: String): BreadcrumbUiModel =
    BreadcrumbUiModel(path, File(path).name.ifBlank { path })

/**
 * 文件选择器全部状态与业务：目录枚举、面包屑导航、勾选、常用目录。
 * 目录 IO 用 withContext(Dispatchers.IO)，不阻塞主线程。
 */
class FilePickerViewModel(
    private val mode: PickerMode,
    private val extensions: List<String>,
    private val favoriteStore: FavoriteFoldersStore,
    initialPath: String = FileOperator.rootFolder.path,
) : ViewModel() {

    private val _state = MutableStateFlow(
        FilePickerUiState(
            mode = mode,
            // 去重：SP 里若残留默认路径，重复的 key 会让 LazyRow 崩溃
            favorites = (DEFAULT_FAVORITES + favoriteStore.load()).distinct(),
        )
    )
    val state = _state.asStateFlow()

    private val _events = Channel<FilePickerEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 导航请求序号：只接受最后一次请求的结果，避免并发导航让面包屑错乱。 */
    private var navRequestId = 0L
    private var navJob: Job? = null

    init {
        navigate(initialPath, targetCrumbs = buildCrumbs(initialPath))
    }

    private val folderMode: Boolean
        get() = mode == PickerMode.SingleFolder || mode == PickerMode.MultiFolder

    /**
     * 进入目录。目标面包屑在发起时就算好并一次性写入，不再由回调按"当前末项"推断，
     * 避免并发导航时面包屑错乱。用 [navRequestId] 只接受最后一次请求的结果。
     */
    private fun navigate(path: String, targetCrumbs: List<BreadcrumbUiModel>) {
        navJob?.cancel()
        val requestId = ++navRequestId
        navJob = viewModelScope.launch {
            _state.update { it.copy(phase = FilePickerUiState.Phase.Loading, hasError = false) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(path)
                    val children = if (folderMode) {
                        FileOperator.getSortedFolders(dir)
                    } else {
                        FileOperator.getSortedFiles(dir, true, ArrayList(extensions))
                    }
                    children.map { it.toUiModel() }
                }
            }
            // 过期请求：已有更新的导航发起，丢弃本次结果，不碰任何状态
            if (requestId != navRequestId) return@launch
            result.fold(
                onSuccess = { files ->
                    _state.update { current ->
                        current.copy(
                            phase = if (files.isEmpty()) FilePickerUiState.Phase.Empty
                            else FilePickerUiState.Phase.Content,
                            items = files,
                            breadcrumbs = targetCrumbs,
                            currentPath = path,
                            hasError = false,
                        )
                    }
                },
                onFailure = {
                    // 失败只标记错误态：面包屑/当前目录在发起时未提前改动，保持一致
                    _state.update {
                        it.copy(phase = FilePickerUiState.Phase.Error, hasError = true)
                    }
                }
            )
        }
    }

    /** 进入子目录：在现有面包屑末尾追加一级。 */
    private fun enterDir(path: String) {
        val crumbs = _state.value.breadcrumbs
        navigate(path, crumbs + crumbOf(path))
    }

    private fun File.toUiModel(): FileItemUiModel {
        val isFile = isFile
        return FileItemUiModel(
            path = path,
            name = name.ifBlank { path },
            isFile = isFile,
            kind = when {
                !isFile -> FileKind.Folder
                name.isVideo() -> FileKind.Video
                name.isImage() -> FileKind.Image
                name.isText() -> FileKind.Text
                else -> FileKind.Other
            },
            // 复用 lib-base 的 sizeFormat，格式与旧选择器完全一致
            sizeText = if (isFile) sizeFormat() else "",
            childCount = if (isFile) 0 else childSize(),
            checked = if (isFile) path in _state.value.selectedFiles
            else path in _state.value.selectedFolders,
            isFavorite = path in _state.value.favorites,
            canFavorite = !isFile && path !in DEFAULT_FAVORITES,
        )
    }

    /** 从目标目录一路向上拼到 root（root 在前），与旧版 changeDir 行为一致。 */
    private fun buildCrumbs(target: String): List<BreadcrumbUiModel> {
        val root = FileOperator.rootFolder.path
        val chain = mutableListOf<String>()
        var current = File(target)
        while (current.path != root && current.notHome()) {
            chain.add(0, current.path)
            current = current.parentFile ?: break
        }
        return listOf(BreadcrumbUiModel(root, "root", isRoot = true)) + chain.map(::crumbOf)
    }

    /** 回退一级；返回 false 表示已在 root，交给外部 finish。 */
    fun navigateUp(): Boolean {
        val crumbs = _state.value.breadcrumbs
        if (crumbs.size <= 1) return false
        val target = crumbs.dropLast(1)
        navigate(target.last().path, target)
        return true
    }

    fun onCrumbClick(index: Int) {
        val crumbs = _state.value.breadcrumbs
        if (index !in crumbs.indices) return
        val target = crumbs.take(index + 1)
        navigate(target.last().path, target)
    }

    fun onFavoriteClick(path: String) {
        navigate(path, buildCrumbs(path))
    }

    /** 文件行点击：单选直接回传，多选切换勾选；目录行点击进入。 */
    fun onItemClick(path: String) {
        val item = _state.value.items.firstOrNull { it.path == path } ?: return
        when {
            !item.isFile -> enterDir(path)
            mode == PickerMode.SingleFile ->
                _events.trySend(FilePickerEvent.Finish(listOf(path), folderMode = false))
            mode == PickerMode.MultiFile -> toggleFile(path)
        }
    }

    /** 多目录模式下目录右侧勾选框：与整行点击互不干扰。 */
    fun toggleFolder(path: String) {
        if (mode != PickerMode.MultiFolder) return
        _state.update { state ->
            val next = if (path in state.selectedFolders) {
                state.selectedFolders - path
            } else {
                state.selectedFolders + path
            }
            state.copy(
                selectedFolders = next,
                items = state.items.map {
                    if (!it.isFile && it.path == path) it.copy(checked = path in next) else it
                },
            )
        }
    }

    private fun toggleFile(path: String) {
        _state.update { state ->
            val next = if (path in state.selectedFiles) state.selectedFiles - path
            else state.selectedFiles + path
            state.copy(
                selectedFiles = next,
                items = state.items.map {
                    if (it.isFile && it.path == path) it.copy(checked = path in next) else it
                },
            )
        }
    }

    fun selectAll() {
        _state.update { state ->
            if (mode == PickerMode.MultiFolder) {
                val paths = state.items.filter { !it.isFile }.map { it.path }
                val next = (state.selectedFolders + paths).distinct()
                state.copy(
                    selectedFolders = next,
                    items = state.items.map {
                        if (!it.isFile) it.copy(checked = true) else it
                    },
                )
            } else {
                val visible = state.items.filter { it.isFile }.map { it.path }.toSet()
                // 保留其它目录已勾选的文件，与 selectInvert 的跨目录语义保持一致
                val next = state.selectedFiles + visible
                state.copy(
                    selectedFiles = next,
                    items = state.items.map { it.copy(checked = it.isFile) },
                )
            }
        }
    }

    fun selectInvert() {
        _state.update { state ->
            if (mode == PickerMode.MultiFolder) {
                val visible = state.items.filter { !it.isFile }.map { it.path }.toSet()
                val next = state.selectedFolders.filterNot { it in visible } +
                    visible.filterNot { it in state.selectedFolders }
                state.copy(
                    selectedFolders = next,
                    items = state.items.map {
                        if (!it.isFile) it.copy(checked = it.path in next) else it
                    },
                )
            } else {
                val visible = state.items.filter { it.isFile }.map { it.path }.toSet()
                val next = state.selectedFiles.filterNot { it in visible } +
                    visible.filterNot { it in state.selectedFiles }
                state.copy(
                    selectedFiles = next.toSet(),
                    items = state.items.map {
                        if (it.isFile) it.copy(checked = it.path in next) else it
                    },
                )
            }
        }
    }

    /** 多目录模式：把当前所在目录加入勾选，跨目录导航保持。 */
    fun selectCurrentFolder() {
        val current = _state.value.currentPath
        if (current == FileOperator.rootFolder.path ||
            current == FileOperator.downloadFolder.path
        ) {
            _events.trySend(FilePickerEvent.NeedConcreteFolder)
            return
        }
        _state.update { state ->
            val next = if (current in state.selectedFolders) state.selectedFolders
            else state.selectedFolders + current
            state.copy(
                selectedFolders = next,
                items = state.items.map {
                    if (it.path == current && !it.isFile) it.copy(checked = true) else it
                },
            )
        }
        _events.trySend(FilePickerEvent.CurrentFolderSelected)
    }

    fun confirm() {
        val state = _state.value
        when (mode) {
            PickerMode.SingleFolder ->
                _events.trySend(FilePickerEvent.Finish(listOf(state.currentPath), true))
            PickerMode.MultiFolder -> {
                val folders = state.selectedFolders
                // 与旧版一致：只要勾选了目录即可返回，不强制凑够两个
                if (folders.isEmpty()) {
                    _events.trySend(FilePickerEvent.NeedSelectTwoFolders)
                } else {
                    _events.trySend(FilePickerEvent.Finish(folders, true))
                }
            }
            PickerMode.MultiFile -> {
                val selected = state.selectedFiles.toList()
                if (selected.isEmpty()) _events.trySend(FilePickerEvent.Cancel)
                else _events.trySend(FilePickerEvent.Finish(selected, false))
            }
            PickerMode.SingleFile -> _events.trySend(FilePickerEvent.Cancel)
        }
    }

    fun toggleFavorite(path: String) {
        // 默认目录不参与收藏（与列表按钮可见性 canFavorite 一致）
        if (path in DEFAULT_FAVORITES) return
        val current = _state.value.favorites
        val next = if (path in current) current - path else current + path
        // update 的 lambda 在 CAS 冲突时会重放，持久化 IO 必须放在外面
        favoriteStore.save(next.filterNot { it in DEFAULT_FAVORITES })
        _state.update { state ->
            state.copy(
                favorites = next,
                // 同步刷新列表项的收藏态，图标/文案才能立刻反映「设为/取消」
                items = state.items.map {
                    if (it.path == path) it.copy(isFavorite = path in next) else it
                },
            )
        }
    }

    fun onBack(): Boolean = navigateUp()
}
