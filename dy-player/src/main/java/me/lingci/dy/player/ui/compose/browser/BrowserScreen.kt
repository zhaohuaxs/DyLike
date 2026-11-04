package me.lingci.dy.player.ui.compose.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.EmptyState
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.MetaChip
import me.lingci.dy.player.ui.compose.components.SearchField
import me.lingci.dy.player.ui.compose.components.StatePill
import me.lingci.dy.player.ui.compose.model.formatFileSize
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.theme.findActivity
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.playlist.PlaylistSelectDialog
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.Comparators
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.notStartDot

private enum class FileFilter { ALL, FOLDER, VIDEO }

@Composable
fun BrowserScreen(
    sourceData: SourceData,
    onBack: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val context = LocalContext.current
    val viewModel: BrowserViewModel = viewModel()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()

    var storage by remember { mutableStateOf<IStorage?>(null) }
    var connected by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    var breadcrumbs by remember { mutableStateOf<List<FileEntity>>(emptyList()) }
    var rawFiles by remember { mutableStateOf<List<FileEntity>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(FileFilter.ALL) }

    val storage0 = remember(sourceData) { sourceData.toStorage() }
    DisposableEffect(storage0) {
        onDispose { storage0?.release() }
    }

    LaunchedEffect(storage0) {
        viewModel.reloadPrefs()
        if (storage0 == null) {
            connectionError = "资源库不受支持"
        } else {
            storage = storage0
            val ok = withContext(Dispatchers.IO) { storage0.testConnect() }
            if (!ok) {
                connectionError = "资源库连接失败"
            } else {
                connected = true
                breadcrumbs = listOf(storage0.rootFile())
            }
        }
    }

    val currentPath = breadcrumbs.lastOrNull()?.path ?: "/"
    LaunchedEffect(currentPath, connected, prefs.showHidden) {
        if (connected && storage != null) {
            isLoading = true
            rawFiles = emptyList()
            val items = mutableListOf<FileEntity>()
            withContext(Dispatchers.IO) {
                storage!!.listFile(currentPath, false).collect { item ->
                    if ((item.isFile.not() && (prefs.showHidden || item.name.notStartDot())) || item.name.isVideo()) {
                        items.add(item)
                    }
                }
            }
            items.sortWith(Comparators.fileEntityFolderComparator())
            rawFiles = items
            isLoading = false
        }
    }

    BackHandler {
        if (breadcrumbs.size > 1) {
            breadcrumbs = breadcrumbs.dropLast(1)
        } else {
            onBack()
        }
    }

    val displayFiles = remember(rawFiles, searchQuery, filter) {
        rawFiles.filter { item ->
            (searchQuery.isBlank() || item.name.contains(searchQuery, ignoreCase = true)) &&
            when (filter) {
                FileFilter.ALL -> true
                FileFilter.FOLDER -> !item.isFile
                FileFilter.VIDEO -> item.isFile
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Column(Modifier.padding(start = 6.dp)) {
                Kicker("FILE BROWSER")
                Text(
                    sourceData.title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (breadcrumbs.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                breadcrumbs.forEachIndexed { index, item ->
                    if (index > 0) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    MetaChip(
                        text = if (index == 0) sourceData.title else item.name,
                        icon = if (index == 0 || !item.isFile) Icons.Default.Folder else Icons.Default.Movie,
                        // 点任意一级即回退到该层（旧版 FileDirItemAdapter 的 removeEnd + nextDir）
                        onClick = if (index < breadcrumbs.lastIndex) {
                            { breadcrumbs = breadcrumbs.take(index + 1) }
                        } else {
                            null
                        }
                    )
                }
            }
        }

        SearchField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "搜索文件或文件夹",
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatePill("全部", filter == FileFilter.ALL) { filter = FileFilter.ALL }
            Spacer(Modifier.width(8.dp))
            StatePill("文件夹", filter == FileFilter.FOLDER) { filter = FileFilter.FOLDER }
            Spacer(Modifier.width(8.dp))
            StatePill("视频", filter == FileFilter.VIDEO) { filter = FileFilter.VIDEO }
            Spacer(Modifier.weight(1f))
            AppIconButton(Icons.AutoMirrored.Filled.Sort, "排序", onClick = { })
        }

        if (connectionError != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    connectionError!!,
                    "请检查资源库配置与网络连接",
                    "返回",
                    onBack
                )
            }
            return@Column
        }

        if (isLoading && displayFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            return@Column
        }

        if (displayFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    "没有文件",
                    if (searchQuery.isNotBlank()) "试试其他关键词" else "此目录为空"
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(displayFiles, key = { it.path }) { item ->
                FileRow(
                    item = item,
                    onClick = {
                        if (item.isFile) {
                            playVideo(item, displayFiles, sourceData, storage, prefs.longVideoMode, context)
                        } else {
                            breadcrumbs = breadcrumbs + item
                        }
                    },
                    onAddToLibrary = {
                        viewModel.addToLibrary(sourceData, item) { result ->
                            ToastUtil.showToast(
                                context,
                                context.getString(
                                    when (result) {
                                        AddLibraryResult.Added -> R.string.media_add_success
                                        AddLibraryResult.Exists -> R.string.media_add_exist
                                        AddLibraryResult.Failed -> R.string.media_add_failed
                                    }
                                )
                            )
                        }
                    },
                    onAddToPlaylist = {
                        storage?.let { storageNow ->
                            viewModel.videosInFolder(storageNow, item, sourceData) { videos ->
                                val activity = context.findActivity()
                                if (videos.isEmpty()) {
                                    ToastUtil.showToast(context, context.getString(R.string.folder_no_video))
                                } else if (activity != null) {
                                    PlaylistSelectDialog.show(activity, videos)
                                }
                            }
                        }
                    }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 71.dp, end = 20.dp),
                    color = c.hairline
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun FileRow(
    item: FileEntity,
    onClick: () -> Unit,
    onAddToLibrary: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    val c = LocalDyLikeColors.current
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(start = 20.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp))
                .background(if (item.isFile) c.surfaceRaised else c.accent.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (item.isFile) Icons.Default.Movie else Icons.Default.Folder,
                null,
                tint = if (item.isFile) c.textSecondary else c.accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            Text(
                item.name,
                style = if (item.isFile) DyLikeType.meta.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                ) else MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (item.isFile) formatFileSize(item.size) else "文件夹",
                style = DyLikeType.micro,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (item.isFile) {
            AppIconButton(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                "播放",
                onClick = onClick,
                tint = c.accent
            )
        } else {
            // 与旧版一致：只有目录行有「更多」，文件行直接点播放
            Box {
                AppIconButton(
                    Icons.Default.MoreVert,
                    stringResource(R.string.cd_more_actions),
                    onClick = { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hint_add_to_media)) },
                        onClick = {
                            menuOpen = false
                            onAddToLibrary()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.hint_add_to_playlist)) },
                        onClick = {
                            menuOpen = false
                            onAddToPlaylist()
                        }
                    )
                }
            }
        }
    }
}

