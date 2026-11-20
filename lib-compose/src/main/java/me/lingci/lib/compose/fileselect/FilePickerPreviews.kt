package me.lingci.lib.compose.fileselect

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "文件选择-内容")
@Composable
private fun FilePickerContentPreview() {
    MaterialTheme {
        FilePickerScreen(
            state = FilePickerUiState(
                phase = FilePickerUiState.Phase.Content,
                mode = PickerMode.MultiFile,
                currentPath = "/storage/emulated/0/Download",
                breadcrumbs = listOf(
                    BreadcrumbUiModel("/storage/emulated/0", "root", isRoot = true),
                    BreadcrumbUiModel("/storage/emulated/0/Download", "Download"),
                ),
                favorites = listOf("/storage/emulated/0/Download"),
                items = listOf(
                    FileItemUiModel(
                        path = "/storage/emulated/0/Download/示例剧集",
                        name = "示例剧集",
                        isFile = false,
                        kind = FileKind.Folder,
                        childCount = 12,
                    ),
                    FileItemUiModel(
                        path = "/storage/emulated/0/Download/backup.json",
                        name = "backup.json",
                        isFile = true,
                        kind = FileKind.Text,
                        sizeText = "2.00 kB",
                        checked = true,
                    ),
                ),
            ),
            onBack = {},
            onCrumbClick = {},
            onFavoriteClick = {},
            onItemClick = {},
            onCheck = {},
            onToggleFavorite = {},
            onSelectAll = {},
            onSelectInvert = {},
            onSelectCurrent = {},
            onConfirm = {},
        )
    }
}

@Preview(name = "文件选择-空")
@Composable
private fun FilePickerEmptyPreview() {
    MaterialTheme {
        FilePickerScreen(
            state = FilePickerUiState(phase = FilePickerUiState.Phase.Empty),
            onBack = {},
            onCrumbClick = {},
            onFavoriteClick = {},
            onItemClick = {},
            onCheck = {},
            onToggleFavorite = {},
            onSelectAll = {},
            onSelectInvert = {},
            onSelectCurrent = {},
            onConfirm = {},
        )
    }
}

@Preview(name = "文件选择-加载")
@Composable
private fun FilePickerLoadingPreview() {
    MaterialTheme {
        FilePickerScreen(
            state = FilePickerUiState(phase = FilePickerUiState.Phase.Loading),
            onBack = {},
            onCrumbClick = {},
            onFavoriteClick = {},
            onItemClick = {},
            onCheck = {},
            onToggleFavorite = {},
            onSelectAll = {},
            onSelectInvert = {},
            onSelectCurrent = {},
            onConfirm = {},
        )
    }
}
