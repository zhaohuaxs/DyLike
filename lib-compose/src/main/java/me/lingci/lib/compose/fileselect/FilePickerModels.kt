package me.lingci.lib.compose.fileselect

import androidx.compose.runtime.Immutable

/** 文件选择器对外暴露的四种模式，由启动参数换算得到。 */
enum class PickerMode {
    MultiFile,
    SingleFile,
    SingleFolder,
    MultiFolder,
}

enum class FileKind {
    Folder,
    Video,
    Image,
    Text,
    Other,
}

/** 传给组件的不可变列表项模型：不直接把 IO 实体交给 Compose。 */
@Immutable
data class FileItemUiModel(
    val path: String,
    val name: String,
    val isFile: Boolean,
    val kind: FileKind,
    /** 已格式化的文件大小文本（目录为空串），由 ViewModel 在 IO 段算好。 */
    val sizeText: String = "",
    val childCount: Int = 0,
    val checked: Boolean = false,
    /** 是否在常用目录里：默认目录恒为 true 且不显示操作按钮。 */
    val isFavorite: Boolean = false,
    /** 是否显示常用操作按钮（文件、默认目录不显示）。 */
    val canFavorite: Boolean = false,
)

@Immutable
data class BreadcrumbUiModel(
    val path: String,
    val label: String,
    val isRoot: Boolean = false,
)

@Immutable
data class FilePickerUiState(
    val phase: Phase = Phase.Loading,
    val mode: PickerMode = PickerMode.SingleFile,
    val items: List<FileItemUiModel> = emptyList(),
    val breadcrumbs: List<BreadcrumbUiModel> = emptyList(),
    val favorites: List<String> = emptyList(),
    val selectedFiles: Set<String> = emptySet(),
    val selectedFolders: List<String> = emptyList(),
    val currentPath: String = "",
    val hasError: Boolean = false,
) {
    enum class Phase { Loading, Content, Empty, Error }
}

/** 一次性事件：Toast 文案在 Route 里映射到 stringResource，ViewModel 不持有文案。 */
sealed interface FilePickerEvent {
    data class Finish(val paths: List<String>, val folderMode: Boolean) : FilePickerEvent
    data object Cancel : FilePickerEvent
    data object NeedSelectTwoFolders : FilePickerEvent
    data object NeedConcreteFolder : FilePickerEvent
    data object CurrentFolderSelected : FilePickerEvent
}
