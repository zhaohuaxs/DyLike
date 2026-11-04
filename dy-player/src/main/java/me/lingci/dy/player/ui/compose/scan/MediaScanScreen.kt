package me.lingci.dy.player.ui.compose.scan

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
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
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.ToastUtil

/** 路由入口：状态与事件、权限申请都收在这里，页面本身只画 UI。 */
@Composable
fun MediaScanRoute(
    onBack: () -> Unit = {},
    vm: MediaScanViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is MediaScanEvent.Message ->
                        ToastUtil.showToast(context, context.getString(event.message.textRes()))

                    is MediaScanEvent.Saved -> {
                        ToastUtil.showToast(context, context.getString(R.string.hint_scan_added, event.addedCount))
                        onBack()
                    }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { (_, granted) -> granted }) {
            vm.scan()
        } else {
            ToastUtil.showToast(context, context.getString(R.string.hint_scan_permission_denied))
        }
    }

    MediaScanScreen(
        state = state,
        onBack = onBack,
        onSelectStorage = vm::selectStorage,
        onScanNoMediaChange = vm::setScanNoMedia,
        onScanHideChange = vm::setScanHide,
        onMinFileSizeChange = vm::setMinFileSize,
        onScan = {
            if (PermissionUtil.checkPermissions(context, PermissionUtil.storagePermissions)) {
                vm.scan()
            } else {
                permissionLauncher.launch(PermissionUtil.storagePermissions)
            }
        },
        onToggleFolder = vm::toggleFolder,
        onSelectAll = vm::selectAllNew,
        onInvertSelection = vm::invertNewSelection,
        onSave = vm::saveSelected
    )
}

@Composable
fun MediaScanScreen(
    state: MediaScanUiState,
    onBack: () -> Unit = {},
    onSelectStorage: (StorageRootOption) -> Unit = {},
    onScanNoMediaChange: (Boolean) -> Unit = {},
    onScanHideChange: (Boolean) -> Unit = {},
    onMinFileSizeChange: (String) -> Unit = {},
    onScan: () -> Unit = {},
    onToggleFolder: (String) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onInvertSelection: () -> Unit = {},
    onSave: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    var storageMenuExpanded by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
                Column(Modifier.padding(start = 6.dp)) {
                    Kicker("SCAN LOCAL MEDIA")
                    Text("扫描本地媒体", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("选择存储", style = DyLikeType.meta, color = c.textSecondary)
                Spacer(Modifier.height(4.dp))
                Box {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { storageMenuExpanded = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            state.selectedStorage?.title ?: "本地存储",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = c.textPrimary
                        )
                        Icon(Icons.Default.ArrowDropDown, null, tint = c.textSecondary)
                    }
                    DropdownMenu(expanded = storageMenuExpanded, onDismissRequest = { storageMenuExpanded = false }) {
                        state.storageOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.title) },
                                onClick = {
                                    onSelectStorage(option)
                                    storageMenuExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("扫描 .nomedia", Modifier.weight(1f), color = c.textPrimary)
                    Switch(checked = state.scanNoMedia, onCheckedChange = onScanNoMediaChange)
                }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("扫描隐藏目录", Modifier.weight(1f), color = c.textPrimary)
                    Switch(checked = state.scanHide, onCheckedChange = onScanHideChange)
                }
                OutlinedTextField(
                    value = state.minFileSizeKb,
                    onValueChange = onMinFileSizeChange,
                    label = { Text("最小文件大小 (KB)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                )
            }
        }
        item {
            Button(
                onClick = onScan,
                enabled = !state.isScanning,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                if (state.isScanning) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("扫描中...")
                } else {
                    Text("执行扫描")
                }
            }
        }
        if (state.folders.isNotEmpty() || state.isScanning) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    Text(
                        "扫描结果：目录 ${state.folders.size}，已存在 ${state.existingCount}，新增 ${state.newCount}",
                        style = DyLikeType.meta, color = c.textSecondary
                    )
                    Text(
                        "总视频文件数：${state.totalVideoCount}",
                        style = DyLikeType.meta, color = c.textSecondary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        items(state.folders, key = { it.path }) { folder ->
            Row(
                Modifier.fillMaxWidth().clickable { onToggleFolder(folder.path) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (folder.selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    null,
                    tint = if (folder.existsInLibrary) c.textTertiary else if (folder.selected) c.accent else c.textSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        folder.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (folder.existsInLibrary) c.textTertiary else c.textPrimary,
                        maxLines = 1
                    )
                    Text(
                        folder.path,
                        style = DyLikeType.micro,
                        color = c.textTertiary,
                        maxLines = 1
                    )
                }
                Text(
                    "${folder.videoCount} 视频",
                    style = DyLikeType.micro,
                    color = c.textSecondary
                )
                if (folder.existsInLibrary) {
                    Spacer(Modifier.width(8.dp))
                    Text("已存在", style = DyLikeType.micro, color = c.textTertiary)
                }
            }
        }
        if (state.folders.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onSelectAll, enabled = !state.isScanning) { Text("全选新增") }
                    TextButton(onClick = onInvertSelection, enabled = !state.isScanning) { Text("反选新增") }
                    Button(
                        onClick = onSave,
                        enabled = !state.isScanning && state.selectedNewCount > 0,
                        colors = ButtonDefaults.buttonColors()
                    ) { Text("加入媒体库(${state.selectedNewCount})") }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

private fun MediaScanMessage.textRes(): Int = when (this) {
    MediaScanMessage.NoStorage -> R.string.hint_scan_no_storage
    MediaScanMessage.Scanning -> R.string.hint_scan_running
    MediaScanMessage.Failed -> R.string.hint_scan_failed
    MediaScanMessage.NoSelection -> R.string.hint_scan_no_selection
    MediaScanMessage.NoNewMedia -> R.string.hint_scan_no_new_media
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 760)
@Composable
private fun MediaScanPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MediaScanScreen(
            state = MediaScanUiState(
                storageOptions = listOf(StorageRootOption("本地存储", "/storage/emulated/0")),
                selectedStorage = StorageRootOption("本地存储", "/storage/emulated/0"),
                folders = listOf(
                    ScanFolderResult("/storage/emulated/0/Movies", "Movies", 24, existsInLibrary = false, selected = true),
                    ScanFolderResult("/storage/emulated/0/Download", "Download", 8, existsInLibrary = true)
                ),
                totalVideoCount = 32
            )
        )
    }
}
