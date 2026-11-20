package me.lingci.lib.compose.fileselect

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.lingci.lib.compose.R

/**
 * 无状态文件选择页：只渲染 UiState 并把操作转成回调。
 * 主题走 MaterialTheme.colorScheme，作为基础库不依赖任何 app 模块的自定义主题。
 */
@Composable
internal fun FilePickerScreen(
    state: FilePickerUiState,
    onBack: () -> Unit,
    onCrumbClick: (Int) -> Unit,
    onFavoriteClick: (String) -> Unit,
    onItemClick: (String) -> Unit,
    onCheck: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelectAll: () -> Unit,
    onSelectInvert: () -> Unit,
    onSelectCurrent: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            FilePickerTopBar(
                state = state,
                onBack = onBack,
                onSelectAll = onSelectAll,
                onSelectInvert = onSelectInvert,
                onSelectCurrent = onSelectCurrent,
                onConfirm = onConfirm,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            FavoriteFoldersRow(
                favorites = state.favorites,
                onClick = onFavoriteClick,
            )
            BreadcrumbRow(
                crumbs = state.breadcrumbs,
                onClick = onCrumbClick,
            )
            when (state.phase) {
                FilePickerUiState.Phase.Loading -> LoadingBox()
                FilePickerUiState.Phase.Error -> MessageBox(
                    text = stringResource(R.string.file_picker_error),
                )
                FilePickerUiState.Phase.Empty -> MessageBox(
                    text = stringResource(R.string.file_picker_empty),
                )
                FilePickerUiState.Phase.Content -> FileList(
                    state = state,
                    onItemClick = onItemClick,
                    onCheck = onCheck,
                    onToggleFavorite = onToggleFavorite,
                )
            }
        }
    }
}

@Composable
private fun FileList(
    state: FilePickerUiState,
    onItemClick: (String) -> Unit,
    onCheck: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
) {
    // 单一菜单状态：同一时刻只有一个目录的收藏菜单展开，避免逐项 remember 串位
    var menuPath by remember { mutableStateOf<String?>(null) }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items = state.items, key = { it.path }) { item ->
            FileItemRow(
                item = item,
                mode = state.mode,
                favoriteMenuExpanded = menuPath == item.path,
                onClick = { onItemClick(item.path) },
                onCheck = { onCheck(item.path) },
                onToggleFavoriteMenu = {
                    menuPath = if (menuPath == item.path) null else item.path
                },
                onDismissFavoriteMenu = { menuPath = null },
                onFavorite = { onToggleFavorite(item.path) },
            )
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun MessageBox(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        )
    }
}
