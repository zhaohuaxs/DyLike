package me.lingci.dy.player.ui.compose.browser

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.EmptyState
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.MetaChip
import me.lingci.dy.player.ui.compose.components.SearchField
import me.lingci.dy.player.ui.compose.components.SortBar
import me.lingci.dy.player.ui.compose.components.SortOption
import me.lingci.dy.player.ui.compose.components.rememberStoragePermissionRequest
import me.lingci.dy.player.ui.compose.model.formatFileSize
import me.lingci.dy.player.ui.compose.playlist.PlaylistPickerSheet
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.ToastUtil

/**
 * 资源库浏览页。
 *
 * 本地存储的「内部存储 / 存储卡」不再各占一个资源库入口：进入本页后由标题栏右侧的
 * 卷胶囊切换，切换即换根目录并清空面包屑（对齐旧版文件管理器的使用习惯）。
 * 排序（字段 + 升降序）走 [SortBar]，选择结果落在 SpUtil，下次进入沿用。
 */
@Composable
fun BrowserScreen(
    sourceData: SourceData,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: BrowserViewModel = viewModel()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val volumes by viewModel.volumes.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 只有本地存储有「卷」的概念；WebDAV / SMB 的根恒等于配置里那一份
    val isLocal = sourceData.storageType() == StorageType.LOCAL_STORAGE
    val localVolumes = if (isLocal) volumes else emptyList()
    var volumeId by rememberSaveable(sourceData.id) { mutableStateOf("") }
    val activeVolume = remember(localVolumes, volumeId) {
        localVolumes.firstOrNull { it.id == volumeId } ?: localVolumes.firstOrNull()
    }
    // 卷根路径就是本地源这次的根；换卷等价于换一个 siteUrl，其余配置原样带过去
    val rootSource = remember(sourceData, activeVolume, isLocal) {
        if (isLocal && activeVolume != null) {
            sourceData.copy(siteUrl = activeVolume.path, schema = activeVolume.path)
        } else {
            sourceData
        }
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // 待加入播放列表的视频；非 null 即展示弹层（弹层挂在本页，不挂 item）
    var playlistVideos by remember { mutableStateOf<List<VideoData>?>(null) }

    // 本地浏览失败最常见的原因是存储权限被拒；直接给「授予权限」，授权后原地重连。
    val requestStoragePermission = rememberStoragePermissionRequest {
        viewModel.open(rootSource, force = true)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, isLocal, rootSource) {
        if (!isLocal) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (state.error == BrowserError.StoragePermission &&
                PermissionUtil.hasStorageAccess(context)
            ) {
                viewModel.open(rootSource, force = true)
            }
        }
    }

    // 连接由 ViewModel 持有且幂等：换卷会重连，从详情页返回则直接复用
    LaunchedEffect(rootSource) {
        viewModel.reloadPrefs()
        viewModel.open(rootSource)
    }

    BackHandler {
        if (!viewModel.navigateUp()) onBack()
    }

    // 排序 / 搜索都是纯函数派生：原始列表只保留一次 IO 结果，换排序不重新读盘
    val displayFiles = remember(state.files, searchQuery, prefs.sortKey, prefs.sortAscending) {
        val matched = state.files.filter { item ->
            searchQuery.isBlank() || item.name.contains(searchQuery, ignoreCase = true)
        }
        sortFiles(matched, prefs.sortKey, prefs.sortAscending)
    }

    BrowserContent(
        title = sourceData.title,
        rootLabel = activeVolume?.label ?: sourceData.title,
        volumes = localVolumes,
        activeVolume = activeVolume,
        breadcrumbs = state.breadcrumbs,
        files = displayFiles,
        query = searchQuery,
        // 连接阶段也算「加载中」：否则首帧 files 为空会先闪一下「没有文件」空态
        isLoading = state.loading || state.phase == BrowserPhase.Loading,
        error = state.error,
        sortKey = prefs.sortKey,
        sortAscending = prefs.sortAscending,
        onBack = onBack,
        onSelectVolume = { volume ->
            // 换卷即换根：清掉搜索词，连接与列表由 open() 在新 rootSource 上整体重置
            volumeId = volume.id
            searchQuery = ""
        },
        onRequestPermission = requestStoragePermission,
        onCrumbClick = viewModel::navigateTo,
        onQueryChange = { searchQuery = it },
        onSortKey = viewModel::setSortKey,
        onToggleSortOrder = viewModel::toggleSortAscending,
        onOpenFile = { item ->
            if (item.isFile) {
                viewModel.buildPlayList(displayFiles, item)?.let { (list, position) ->
                    if (prefs.longVideoMode) {
                        LongVideoActivity.start(context, list, position, false)
                    } else {
                        ShortVideoActivity.start(context, list, position, false)
                    }
                }
            } else {
                viewModel.openFolder(item)
            }
        },
        onAddToLibrary = { item ->
            viewModel.addToLibrary(item) { result ->
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
        onAddToPlaylist = { item ->
            viewModel.videosInFolder(item) { videos ->
                if (videos.isEmpty()) {
                    ToastUtil.showToast(context, context.getString(R.string.folder_no_video))
                } else {
                    playlistVideos = videos
                }
            }
        }
    )

    playlistVideos?.let { videos ->
        PlaylistPickerSheet(
            videos = videos,
            onDismiss = { playlistVideos = null }
        )
    }
}

/**
 * 浏览页的静态内容：只吃状态与回调，不碰存储连接、不读偏好，便于单独预览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowserContent(
    title: String,
    rootLabel: String,
    volumes: List<StorageVolume>,
    activeVolume: StorageVolume?,
    breadcrumbs: List<FileEntity>,
    files: List<FileEntity>,
    query: String,
    isLoading: Boolean,
    error: BrowserError?,
    sortKey: FileSortKey,
    sortAscending: Boolean,
    onBack: () -> Unit,
    onSelectVolume: (StorageVolume) -> Unit,
    onRequestPermission: () -> Unit,
    onCrumbClick: (Int) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortKey: (FileSortKey) -> Unit,
    onToggleSortOrder: () -> Unit,
    onOpenFile: (FileEntity) -> Unit,
    onAddToLibrary: (FileEntity) -> Unit,
    onAddToPlaylist: (FileEntity) -> Unit
) {
    val c = LocalDyLikeColors.current
    val sortOptions = FileSortKey.entries.map { SortOption(it, stringResource(it.labelRes)) }
    // 行菜单的目标提升到页面层：放在行内会随 item 回收一起销毁
    var menuFile by remember { mutableStateOf<FileEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), onBack)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Kicker("FILE BROWSER")
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 只有一个卷时不展示切换器，避免给一个点不出第二个选项的入口
            if (volumes.size > 1) {
                Spacer(Modifier.width(8.dp))
                VolumeSwitcher(
                    volumes = volumes,
                    active = activeVolume,
                    onSelect = onSelectVolume
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
                        text = if (index == 0) rootLabel else item.name,
                        icon = if (index == 0 || !item.isFile) Icons.Default.Folder else Icons.Default.Movie,
                        // 点任意一级即回退到该层（旧版 FileDirItemAdapter 的 removeEnd + nextDir）
                        onClick = if (index < breadcrumbs.lastIndex) {
                            { onCrumbClick(index) }
                        } else {
                            null
                        }
                    )
                }
            }
        }

        SearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.browser_search_hint),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
        )

        SortBar(
            options = sortOptions,
            selected = sortKey,
            ascending = sortAscending,
            onSelect = onSortKey,
            onToggleOrder = onToggleSortOrder,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            leading = stringResource(R.string.browser_item_count, files.size)
        )

        if (error != null) {
            val permissionDenied = error == BrowserError.StoragePermission
            val message = stringResource(
                when (error) {
                    BrowserError.UnsupportedSource -> R.string.browser_source_unsupported
                    BrowserError.ConnectFailed -> R.string.browser_connect_failed
                    BrowserError.StoragePermission -> R.string.permission_storage_title
                }
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message,
                    stringResource(
                        if (permissionDenied) R.string.permission_storage_rationale
                        else R.string.browser_error_body
                    ),
                    stringResource(
                        if (permissionDenied) R.string.permission_grant
                        else R.string.action_back
                    ),
                    if (permissionDenied) onRequestPermission else onBack
                )
            }
            return@Column
        }

        if (isLoading && files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            return@Column
        }

        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    stringResource(R.string.browser_empty_title),
                    if (query.isNotBlank()) {
                        stringResource(R.string.browser_empty_search)
                    } else {
                        stringResource(R.string.browser_empty_dir)
                    }
                )
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(files, key = { it.path }) { item ->
                FileRow(
                    item = item,
                    onClick = { onOpenFile(item) },
                    onMore = { menuFile = item }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 71.dp, end = 20.dp),
                    color = c.hairline
                )
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    val target = menuFile
    if (target != null) {
        ModalBottomSheet(
            onDismissRequest = { menuFile = null },
            sheetState = rememberModalBottomSheetState(),
            containerColor = c.surface
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    target.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                SheetAction(Icons.Default.Movie, stringResource(R.string.hint_add_to_media)) {
                    menuFile = null
                    onAddToLibrary(target)
                }
                SheetAction(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    stringResource(R.string.hint_add_to_playlist)
                ) {
                    menuFile = null
                    onAddToPlaylist(target)
                }
            }
        }
    }
}

/** 底部动作行：与播放列表页的长按菜单同一套视觉 */
@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary)
    }
}