private fun playVideo(
    clicked: FileEntity,
    allFiles: List<FileEntity>,
    sourceData: SourceData,
    storage: IStorage?,
    longVideoMode: Boolean,
    context: android.content.Context
) {
    if (storage == null) return
    val list = ArrayList<VideoData>()
    var position = 0
    allFiles.forEach { fileBean ->
        if (fileBean.isFile) {
            list.add(fileBean.toVideoData(storage, sourceData))
            if (fileBean.name == clicked.name) {
                position = list.size - 1
            }
        }
    }
    if (longVideoMode) {
        LongVideoActivity.start(context, list, position, false)
    } else {
        ShortVideoActivity.start(context, list, position, false)
    }
}

@Preview(showBackground = true)
@Composable
private fun FileRowPreview() {
    DyLikeTheme(mode = ThemeMode.Light, brand = BrandHue.Purple, dynamicColor = false) {
        Column {
            FileRow(
                item = FileEntity(name = "第 01 集.mp4", path = "/video/a/第 01 集.mp4", isFile = true, size = 734_003_200),
                onClick = {},
                onAddToLibrary = {},
                onAddToPlaylist = {}
            )
            FileRow(
                item = FileEntity(name = "2024 演唱会", path = "/video/b", isFile = false),
                onClick = {},
                onAddToLibrary = {},
                onAddToPlaylist = {}
            )
        }
    }
}
