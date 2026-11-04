package me.lingci.dy.player.ui.compose.media_add

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.FileOperator
import java.io.File

/** 编辑态的初值：只带表单要回填的字段，不带实体。 */
@Immutable
data class MediaFormSeed(
    val id: String = "",
    val name: String = "",
    val folderPath: String = "",
    val folderName: String = "",
    val playMode: Int = 0,
    /** 0 默认 / 1 本地选择 / 2 自动匹配，与界面上的单选一一对应 */
    val coverMode: Int = 0,
    val coverPath: String = ""
)

@Immutable
data class MediaManagerUiState(
    val loading: Boolean = false,
    val isEdit: Boolean = false,
    val seed: MediaFormSeed? = null,
    val folders: List<FileEntity> = emptyList(),
    val images: List<FileEntity> = emptyList(),
    val browsing: Boolean = false
)

/**
 * 「本地媒体库」表单的数据侧。
 *
 * 收回来的是三件以前写在 Composable/Activity 里的事：
 * - 编辑态按 id 读库回填（不再由宿主把实体一路传进来）；
 * - 目录 / 封面浏览的磁盘遍历（原来是 `produceState` + `withContext(IO)`）；
 * - 实体装配与 `mediaId` 归一（原来是组件里拼 `LibraryCompat.mediaId`）。
 */
class MediaManagerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MediaManagerUiState())
    val state: StateFlow<MediaManagerUiState> = _state.asStateFlow()

    /** 正在跑的目录遍历：换目录 / 关表单时取消，避免旧结果覆盖新目录 */
    private var browseJob: Job? = null

    /** 每次打开都重置：表单关闭后 ViewModel 仍然存活，不能带着上一轮的实体回来 */
    fun start(mediaId: String?) {
        if (mediaId == null) {
            _state.value = MediaManagerUiState(isEdit = false, seed = MediaFormSeed())
            return
        }
        _state.value = MediaManagerUiState(loading = true, isEdit = true)
        viewModelScope.launch {
            val media = withContext(Dispatchers.IO) { DatabaseManager.mediaDataDao.getById(mediaId) }
            _state.update {
                it.copy(
                    loading = false,
                    // 查不到（被并发删除）时退化为新增表单，不把空表单锁死
                    isEdit = media != null,
                    seed = media?.toSeed() ?: MediaFormSeed()
                )
            }
        }
    }

    /**
     * 列目录：`images = false` 只列子目录，`true` 列图片（封面选择器）。
     *
     * 连着换两层目录时取消上一次遍历：用「正在浏览」当闸门会把后一次请求直接丢掉，
     * 而界面的 `LaunchedEffect` 已经认为它发出去了，列表就停在旧目录。
     */
    fun browse(dir: File, images: Boolean) {
        browseJob?.cancel()
        _state.update { it.copy(browsing = true) }
        browseJob = viewModelScope.launch {
            val (folders, files) = withContext(Dispatchers.IO) {
                if (images) {
                    emptyList<FileEntity>() to
                        FileOperator.getSortedFiles(dir, true, FileOperator.IMAGE_EXTENSIONS)
                            .map { FileEntity(it) }
                } else {
                    FileOperator.getSortedFolders(dir).map { FileEntity(it) } to emptyList()
                }
            }
            _state.update {
                it.copy(
                    folders = folders,
                    images = files,
                    browsing = false
                )
            }
        }
    }

    /**
     * 装配实体：id 在编辑态沿用原值，新增留空由落库方按 path 归一。
     *
     * 同步返回而不是走事件通道：表单关闭后收集方就没了，事件一旦滞留，
     * 下次打开会补发一次「迟到的保存」。
     */
    fun build(
        playMode: Int,
        coverMode: Int,
        name: String,
        folderPath: String,
        coverPath: String?
    ): MediaData {
        val seed = _state.value.seed ?: MediaFormSeed()
        val coverType = when (coverMode) {
            1 -> CoverType.CUSTOM
            2 -> CoverType.AUTO
            else -> CoverType.DEFAULT
        }
        return MediaData(
            id = seed.id,
            title = name.trim(),
            path = folderPath,
            coverType = coverType,
            showFile = when (coverType) {
                CoverType.CUSTOM -> coverPath.orEmpty()
                else -> ""
            },
            type = MediaLibType.LOCAL,
            storageType = StorageType.LOCAL_STORAGE,
            playMode = playMode
        )
    }

    private fun MediaData.toSeed() = MediaFormSeed(
        id = id,
        name = title,
        folderPath = path,
        folderName = title,
        playMode = playMode,
        coverMode = when (coverType) {
            CoverType.CUSTOM -> if (showFile.isNotBlank() && showFile != "null") 1 else 0
            CoverType.AUTO -> 2
            else -> 0
        },
        coverPath = if (coverType == CoverType.CUSTOM) showFile else ""
    )
}
