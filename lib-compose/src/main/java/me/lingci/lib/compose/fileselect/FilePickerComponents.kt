package me.lingci.lib.compose.fileselect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FlipToBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lingci.lib.compose.R

private val IconSize = 40.dp
private val RowHeight = 60.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilePickerTopBar(
    state: FilePickerUiState,
    onBack: () -> Unit,
    onSelectAll: () -> Unit,
    onSelectInvert: () -> Unit,
    onSelectCurrent: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showOverflow by remember { mutableStateOf(false) }
    val multiMode = state.mode == PickerMode.MultiFile || state.mode == PickerMode.MultiFolder
    val folderMulti = state.mode == PickerMode.MultiFolder

    TopAppBar(
        modifier = modifier,
        title = { Text(stringResource(R.string.file_picker_title)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                )
            }
        },
        actions = {
            if (multiMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, contentDescription = stringResource(R.string.file_picker_select_all))
                }
                IconButton(onClick = onSelectInvert) {
                    Icon(Icons.Default.FlipToBack, contentDescription = stringResource(R.string.file_picker_select_invert))
                }
            }
            if (state.mode != PickerMode.SingleFile) {
                IconButton(onClick = onConfirm) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.file_picker_confirm))
                }
            }
            if (folderMulti) {
                Box {
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showOverflow,
                        onDismissRequest = { showOverflow = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.file_picker_select_current)) },
                            onClick = { showOverflow = false; onSelectCurrent() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
internal fun FavoriteFoldersRow(
    favorites: List<String>,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (favorites.isEmpty()) return
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(favorites, key = { _, path -> path }) { _, path ->
            val label = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable { onClick(path) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun BreadcrumbRow(
    crumbs: List<BreadcrumbUiModel>,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(crumbs, key = { _, crumb -> crumb.path }) { index, crumb ->
            val label = if (crumb.isRoot) "/" else crumb.label
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (index == crumbs.lastIndex) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClick(index) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // root 自身的 "/" 已经是分隔符，后面不能再追加，否则出现 "/ / Download"
            if (index != crumbs.lastIndex && !crumb.isRoot) {
                Text("/", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
internal fun FileItemRow(
    item: FileItemUiModel,
    mode: PickerMode,
    favoriteMenuExpanded: Boolean,
    onClick: () -> Unit,
    onCheck: () -> Unit,
    onToggleFavoriteMenu: () -> Unit,
    onDismissFavoriteMenu: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(RowHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when (item.kind) {
                FileKind.Folder -> Icons.Default.Folder
                FileKind.Video -> Icons.Default.Movie
                FileKind.Image -> Icons.Default.Image
                FileKind.Text -> Icons.Default.Description
                FileKind.Other -> Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            tint = when (item.kind) {
                FileKind.Folder -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(IconSize),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = if (item.isFile) item.sizeText
            else stringResource(R.string.file_picker_folder_hint, item.childCount)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.isFile && item.canFavorite) {
            Box {
                IconButton(onClick = onToggleFavoriteMenu) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = stringResource(
                            if (item.isFavorite) R.string.file_picker_unset_favorite
                            else R.string.file_picker_set_favorite
                        ),
                        tint = if (item.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 与旧版一致：点击弹出「设为常用 / 取消常用」选项，不直接执行
                DropdownMenu(
                    expanded = favoriteMenuExpanded,
                    onDismissRequest = onDismissFavoriteMenu,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (item.isFavorite) R.string.file_picker_unset_favorite
                                    else R.string.file_picker_set_favorite
                                )
                            )
                        },
                        onClick = {
                            onDismissFavoriteMenu()
                            onFavorite()
                        },
                    )
                }
            }
        }
        val showCheck = (mode == PickerMode.MultiFile && item.isFile) ||
            (mode == PickerMode.MultiFolder && !item.isFile)
        if (showCheck) {
            IconButton(onClick = onCheck) {
                Icon(
                    imageVector = if (item.checked) Icons.Default.CheckCircle
                    else Icons.Default.RadioButtonUnchecked,
                    contentDescription = item.name,
                    tint = if (item.checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
