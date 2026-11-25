package me.lingci.dy.player.ui.compose.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.ui.compose.model.formatDuration
import me.lingci.dy.player.ui.compose.model.mediaTypeLabelRes
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.EmptyState
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.PosterTile
import me.lingci.dy.player.ui.compose.components.ResumeCard
import me.lingci.dy.player.ui.compose.components.SearchField
import me.lingci.dy.player.ui.compose.components.SectionHeader
import me.lingci.dy.player.ui.compose.components.ShortcutChip
import me.lingci.dy.player.ui.compose.components.SortBar
import me.lingci.dy.player.ui.compose.components.SortOption
import me.lingci.dy.player.ui.compose.components.StatePill
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.util.ToastUtil

/** 路由入口：只取 ViewModel 与订阅状态，页面本身无状态。 */
@Composable
fun LibraryRoute(
    /** 存储权限：会话内变化（首次授权 / 被撤销）时让查询重订阅，不再重建整棵 UI 树 */
    storageAccess: Boolean = true,
    onOpen: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onHistory: () -> Unit = {},
    onLikes: () -> Unit = {},
    onLists: () -> Unit = {},
    onBrowser: () -> Unit = {},
    onAddLocalMedia: () -> Unit = {},
    onAddOnlineMedia: () -> Unit = {},
    onImportOnlineMedia: () -> Unit = {},
    onScanMedia: () -> Unit = {},
    onEditMedia: (MediaUiModel) -> Unit = {},
    onDeleteMedia: (MediaUiModel) -> Unit = {},
    onTogglePin: (MediaUiModel) -> Unit = {},
    vm: LibraryViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    // 每次回到前台都重读设置与权限：封面比例可能改了，权限也可能被撤销或刚从系统设置授权
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshCoverAspect()
            vm.refreshStorageAccess()
            vm.refreshAutoPlay()
        }
    }

    // 权限在会话内变化（首次授权 / 被撤销）：立即重订阅查询，不等下一次 RESUMED
    LaunchedEffect(storageAccess) { vm.refreshStorageAccess() }

    // 批量删除的结果在页面层提示：删除本身跑在 ViewModel 里，成败与「哪些没删掉」只有这里有上下文
    LaunchedEffect(vm, context) {
        vm.events.collect { event ->
            when (event) {
                is LibraryEvent.BatchDeleted -> ToastUtil.showToast(
                    context,
                    if (event.failed == 0 && event.partial == 0) {
                        context.getString(R.string.library_delete_selected_done, event.deleted)
                    } else {
                        context.getString(
                            R.string.library_delete_selected_partial,
                            event.deleted,
                            event.partial,
                            event.failed
                        )
                    }
                )
                LibraryEvent.AutoPlaySet -> ToastUtil.showToast(
                    context,
                    context.getString(R.string.library_auto_play_set_done)
                )
                LibraryEvent.AutoPlayCleared -> ToastUtil.showToast(
                    context,
                    context.getString(R.string.library_auto_play_cleared_done)
                )
            }
        }
    }

    LibraryScreen(
        state = state,
        onLoadMore = vm::loadMore,
        onQueryChange = vm::setQuery,
        onTypeChange = vm::setTypeFilter,
        onClearFilters = vm::clearFilters,
        onRetry = vm::retry,
        onOpen = onOpen,
        onResume = onResume,
        onHistory = onHistory,
        onLikes = onLikes,
        onLists = onLists,
        onBrowser = onBrowser,
        onAddLocalMedia = onAddLocalMedia,
        onAddOnlineMedia = onAddOnlineMedia,
        onImportOnlineMedia = onImportOnlineMedia,
        onScanMedia = onScanMedia,
        onEditMedia = onEditMedia,
        onDeleteMedia = onDeleteMedia,
        onTogglePin = onTogglePin,
        onToggleAutoPlay = { vm.toggleAutoPlay(it.id) },
        onSortField = vm::setSortField,
        onToggleSortOrder = vm::toggleSortOrder,
        onStartSelection = vm::startSelection,
        onToggleSelection = vm::toggleSelection,
        onClearSelection = vm::clearSelection,
        onDeleteSelected = vm::deleteSelected
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onLoadMore: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onTypeChange: (MediaLibType?) -> Unit = {},
    onClearFilters: () -> Unit = {},
    onRetry: () -> Unit = {},
    onOpen: (String) -> Unit = {},
    onResume: (String) -> Unit = {},
    onHistory: () -> Unit = {},
    onLikes: () -> Unit = {},
    onLists: () -> Unit = {},
    onBrowser: () -> Unit = {},
    onAddLocalMedia: () -> Unit = {},
    onAddOnlineMedia: () -> Unit = {},
    onImportOnlineMedia: () -> Unit = {},
    onScanMedia: () -> Unit = {},
    onEditMedia: (MediaUiModel) -> Unit = {},
    onDeleteMedia: (MediaUiModel) -> Unit = {},
    onTogglePin: (MediaUiModel) -> Unit = {},
    onToggleAutoPlay: (MediaUiModel) -> Unit = {},
    onSortField: (LibrarySortField) -> Unit = {},
    onToggleSortOrder: () -> Unit = {},
    onStartSelection: (String) -> Unit = {},
    onToggleSelection: (String) -> Unit = {},
    onClearSelection: () -> Unit = {},
    /** 批量删除：成败由 ViewModel 记账，全部成功才退出选择模式 */
    onDeleteSelected: (Collection<String>) -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    var menuMedia by remember { mutableStateOf<MediaUiModel?>(null) }
    // 顶栏菜单状态放在列表外：item 滚出屏幕会被回收，状态跟着丢
    var headerMenu by remember { mutableStateOf(false) }
    var confirmBatchDelete by remember { mutableStateOf(false) }

    // 选择模式优先吃掉系统返回：默认行为会直接离开首页，而选中集合是 ViewModel 里的状态，
    // 返回后再进来仍然带着勾选，看起来像「返回键没生效」
    BackHandler(enabled = state.selectionMode) { onClearSelection() }

    val filters = remember(state.availableTypes) {
        listOf<MediaLibType?>(null) + state.availableTypes
    }
    LaunchedEffect(filters, state.filter) {
        if (state.filter != null && state.filter !in filters) {
            onTypeChange(null)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth < 520.dp -> 3
            maxWidth < 900.dp -> 4
            else -> 6
        }
        val gridState = rememberLazyGridState()
        // 只有真正拿到第一页之后才允许扩容：Loading 期列表项很少，滚到底会误触发，
        // 一旦提前把 pageSize 撑大，首批数据回来就变成「不满一页」而永远不再翻页
        val canLoadMore by rememberUpdatedState(
            state.phase == LibraryPhase.Content && state.hasMore
        )

        // key 只用 gridState：分页条件通过 rememberUpdatedState 读最新值，
        // 把 size / hasMore 放进 key 会让收集器每次扩容都重启一遍
        LaunchedEffect(gridState) {
            snapshotFlow {
                val info = gridState.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
            }.collect { (lastVisible, total) ->
                if (canLoadMore && lastVisible >= 0 && total > 0 && lastVisible >= total - 4) {
                    onLoadMore()
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (state.selectionMode) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.library_selection_count, state.selectedIds.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = c.textPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onClearSelection) {
                            Text(stringResource(R.string.action_cancel))
                        }
                        TextButton(
                            enabled = state.selectedIds.isNotEmpty() &&
                                !state.deleting &&
                                state.phase != LibraryPhase.Loading &&
                                state.phase != LibraryPhase.Error,
                            onClick = { confirmBatchDelete = true }
                        ) {
                            Text(
                                stringResource(R.string.hint_media_delete),
                                color = if (
                                    state.selectedIds.isEmpty() ||
                                    state.deleting ||
                                    state.phase == LibraryPhase.Loading ||
                                    state.phase == LibraryPhase.Error
                                ) {
                                    c.textTertiary
                                } else {
                                    c.accent
                                }
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Kicker("PRIVATE SCREENING ROOM", accent = true)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                stringResource(R.string.library_title),
                                style = MaterialTheme.typography.displaySmall,
                                color = c.textPrimary
                            )
                        }
                        AppIconButton(Icons.Default.Add, stringResource(R.string.library_add_local), onClick = onAddLocalMedia, tint = c.accent)
                        Box {
                            AppIconButton(Icons.Default.MoreVert, stringResource(R.string.action_more), { headerMenu = true })
                            DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.hint_media_scan)) },
                                    leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                                    onClick = { headerMenu = false; onScanMedia() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.hint_media_link_input)) },
                                    leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp)) },
                                    onClick = { headerMenu = false; onImportOnlineMedia() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.hint_media_link_add)) },
                                    leadingIcon = { Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp)) },
                                    onClick = { headerMenu = false; onAddOnlineMedia() }
                                )
                            }
                        }
                    }
                }
            }
            if (state.resumeItems.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        stringResource(R.string.library_continue_watching),
                        stringResource(R.string.library_recently_played),
                        kicker = "CONTINUE",
                        onClick = onHistory
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.resumeItems, key = { it.mediaId }) { item ->
                            ResumeCard(
                                title = item.mediaTitle,
                                episode = item.videoName,
                                remaining = stringResource(R.string.library_remaining, formatDuration(item.remainingMs)),
                                progress = item.progress,
                                coverPath = item.coverPath,
                                modifier = Modifier.width(240.dp),
                                onClick = { onResume(item.mediaId) }
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShortcutChip(Icons.Default.History, stringResource(R.string.sort_field_last_played), onClick = onHistory)
                    ShortcutChip(Icons.Default.FavoriteBorder, stringResource(R.string.title_likes), onClick = onLikes)
                    ShortcutChip(Icons.AutoMirrored.Filled.PlaylistPlay, stringResource(R.string.title_playlist), onClick = onLists)
                }
            }
            // 选择模式下收起搜索与筛选：它们只过滤显示，不会把已选项从集合里剔除，
            // 一旦允许中途改条件，用户就可能删掉屏幕上看不到的选中项
            if (!state.selectionMode) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SearchField(
                        state.query,
                        onQueryChange,
                        stringResource(R.string.library_search_hint),
                        Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filters, key = { it?.name ?: "all" }) { type ->
                            val label = stringResource(
                                type?.let { mediaTypeLabelRes(it) } ?: R.string.library_filter_all
                            )
                            StatePill(label, state.filter == type) { onTypeChange(type) }
                        }
                    }
                }
            }
            // 排序条：与筛选 chips 分两行，避免一行里挤两个可横向滚动的控件。
            // leading 始终显示库总行数，不被当前窗口、搜索或类型筛选截断。
            item(span = { GridItemSpan(maxLineSpan) }) {
                SortBar(
                    options = LibrarySortField.entries.map {
                        SortOption(it, stringResource(it.labelRes))
                    },
                    selected = state.sortField,
                    ascending = state.sortAscending,
                    onSelect = onSortField,
                    onToggleOrder = onToggleSortOrder,
                    leading = stringResource(R.string.sort_leading_media, state.totalCount),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            if (state.media.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp)) {
                        when (state.phase) {
                            LibraryPhase.Error -> EmptyState(
                                stringResource(R.string.library_error_title),
                                stringResource(R.string.library_error_body),
                                stringResource(R.string.library_error_action),
                                onRetry
                            )
                            LibraryPhase.Loading -> Unit
                            else -> if (state.totalCount == 0) {
                                EmptyState(
                                    stringResource(R.string.library_empty_title),
                                    stringResource(R.string.library_empty_body),
                                    stringResource(R.string.library_empty_action),
                                    onBrowser
                                )
                            } else {
                                EmptyState(
                                    stringResource(R.string.library_no_results_title),
                                    stringResource(R.string.library_no_results_body),
                                    stringResource(R.string.library_no_results_action),
                                    onClearFilters
                                )
                            }
                        }
                    }
                }
            } else {
                itemsIndexed(state.media, key = { _, media -> media.cardKey }) { index, media ->
                    Column(Modifier.fillMaxWidth()) {
                        PosterTile(
                            media = media,
                            index = index,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            coverAspect = state.coverAspect,
                            selected = media.cardKey in state.selectedIds,
                            selectionMode = state.selectionMode,
                            onLongClick = {
                                if (state.selectionMode) onToggleSelection(media.cardKey) else menuMedia = media
                            },
                            onClick = {
                                if (state.selectionMode) onToggleSelection(media.cardKey) else onOpen(media.id)
                            }
                        )
                        if (media.seasonCount > 1) {
                            Text(
                                stringResource(R.string.library_season_count, media.seasonCount),
                                style = MaterialTheme.typography.labelSmall,
                                color = c.textTertiary,
                                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                            )
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Spacer(Modifier.height(80.dp))
            }
        }

        // 长按菜单提升到页面级：放在 item 内会随 item 滚出屏幕被销毁，菜单凭空消失
        val target = menuMedia
        if (target != null) {
            val sheetState = rememberModalBottomSheetState()
            ModalBottomSheet(
                onDismissRequest = { menuMedia = null },
                sheetState = sheetState,
                containerColor = c.surface
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                    listOf(
                        stringResource(R.string.action_select) to { menuMedia = null; onStartSelection(target.cardKey) },
                        stringResource(R.string.hint_media_edit) to { menuMedia = null; onEditMedia(target) },
                        stringResource(R.string.hint_media_delete) to { menuMedia = null; onDeleteMedia(target) },
                        stringResource(R.string.hint_media_pin) to { menuMedia = null; onTogglePin(target) },
                        stringResource(
                            if (state.autoPlayMediaId == target.id) {
                                R.string.library_auto_play_cancel
                            } else {
                                R.string.library_auto_play_set
                            }
                        ) to { menuMedia = null; onToggleAutoPlay(target) }
                    ).forEach { (label, action) ->
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = action)
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.textPrimary
                            )
                        }
                    }
                }
            }
        }

        if (confirmBatchDelete) {
            AlertDialog(
                onDismissRequest = { confirmBatchDelete = false },
                title = { Text(stringResource(R.string.hint_media_delete)) },
                text = {
                    Text(
                        stringResource(
                            R.string.library_delete_selected_confirm,
                            state.selectedIds.size
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !state.deleting &&
                            state.selectedIds.isNotEmpty() &&
                            state.phase != LibraryPhase.Loading &&
                            state.phase != LibraryPhase.Error,
                        onClick = {
                            confirmBatchDelete = false
                            // 选择模式的退出交给 ViewModel：只有全部删成功才清空，
                            // 有失败项时它们仍保持选中，用户能直接重试
                            onDeleteSelected(state.selectedIds)
                        }
                    ) { Text(stringResource(R.string.action_confirmed)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmBatchDelete = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 900)
@Composable
private fun LibraryContentPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LibraryScreen(
            state = LibraryUiState(
                phase = LibraryPhase.Content,
                media = listOf(
                    MediaUiModel(id = "m1", title = "示例媒体库", type = MediaLibType.LOCAL),
                    MediaUiModel(id = "m2", title = "远端片源", type = MediaLibType.WEBDAV),
                    MediaUiModel(
                        id = "m3",
                        title = "示例多季剧集",
                        type = MediaLibType.LOCAL,
                        seriesKey = "series-demo",
                        seasonCount = 2
                    )
                ),
                resumeItems = listOf(
                    ResumeItemUi(
                        mediaId = "m1",
                        mediaTitle = "示例媒体库",
                        videoName = "第 03 集",
                        progress = 0.42f,
                        remainingMs = 900_000,
                        coverPath = null
                    )
                )
            ),
            onBrowser = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 700)
@Composable
private fun LibraryEmptyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LibraryScreen(state = LibraryUiState(phase = LibraryPhase.Empty), onBrowser = {})
    }
}