/**
 * 存储卷切换器：胶囊上写当前卷名，点开列全部卷（含容量与当前标记）。
 */
@Composable
private fun VolumeSwitcher(
    volumes: List<StorageVolume>,
    active: StorageVolume?,
    onSelect: (StorageVolume) -> Unit
) {
    val c = LocalDyLikeColors.current
    var expanded by remember { mutableStateOf(false) }
    val shape = CircleShape

    Box {
        Row(
            Modifier.clip(shape)
                .background(c.surfaceRaised.copy(alpha = .68f))
                .border(1.dp, c.hairline, shape)
                .clickable { expanded = true }
                .heightIn(min = 36.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (active?.removable == true) Icons.Default.SdStorage else Icons.Default.Storage,
                contentDescription = null,
                tint = c.textTertiary,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                active?.label ?: stringResource(R.string.title_local_storage),
                style = MaterialTheme.typography.labelLarge,
                color = c.textPrimary,
                maxLines = 1
            )
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_switch_storage),
                tint = c.textTertiary,
                modifier = Modifier.size(16.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Text(
                stringResource(R.string.label_storage_location),
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                style = DyLikeType.overline,
                color = c.textTertiary
            )
            if (volumes.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.hint_no_storage_volume)) },
                    onClick = { expanded = false },
                    enabled = false
                )
            }
            volumes.forEach { volume ->
                val selected = volume.id == active?.id
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                volume.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = c.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (volume.capacity.isNotBlank()) {
                                Text(
                                    volume.capacity,
                                    style = DyLikeType.micro,
                                    color = c.textTertiary,
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(volume)
                    },
                    leadingIcon = {
                        Icon(
                            if (volume.removable) Icons.Default.SdStorage else Icons.Default.Storage,
                            contentDescription = null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    trailingIcon = if (selected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_current_storage),
                                tint = c.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@Composable
private fun FileRow(
    item: FileEntity,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val c = LocalDyLikeColors.current

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
                if (item.isFile) formatFileSize(item.size) else stringResource(R.string.browser_folder),
                style = DyLikeType.micro,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (item.isFile) {
            AppIconButton(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                stringResource(R.string.cd_play),
                onClick = onClick,
                tint = c.accent
            )
        } else {
            // 与旧版一致：只有目录行有「更多」，文件行直接点播放
            AppIconButton(
                Icons.Default.MoreVert,
                stringResource(R.string.cd_more_actions),
                onClick = onMore
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun BrowserContentPreview() {
    val volumes = listOf(
        StorageVolume(
            id = "primary",
            label = "内部存储",
            path = "/storage/emulated/0",
            capacity = "可用 32.10 GB · 共 116.30 GB",
            removable = false
        ),
        StorageVolume(
            id = "sd",
            label = "存储卡",
            path = "/storage/1234-5678",
            capacity = "可用 8.02 GB · 共 59.50 GB",
            removable = true
        )
    )
    val files = listOf(
        FileEntity(name = "2024 演唱会", path = "/video/b", isFile = false),
        FileEntity(name = "第 01 集.mp4", path = "/video/a/第 01 集.mp4", isFile = true, size = 734_003_200),
        FileEntity(name = "第 02 集.mp4", path = "/video/a/第 02 集.mp4", isFile = true, size = 612_004_100)
    )
    DyLikeTheme(mode = ThemeMode.Light, brand = BrandHue.Purple, dynamicColor = false) {
        BrowserContent(
            title = "本地存储",
            rootLabel = "内部存储",
            volumes = volumes,
            activeVolume = volumes.first(),
            breadcrumbs = listOf(FileEntity(name = "主页", path = "/storage/emulated/0", isFile = false)),
            files = files,
            query = "",
            isLoading = false,
            error = null,
            sortKey = FileSortKey.Name,
            sortAscending = true,
            onBack = {},
            onSelectVolume = {},
            onRequestPermission = {},
            onCrumbClick = {},
            onQueryChange = {},
            onSortKey = {},
            onToggleSortOrder = {},
            onOpenFile = {},
            onAddToLibrary = {},
            onAddToPlaylist = {}
        )
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
                onMore = {}
            )
            FileRow(
                item = FileEntity(name = "2024 演唱会", path = "/video/b", isFile = false),
                onClick = {},
                onMore = {}
            )
        }
    }
}
