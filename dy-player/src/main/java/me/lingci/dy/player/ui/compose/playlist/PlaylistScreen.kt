package me.lingci.dy.player.ui.compose.playlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.util.ToastUtil

/** 路由入口：订阅状态与一次性提示，页面本身无状态。 */
@Composable
fun PlaylistRoute(
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    vm: PlaylistViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 资源 id 只能在组合期解析，事件回调里用不了 stringResource
    val messageTexts = mapOf(
        PlaylistMessage.Created to stringResource(R.string.hint_playlist_created),
        PlaylistMessage.Renamed to stringResource(R.string.hint_playlist_renamed),
        PlaylistMessage.Deleted to stringResource(R.string.hint_playlist_deleted),
        PlaylistMessage.ModeUpdated to stringResource(R.string.hint_playlist_mode_changed),
        PlaylistMessage.AutoPlaySet to stringResource(R.string.hint_playlist_auto_play_set),
        PlaylistMessage.AutoPlayCleared to stringResource(R.string.hint_playlist_auto_play_cleared)
    )

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is PlaylistEvent.Message ->
                        ToastUtil.showToast(context, messageTexts.getValue(event.message))
                }
            }
        }
    }

    PlaylistScreen(
        state = state,
        onBack = onBack,
        onOpenPlaylist = onOpenPlaylist,
        onCreate = vm::create,
        onRename = vm::rename,
        onDelete = vm::delete,
        onSetPlayMode = vm::setPlayMode,
        onToggleAutoPlay = vm::toggleAutoPlay
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaylistScreen(
    state: PlaylistUiState,
    onBack: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onCreate: (String, Int) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onSetPlayMode: (String, Int) -> Unit,
    onToggleAutoPlay: (PlaylistUiModel) -> Unit
) {
    val c = LocalDyLikeColors.current

    var showCreate by remember { mutableStateOf(false) }
    var menuPlaylist by remember { mutableStateOf<PlaylistUiModel?>(null) }
    var renameTarget by remember { mutableStateOf<PlaylistUiModel?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistUiModel?>(null) }
    var modeTarget by remember { mutableStateOf<PlaylistUiModel?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Kicker("PLAYLISTS")
                Text("播放列表", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            }
            AppIconButton(Icons.Default.Add, "新建", { showCreate = true })
        }

        if (state.phase == PlaylistPhase.Empty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        null,
                        tint = c.textTertiary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("暂无播放列表，点击右上角创建", style = DyLikeType.meta, color = c.textTertiary)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.items, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        onClick = { onOpenPlaylist(playlist.id) },
                        onLongClick = { menuPlaylist = playlist }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, playMode ->
                onCreate(name, playMode)
                showCreate = false
            }
        )
    }

    menuPlaylist?.let { playlist ->
        ModalBottomSheet(
            onDismissRequest = { menuPlaylist = null },
            containerColor = c.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                MenuRow(Icons.Default.DriveFileRenameOutline, "重命名") {
                    renameTarget = playlist
                    menuPlaylist = null
                }
                MenuRow(Icons.Default.Delete, "删除") {
                    deleteTarget = playlist
                    menuPlaylist = null
                }
                MenuRow(
                    Icons.Default.PlayCircle,
                    if (playlist.isAutoPlay) "取消启动自动播放" else "设为启动自动播放"
                ) {
                    onToggleAutoPlay(playlist)
                    menuPlaylist = null
                }
                MenuRow(Icons.Default.PlayArrow, "播放类型") {
                    modeTarget = playlist
                    menuPlaylist = null
                }
            }
        }
    }

    renameTarget?.let { playlist ->
        RenameDialog(
            currentName = playlist.title,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRename(playlist.id, name)
                renameTarget = null
            }
        )
    }

    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除") },
            text = { Text("确定删除播放列表「${playlist.title}」？") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(playlist.id)
                    deleteTarget = null
                }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    modeTarget?.let { playlist ->
        PlayModeDialog(
            currentMode = playlist.playMode,
            onDismiss = { modeTarget = null },
            onSelect = { mode ->
                onSetPlayMode(playlist.id, mode)
                modeTarget = null
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistRow(
    playlist: PlaylistUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceRaised)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.PlaylistPlay,
            null,
            tint = c.accent,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "包含 ${playlist.itemCount} 条媒体",
                    style = DyLikeType.meta,
                    color = c.textTertiary
                )
                val modeText = when (playlist.playMode) {
                    1 -> "短"
                    2 -> "长"
                    else -> null
                }
                if (modeText != null) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        modeText,
                        style = DyLikeType.meta,
                        color = c.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(c.accent.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
        if (playlist.isAutoPlay) {
            Text(
                "自动",
                style = DyLikeType.meta,
                color = c.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.accent.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
    }
}

@Composable
private fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var playMode by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建播放列表") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("播放列表名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                val modes = listOf("全局" to 0, "短视频" to 1, "长视频" to 2)
                modes.forEach { (label, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { playMode = value }
                    ) {
                        RadioButton(selected = playMode == value, onClick = { playMode = value })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onConfirm(name.trim(), playMode)
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank()) onConfirm(name.trim())
            }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun PlayModeDialog(
    currentMode: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit
) {
    var selected by remember { mutableStateOf(currentMode) }
    val modes = listOf("全局" to 0, "短视频" to 1, "长视频" to 2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("播放类型") },
        text = {
            Column {
                modes.forEach { (label, value) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = value }
                    ) {
                        RadioButton(selected = selected == value, onClick = { selected = value })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selected) }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 520)
@Composable
private fun PlaylistContentPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        PlaylistScreen(
            state = PlaylistUiState(
                phase = PlaylistPhase.Content,
                items = listOf(
                    PlaylistUiModel("p1", "周末片单", 12, 2, true),
                    PlaylistUiModel("p2", "随手刷", 3, 1, false)
                )
            ),
            onBack = {},
            onOpenPlaylist = {},
            onCreate = { _, _ -> },
            onRename = { _, _ -> },
            onDelete = {},
            onSetPlayMode = { _, _ -> },
            onToggleAutoPlay = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 400)
@Composable
private fun PlaylistEmptyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        PlaylistScreen(
            state = PlaylistUiState(phase = PlaylistPhase.Empty),
            onBack = {},
            onOpenPlaylist = {},
            onCreate = { _, _ -> },
            onRename = { _, _ -> },
            onDelete = {},
            onSetPlayMode = { _, _ -> },
            onToggleAutoPlay = {}
        )
    }
}
