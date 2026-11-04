package me.lingci.dy.player.ui.compose.media_add

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.ui.compose.components.GhostButton
import me.lingci.dy.player.ui.compose.components.PrimaryButton
import me.lingci.dy.player.ui.compose.components.ThumbImage
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.mediaChildSize
import me.lingci.lib.base.util.pathNoRoot
import java.io.File

private enum class BrowserMode { None, Folder, Cover }

/**
 * 路由入口：编辑态只传 id，实体与目录遍历都交给 [MediaManagerViewModel]。
 */
@Composable
fun MediaManagerSheet(
    editingMediaId: String? = null,
    onSave: (media: MediaData, update: Boolean) -> Unit,
    onDismiss: () -> Unit,
    vm: MediaManagerViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(editingMediaId, vm) { vm.start(editingMediaId) }

    MediaManagerSheetContent(
        state = state,
        onBrowse = vm::browse,
        onSubmit = { playMode, coverMode, name, folderPath, coverPath ->
            onSave(
                vm.build(playMode, coverMode, name, folderPath, coverPath),
                state.isEdit
            )
        },
        onDismiss = onDismiss
    )
}

/** 无状态表单：表单值是本地 UI 状态，读盘与落库都在 ViewModel。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaManagerSheetContent(
    state: MediaManagerUiState,
    onBrowse: (File, Boolean) -> Unit = { _, _ -> },
    onSubmit: (playMode: Int, coverMode: Int, name: String, folderPath: String, coverPath: String?) -> Unit =
        { _, _, _, _, _ -> },
    onDismiss: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    val context = LocalContext.current
    val rootPath = remember { FileOperator.rootFolder.path }
    val seed = state.seed
    val isEdit = state.isEdit

    var selectedPlayMode by remember(seed) { mutableIntStateOf(seed?.playMode ?: 0) }
    var selectedCoverMode by remember(seed) { mutableIntStateOf(seed?.coverMode ?: 0) }
    var selectedFolder by remember(seed) {
        mutableStateOf<FileEntity?>(
            seed?.folderPath?.takeIf { it.isNotBlank() }
                ?.let { FileEntity(name = seed.folderName, path = it, isFile = false) }
        )
    }
    var selectedCover by remember(seed) {
        mutableStateOf<FileEntity?>(
            seed?.coverPath?.takeIf { it.isNotBlank() }?.let { FileEntity(path = it, isFile = true) }
        )
    }
    var name by remember(seed) { mutableStateOf(seed?.name ?: "") }
    var browserMode by remember { mutableStateOf(BrowserMode.None) }
    var browserDir by remember { mutableStateOf(File(rootPath)) }

    // 目录内容跟着「当前目录 + 浏览类型」走：换目录就重新列，不再由组件里 produceState 各持一份
    LaunchedEffect(browserDir, browserMode) {
        when (browserMode) {
            BrowserMode.Folder -> onBrowse(browserDir, false)
            BrowserMode.Cover -> onBrowse(browserDir, true)
            BrowserMode.None -> Unit
        }
    }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface
    ) {
        if (state.loading) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            return@ModalBottomSheet
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                if (isEdit) "修改媒体库" else "添加媒体库",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = c.accent,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))

            Text("加载模式", style = DyLikeType.meta, color = c.textSecondary)
            Spacer(Modifier.height(6.dp))
            ModeRadioRow(
                options = listOf("全局" to 0, "短视频" to 1, "长视频" to 2),
                selected = selectedPlayMode,
                onSelect = { selectedPlayMode = it }
            )
            Spacer(Modifier.height(16.dp))

            Text("封面来源", style = DyLikeType.meta, color = c.textSecondary)
            Spacer(Modifier.height(6.dp))
            ModeRadioRow(
                options = listOf("默认" to 0, "本地选择" to 1, "自动匹配" to 2),
                selected = selectedCoverMode,
                onSelect = {
                    selectedCoverMode = it
                    if (it != 1) selectedCover = null
                }
            )
            Spacer(Modifier.height(16.dp))

            when (browserMode) {
                BrowserMode.Folder -> FolderBrowserSection(
                    browserDir = browserDir,
                    rootPath = rootPath,
                    folders = state.folders,
                    onNavigate = { browserDir = it },
                    onConfirm = {
                        if (browserDir.mediaChildSize() == 0) {
                            ToastUtil.showToast(context, "该目录不包含媒体，请重新选择")
                            return@FolderBrowserSection
                        }
                        selectedFolder = FileEntity(
                            name = browserDir.name,
                            path = browserDir.path,
                            isFile = false
                        )
                        name = browserDir.name
                        browserMode = BrowserMode.None
                    }
                )
                BrowserMode.Cover -> CoverBrowserSection(
                    browserDir = browserDir,
                    rootPath = rootPath,
                    items = state.images,
                    onNavigate = { browserDir = it },
                    onPickImage = {
                        selectedCover = it
                        browserMode = BrowserMode.None
                    }
                )
                BrowserMode.None -> CoverPreviewSection(
                    selectedFolder = selectedFolder,
                    selectedCoverMode = selectedCoverMode,
                    selectedCover = selectedCover,
                    onSelectFolder = {
                        browserDir = File(rootPath)
                        browserMode = BrowserMode.Folder
                    },
                    onSelectCover = {
                        browserDir = File(rootPath)
                        browserMode = BrowserMode.Cover
                    }
                )
            }

            if (selectedFolder != null && browserMode == BrowserMode.None) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        selectedFolder!!.path.replace(rootPath, "").ifBlank { "/" },
                        style = DyLikeType.meta,
                        color = c.textSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    GhostButton("重新选择", onClick = {
                        selectedFolder = null
                        selectedCover = null
                        name = ""
                    })
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                enabled = selectedFolder != null,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("资源库名称") },
                singleLine = true
            )
            Spacer(Modifier.height(16.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                GhostButton(
                    "取消",
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                PrimaryButton(
                    "确认",
                    enabled = selectedFolder != null && name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onSubmit(
                            selectedPlayMode,
                            selectedCoverMode,
                            name,
                            selectedFolder?.path.orEmpty(),
                            selectedCover?.path
                        )
                        onDismiss()
                    }
                )
            }
        }
    }
}

@Composable
private fun ModeRadioRow(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEach { (label, value) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSelect(value) }
            ) {
                RadioButton(
                    selected = selected == value,
                    onClick = { onSelect(value) }
                )
                Spacer(Modifier.width(4.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary)
            }
        }
    }
}

@Composable
private fun FolderBrowserSection(
    browserDir: File,
    rootPath: String,
    folders: List<FileEntity>,
    onNavigate: (File) -> Unit,
    onConfirm: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${browserDir.pathNoRoot()} 媒体数${browserDir.mediaChildSize()}",
            style = DyLikeType.meta,
            color = c.textSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (browserDir.path != rootPath) {
            GhostButton("返回上级", onClick = {
                onNavigate(browserDir.parentFile ?: File(rootPath))
            })
        }
    }
    Spacer(Modifier.height(8.dp))
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxWidth().height(280.dp)
    ) {
        items(folders, key = { it.path }) { folder ->
            FolderItem(folder) { onNavigate(File(folder.path)) }
        }
    }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(
        "确认选择",
        modifier = Modifier.fillMaxWidth(),
        onClick = onConfirm
    )
}

@Composable
private fun CoverBrowserSection(
    browserDir: File,
    rootPath: String,
    items: List<FileEntity>,
    onNavigate: (File) -> Unit,
    onPickImage: (FileEntity) -> Unit
) {
    if (browserDir.path != rootPath) {
        GhostButton("返回上级", onClick = {
            onNavigate(browserDir.parentFile ?: File(rootPath))
        })
        Spacer(Modifier.height(8.dp))
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxWidth().height(320.dp)
    ) {
        items(items, key = { it.path }) { item ->
            if (item.isFile) {
                ImageItem(item) { onPickImage(item) }
            } else {
                FolderItem(item, compact = true) { onNavigate(File(item.path)) }
            }
        }
    }
}

@Composable
private fun CoverPreviewSection(
    selectedFolder: FileEntity?,
    selectedCoverMode: Int,
    selectedCover: FileEntity?,
    onSelectFolder: () -> Unit,
    onSelectCover: () -> Unit
) {
    val c = LocalDyLikeColors.current
    if (selectedFolder == null) {
        GhostButton(
            "选择文件夹",
            modifier = Modifier.fillMaxWidth(),
            onClick = onSelectFolder
        )
        return
    }
    when (selectedCoverMode) {
        0 -> {
            Image(
                painter = painterResource(R.drawable.ic_media_default),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        1 -> {
            if (selectedCover != null) {
                PathImage(
                    selectedCover.path,
                    Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
                )
            } else {
                GhostButton(
                    "选择封面文件",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSelectCover
                )
            }
        }
        2 -> {
            Box(
                Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.surfaceRaised),
                contentAlignment = Alignment.Center
            ) {
                Text("系统将自动匹配封面", style = DyLikeType.meta, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun FolderItem(folder: FileEntity, compact: Boolean = false, onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Folder, null, tint = c.textSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            folder.name,
            style = MaterialTheme.typography.bodyMedium,
            color = c.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ImageItem(item: FileEntity, onClick: () -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
    ) {
        PathImage(item.path, Modifier.fillMaxSize())
    }
}

@Composable
private fun PathImage(path: String, modifier: Modifier = Modifier) {
    ThumbImage(path = path, modifier = modifier, contentScale = ContentScale.Crop)
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 720)
@Composable
private fun MediaManagerSheetPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MediaManagerSheetContent(
            state = MediaManagerUiState(
                seed = MediaFormSeed(
                    id = "local:/sdcard/Movies",
                    name = "示例媒体库",
                    folderPath = "/storage/emulated/0/Movies",
                    folderName = "Movies"
                )
            )
        )
    }
}
