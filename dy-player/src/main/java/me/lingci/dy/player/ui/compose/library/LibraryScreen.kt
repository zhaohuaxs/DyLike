package me.lingci.dy.player.ui.compose.library

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.ui.compose.model.formatDuration
import me.lingci.dy.player.ui.compose.model.mediaTypeLabel
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.EmptyState
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.PosterTile
import me.lingci.dy.player.ui.compose.components.ResumeCard
import me.lingci.dy.player.ui.compose.components.SearchField
import me.lingci.dy.player.ui.compose.components.SectionHeader
import me.lingci.dy.player.ui.compose.components.ShortcutChip
import me.lingci.dy.player.ui.compose.components.StatePill
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode

/** 路由入口：只取 ViewModel 与订阅状态，页面本身无状态。 */
@Composable
fun LibraryRoute(
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

    LibraryScreen(
        state = state,
        onLoadMore = vm::loadMore,
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
        onTogglePin = onTogglePin
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onLoadMore: () -> Unit = {},
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
    onTogglePin: (MediaUiModel) -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("全部") }
    var menuMedia by remember { mutableStateOf<MediaUiModel?>(null) }

    val filteredList = remember(state.media, query, filter) {
        val byQuery = if (query.isBlank()) {
            state.media
        } else {
            // 标题在 ViewModel 里已解码，直接匹配；存量里的 `&#58;` 也不会再搜不到
            state.media.filter { it.title.contains(query, ignoreCase = true) }
        }
        if (filter == "全部") byQuery else byQuery.filter { mediaTypeLabel(it.type) == filter }
    }

    val filters = remember(state.media) {
        listOf("全部") + state.media.map { mediaTypeLabel(it.type) }.distinct().filter { it != "媒体" }
    }
    LaunchedEffect(filters) {
        if (filter != "全部" && filter !in filters) {
            filter = "全部"
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
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Kicker("PRIVATE SCREENING ROOM", accent = true)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "DyLike放映厅",
                            style = MaterialTheme.typography.displaySmall,
                            color = c.textPrimary
                        )
                    }
                    AppIconButton(Icons.Default.Add, "添加本地媒体库", onClick = onAddLocalMedia, tint = c.accent)
                    var headerMenu by remember { mutableStateOf(false) }
                    Box {
                        AppIconButton(Icons.Default.MoreVert, "更多", { headerMenu = true })
                        DropdownMenu(expanded = headerMenu, onDismissRequest = { headerMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("扫描媒体库") },
                                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp)) },
                                onClick = { headerMenu = false; onScanMedia() }
                            )
                            DropdownMenuItem(
                                text = { Text("导入在线媒体库") },
                                leadingIcon = { Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(20.dp)) },
                                onClick = { headerMenu = false; onImportOnlineMedia() }
                            )
                            DropdownMenuItem(
                                text = { Text("添加在线媒体库") },
                                leadingIcon = { Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp)) },
                                onClick = { headerMenu = false; onAddOnlineMedia() }
                            )
                        }
                    }
                }
            }
            if (state.resumeItems.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        "继续观看",
                        "最近播放",
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
                                remaining = "剩余 ${formatDuration(item.remainingMs)}",
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
                    ShortcutChip(Icons.Default.History, "最近观看", onClick = onHistory)
                    ShortcutChip(Icons.Default.FavoriteBorder, "我的喜欢", onClick = onLikes)
                    ShortcutChip(Icons.AutoMirrored.Filled.PlaylistPlay, "播放列表", onClick = onLists)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                SearchField(
                    query,
                    { query = it },
                    "搜索视频、媒体库",
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(top = 12.dp)
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filters, key = { it }) { f -> StatePill(f, filter == f) { filter = f } }
                }
            }
            if (filteredList.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(top = 60.dp)) {
                        EmptyState(
                            "还没有媒体",
                            "添加本地文件夹或连接远端资源。",
                            "浏览文件",
                            onBrowser
                        )
                    }
                }
            } else {
                itemsIndexed(filteredList, key = { _, media -> media.id }) { index, media ->
                    PosterTile(
                        media = media,
                        index = index,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        onLongClick = { menuMedia = media },
                        onClick = { onOpen(media.id) }
                    )
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
                        "编辑媒体库" to { menuMedia = null; onEditMedia(target) },
                        "删除媒体库" to { menuMedia = null; onDeleteMedia(target) },
                        "置顶/取消置顶" to { menuMedia = null; onTogglePin(target) }
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
                    MediaUiModel(id = "m2", title = "远端片源", type = MediaLibType.WEBDAV)
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
