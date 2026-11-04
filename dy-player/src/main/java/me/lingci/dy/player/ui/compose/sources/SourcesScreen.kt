package me.lingci.dy.player.ui.compose.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.ScreenTitle
import me.lingci.dy.player.ui.compose.components.SourceCard
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.storage.entity.StorageType

@Composable
fun SourcesScreen(
    onBrowser: (SourceData) -> Unit = {},
    onPlayStream: (url: String, headers: Map<String, String>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val viewModel: SourcesViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SourcesContent(
        state = state,
        onBrowser = onBrowser,
        onPlayStream = onPlayStream,
        onSaveSource = viewModel::saveSource,
        onDeleteSource = viewModel::deleteSource,
        modifier = modifier
    )
}

/**
 * 资源库（本地 / WebDAV / SMB / 串流）列表页。
 *
 * 标题栏右侧的「＋」是新入口：迁移到 Compose 后旧版 FAB 消失，新增 WebDAV / SMB
 * 资源库的入口只能从这里进（旧版行为见 SourceFragment 的 floatingButton）。
 */
@Composable
private fun SourcesContent(
    state: SourcesUiState,
    onBrowser: (SourceData) -> Unit,
    onPlayStream: (url: String, headers: Map<String, String>) -> Unit,
    onSaveSource: (SourceData) -> Unit,
    onDeleteSource: (SourceData) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    var streamDialogOpen by remember { mutableStateOf(false) }
    var typePickerOpen by rememberSaveable { mutableStateOf(false) }
    var pendingType by remember { mutableStateOf<StorageType?>(null) }
    // 长按命中的条目 → 弹出「编辑 / 删除」；选编辑再切到表单态
    var actionTarget by remember { mutableStateOf<SourceData?>(null) }
    var editingTarget by remember { mutableStateOf<SourceData?>(null) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth()) {
            ScreenTitle(stringResource(R.string.title_source), kicker = "LOCAL & REMOTE")
            FilledTonalIconButton(
                onClick = { typePickerOpen = true },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.content_desc_add_source)
                )
            }
        }
        Kicker("YOUR SOURCES", Modifier.padding(start = 20.dp, top = 26.dp, bottom = 6.dp))

        when (state) {
            SourcesUiState.Loading -> LoadingRow()
            is SourcesUiState.Error -> ErrorRow(state.message)
            is SourcesUiState.Content -> state.sources.forEach { source ->
                val type = source.type ?: StorageType.LOCAL_STORAGE
                SourceCard(
                    icon = sourceIcon(type),
                    title = source.title,
                    detail = source.siteUrl.ifBlank { source.schema.ifBlank { "未配置" } },
                    tint = sourceTint(type),
                    status = if (type == StorageType.LOCAL_STORAGE || type == StorageType.STREAM_LINK) "可用" else "在线",
                    statusColor = c.success,
                    onClick = {
                        if (type == StorageType.STREAM_LINK) streamDialogOpen = true
                        else onBrowser(source)
                    },
                    // 内置源（本地/外部存储、串流）在旧版也是不可编辑/删除的，这里保持同样的边界
                    onLongClick = if (type == StorageType.LOCAL_STORAGE || type == StorageType.STREAM_LINK) {
                        null
                    } else {
                        { actionTarget = source }
                    }
                )
            }
        }
        Spacer(Modifier.height(96.dp))
    }

    if (typePickerOpen) {
        AddSourceTypeSheet(
            onPick = {
                typePickerOpen = false
                pendingType = it
            },
            onDismiss = { typePickerOpen = false }
        )
    }

    pendingType?.let { type ->
        SourceFormSheet(
            type = type,
            onSave = onSaveSource,
            onDismiss = { pendingType = null }
        )
    }

    actionTarget?.let { target ->
        SourceActionSheet(
            title = target.title,
            onEdit = {
                actionTarget = null
                editingTarget = target
            },
            onDelete = {
                actionTarget = null
                onDeleteSource(target)
            },
            onDismiss = { actionTarget = null }
        )
    }

    editingTarget?.let { target ->
        SourceFormSheet(
            type = target.type ?: StorageType.WEBDAV,
            editing = target,
            onSave = onSaveSource,
            onDismiss = { editingTarget = null }
        )
    }

    if (streamDialogOpen) {
        var url by remember { mutableStateOf("") }
        var header by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { streamDialogOpen = false },
            title = { Text(stringResource(R.string.title_stream_link)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("播放地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = header,
                        onValueChange = { header = it },
                        label = { Text("请求头（可选，每行 key: value）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton({
                    if (url.isNotBlank()) {
                        val headers = header.lines()
                            .mapNotNull { line ->
                                val parts = line.split(":", limit = 2)
                                if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                            }.toMap()
                        onPlayStream(url.trim(), headers)
                    }
                    streamDialogOpen = false
                }) { Text("播放") }
            },
            dismissButton = { TextButton({ streamDialogOpen = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun LoadingRow() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ErrorRow(message: String) {
    Text(
        text = stringResource(R.string.hint_source_load_failed, message),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp)
    )
}

private fun sourceIcon(type: StorageType): ImageVector = when (type) {
    StorageType.LOCAL_STORAGE -> Icons.Default.PhoneAndroid
    StorageType.WEBDAV -> Icons.Default.CloudQueue
    StorageType.SMB -> Icons.Default.Storage
    StorageType.STREAM_LINK -> Icons.Default.Link
    else -> Icons.Default.Link
}

@Composable
private fun sourceTint(type: StorageType): Color {
    val c = LocalDyLikeColors.current
    return when (type) {
        StorageType.LOCAL_STORAGE -> c.accent
        StorageType.WEBDAV -> c.info
        StorageType.SMB -> c.warning
        StorageType.STREAM_LINK -> c.like
        else -> c.accent
    }
}

@Preview(showBackground = true)
@Composable
private fun SourcesContentPreview() {
    val sample = listOf(
        SourceData().apply {
            id = "__builtin_local__"
            type = StorageType.LOCAL_STORAGE
            title = "本地存储"
            siteUrl = "/storage/emulated/0/"
        },
        SourceData().apply {
            id = "dav-1"
            type = StorageType.WEBDAV
            title = "NAS 影音"
            siteUrl = "http://192.168.1.10:5244/dav"
        },
        SourceData().apply {
            id = "smb-1"
            type = StorageType.SMB
            title = "家庭共享"
            siteUrl = "smb://192.168.1.10:445/video"
        }
    )
    DyLikeTheme(mode = ThemeMode.Light, brand = BrandHue.Purple, dynamicColor = false) {
        SourcesContent(
            state = SourcesUiState.Content(sample),
            onBrowser = {},
            onPlayStream = { _, _ -> },
            onSaveSource = {},
            onDeleteSource = {}
        )
    }
}
