package me.lingci.dy.player.ui.compose.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed as staggeredItemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.entity.formatTime
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.CoverImage
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.SearchField
import me.lingci.dy.player.ui.compose.components.ThumbImage
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.danmaku_binding.DanmakuBindingActivity
import me.lingci.dy.player.util.decodeHtmlEntities
import kotlin.math.abs

private val SORT_LABELS = listOf("名称升序", "名称降序", "修改时间升序", "修改时间降序")

/** 距离列表尾部还有这么多项就开始续页，留够 IO 时间，滑动不会看到空白等待。 */
private const val LOAD_MORE_AHEAD = 12

/** 剧集列表里，剧集之前的固定条目：顶栏、封面、搜索框。 */
private const val EPISODE_HEADER_ITEMS = 3

/** 超过这个项数就直接定位：动画滚动会一帧帧经过中间所有项，几百项会卡成幻灯片。 */
private const val SCROLL_ANIMATE_MAX_ITEMS = 24

/** 「上次播放」卡片的描边宽度。 */
private val LAST_PLAYED_BORDER = 2.dp

/** 列表底部固定预留的按钮高度：条件化 padding 会让整屏重新布局，滚动位置也会被拽动。 */
private val JUMP_BUTTON_SPACE = 76.dp

/** 按钮距列表底部的距离。 */
private val JUMP_BUTTON_GAP = 20.dp

/** 路由入口：只负责拿 ViewModel 与状态订阅，页面本身保持无状态。 */
@Composable
fun DetailRoute(
    mediaId: String,
    onBack: () -> Unit = {},
    onPlay: (List<VideoData>, Int, Boolean) -> Unit = { _, _, _ -> }
) {
    val vm: DetailViewModel = viewModel(factory = DetailViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(mediaId) { vm.load(mediaId) }

    // 一次性事件转成本地滚动请求：列表状态属于 Screen，这里只负责下发与消费
    var scrollRequest by remember { mutableStateOf<Int?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is DetailEvent.ScrollToEntry -> scrollRequest = event.index
                }
            }
        }
    }

    DetailScreen(
        state = state,
        scrollRequest = scrollRequest,
        onScrollConsumed = { scrollRequest = null },
        onBack = onBack,
        onPlay = onPlay,
        onRetry = { vm.load(mediaId, force = true) },
        onOpEd = vm::updateOpEd,
        onQueryChange = vm::setQuery,
        onSortChange = vm::setSortMode,
        onLoadMore = vm::loadMore,
        onJumpLastPlayed = vm::jumpToLastPlayed
    )
}

/**
 * 媒体库详情：短视频走瀑布流，长视频走线性剧集列表。
 *
 * 卡片数据（缩略图路径 / 宽高比 / 修改时间）全部来自 [ShortCardUiModel]，
 * 组合期不再做 md5、`File.exists()`、图片尺寸解码；并且只渲染 [DetailUiState.entries]
 * 这部分「已解析」的条目，滚动到底再续页——上千个视频也不会在首帧集中做 IO。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    state: DetailUiState,
    scrollRequest: Int? = null,
    onScrollConsumed: () -> Unit = {},
    onBack: () -> Unit = {},
    onPlay: (List<VideoData>, Int, Boolean) -> Unit = { _, _, _ -> },
    onRetry: () -> Unit = {},
    onOpEd: (Int, Int) -> Unit = { _, _ -> },
    onQueryChange: (String) -> Unit = {},
    onSortChange: (Int) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onJumpLastPlayed: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    val context = LocalContext.current
    val gridState = rememberLazyStaggeredGridState()
    val listState = rememberLazyListState()

    var hideText by rememberSaveable { mutableStateOf(true) }
    var opEdDialogOpen by remember { mutableStateOf(false) }

    val entries = state.entries
    val playList = state.playList
    val lastPlayedIndex = state.lastPlayedIndex

    // 目标项是否已在可见范围内。derivedStateOf 只在布尔翻转时触发重组，
    // 不会因为滚动过程中 layoutInfo 每帧变化而整屏重组。
    val lastPlayedIndexState = rememberUpdatedState(lastPlayedIndex)
    val gridLastPlayedVisible by remember(gridState) {
        derivedStateOf {
            val index = lastPlayedIndexState.value ?: return@derivedStateOf false
            gridState.layoutInfo.visibleItemsInfo.any { it.index == index }
        }
    }
    val listLastPlayedVisible by remember(listState) {
        derivedStateOf {
            val index = lastPlayedIndexState.value ?: return@derivedStateOf false
            listState.layoutInfo.visibleItemsInfo.any { it.index == index + EPISODE_HEADER_ITEMS }
        }
    }

    // 定位请求：距离远时直接跳（动画滚几百项会很卡），近处才用动画
    LaunchedEffect(scrollRequest, state.shortMode) {
        val target = scrollRequest ?: return@LaunchedEffect
        if (state.shortMode) {
            if (abs(target - gridState.firstVisibleItemIndex) > SCROLL_ANIMATE_MAX_ITEMS) {
                gridState.scrollToItem(target)
            } else {
                gridState.animateScrollToItem(target)
            }
        } else {
            val item = target + EPISODE_HEADER_ITEMS
            if (abs(item - listState.firstVisibleItemIndex) > SCROLL_ANIMATE_MAX_ITEMS) {
                listState.scrollToItem(item)
            } else {
                listState.animateScrollToItem(item)
            }
        }
        onScrollConsumed()
    }

    // 两条列表（瀑布流 / 剧集）各自监听自己的可见范围；inactive 的那条 layoutInfo 为空，不会误触发
    val loadedCount by rememberUpdatedState(entries.size)
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= loadedCount - LOAD_MORE_AHEAD) loadMore()
            }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { lastVisible ->
                if (lastVisible >= loadedCount - LOAD_MORE_AHEAD) loadMore()
            }
    }

    when (state.phase) {
        DetailPhase.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = c.accent)
            }
            return
        }
        DetailPhase.Error -> {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    stringResource(R.string.detail_load_failed),
                    style = DyLikeType.meta,
                    color = c.textTertiary
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            return
        }
        DetailPhase.Content -> Unit
    }

    val title = state.media?.title?.decodeHtmlEntities() ?: "媒体库详情"

    if (state.shortMode) {
        Column(Modifier.fillMaxSize()) {
            DetailTopBar(
                title = title,
                hideText = hideText,
                onBack = onBack,
                onSort = onSortChange,
                onToggleText = { hideText = !hideText }
            )
            SearchField(
                state.query,
                onQueryChange,
                stringResource(R.string.detail_search_videos),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.detail_no_match_video),
                        style = DyLikeType.meta,
                        color = c.textTertiary
                    )
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = when {
                        maxWidth < 520.dp -> 3
                        maxWidth < 900.dp -> 4
                        else -> 6
                    }
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        // 底部固定留出「回到上次播放」按钮的高度：条件化 padding 会让整屏重新布局
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 4.dp,
                            bottom = JUMP_BUTTON_SPACE
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp
                    ) {
                        staggeredItemsIndexed(
                            items = entries,
                            key = { _, entry -> entry.card.id },
                            contentType = { _, _ -> "short-card" }
                        ) { _, entry ->
                            ShortCard(
                                card = entry.card,
                                hideText = hideText,
                                isLastPlayed = entry.lastPlay,
                                onClick = { onPlay(playList, entry.playIndex, true) }
                            )
                        }
                    }
                    if (lastPlayedIndex != null && !gridLastPlayedVisible) {
                        LastPlayedJumpButton(
                            loading = state.jumpingToLastPlayed,
                            onClick = onJumpLastPlayed,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = JUMP_BUTTON_GAP)
                        )
                    }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                // 剧集列表也用固定尾部留白，避免按钮出现/消失时整屏重新布局
                contentPadding = PaddingValues(bottom = JUMP_BUTTON_SPACE)
            ) {
                item {
                    DetailTopBar(
                        title = title,
                        hideText = null,
                        onBack = onBack,
                        onSort = onSortChange,
                        onToggleText = {}
                    )
                }

                item {
                    val media = state.media
                    val showDanmaku = media != null &&
                        media.type != MediaLibType.HISTORY &&
                        media.type != MediaLibType.LIKE &&
                        media.type != MediaLibType.PLAYLIST
                    CoverHeader(
                        cover = state.cover,
                        videoCount = state.totalCount,
                        opValue = state.opOffset.formatTime(),
                        edValue = state.edOffset.formatTime(),
                        showDanmaku = showDanmaku,
                        onPlay = { onPlay(playList, lastPlayedIndex ?: 0, false) },
                        onOpEdClick = { opEdDialogOpen = true },
                        onDanmakuClick = {
                            media?.let { DanmakuBindingActivity.start(context, playList, it.id) }
                        }
                    )
                }

                item {
                    SearchField(
                        state.query,
                        onQueryChange,
                        stringResource(R.string.detail_search_episodes),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                if (entries.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.detail_no_match_episode),
                                style = DyLikeType.meta,
                                color = c.textTertiary
                            )
                        }
                    }
                } else {
                    itemsIndexed(entries, key = { _, entry -> entry.card.id }) { _, entry ->
                        EpisodeRow(
                            card = entry.card,
                            index = entry.playIndex,
                            isLastPlayed = entry.lastPlay,
                            onClick = { onPlay(playList, entry.playIndex, false) }
                        )
                    }
                }
            }
            if (lastPlayedIndex != null && !listLastPlayedVisible) {
                LastPlayedJumpButton(
                    loading = state.jumpingToLastPlayed,
                    onClick = onJumpLastPlayed,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = JUMP_BUTTON_GAP)
                )
            }
        }
    }

    if (opEdDialogOpen && state.media != null) {
        OpEdOffsetDialog(
            opOffset = state.opOffset,
            edOffset = state.edOffset,
            onConfirm = { op, ed ->
                onOpEd(op, ed)
                opEdDialogOpen = false
            },
            onDismiss = { opEdDialogOpen = false }
        )
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    hideText: Boolean?,
    onBack: () -> Unit,
    onSort: (Int) -> Unit,
    onToggleText: () -> Unit
) {
    val c = LocalDyLikeColors.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Kicker("MEDIA LIBRARY")
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            AppIconButton(Icons.Default.MoreVert, "排序", { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                SORT_LABELS.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSort(index)
                            menuOpen = false
                        }
                    )
                }
                if (hideText != null) {
                    DropdownMenuItem(
                        text = { Text(if (hideText) "显示文本" else "隐藏文本") },
                        onClick = {
                            onToggleText()
                            menuOpen = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverHeader(
    cover: MediaUiModel?,
    videoCount: Int,
    opValue: String,
    edValue: String,
    showDanmaku: Boolean,
    onPlay: () -> Unit,
    onOpEdClick: () -> Unit,
    onDanmakuClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
    ) {
        if (cover != null) {
            CoverImage(cover = cover, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(c.surfaceRaised))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
            )
        )
        // 封面中间的播放按钮补上点击响应：原来点封面是空操作
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.32f),
            modifier = Modifier.align(Alignment.Center).size(54.dp).clickable(onClick = onPlay)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(28.dp), tint = Color.White)
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).padding(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(onClick = onOpEdClick)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("OP $opValue", style = DyLikeType.meta, color = Color.White)
            Text("ED $edValue", style = DyLikeType.meta, color = Color.White)
        }
        if (showDanmaku) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp)
                    .clickable(onClick = onDanmakuClick)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Subtitles, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(16.dp)
        ) {
            Text(
                cover?.title ?: "",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "包含 $videoCount 条媒体",
                style = DyLikeType.meta,
                color = Color.White.copy(alpha = 0.78f)
            )
        }
    }
}

@Composable
private fun EpisodeRow(
    card: ShortCardUiModel,
    index: Int,
    isLastPlayed: Boolean,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val playLabel = stringResource(R.string.cd_play_episode, index + 1)
    val description = stringResource(
        if (isLastPlayed) R.string.cd_episode_last_played else R.string.cd_episode_desc,
        index + 1,
        card.title
    )
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isLastPlayed) c.accent.copy(alpha = 0.08f) else Color.Transparent)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(onClickLabel = playLabel, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))
                .background(c.surfaceRaised)
        ) {
            ThumbImage(
                path = card.thumbPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (isLastPlayed) {
                Text(
                    stringResource(R.string.detail_last_played),
                    style = DyLikeType.meta,
                    color = c.accent,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
            }
            Text(
                card.title,
                style = MaterialTheme.typography.bodyLarge,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            "${index + 1}",
            style = DyLikeType.meta,
            color = c.textTertiary,
            modifier = Modifier.size(28.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LastPlayedJumpButton(
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    Surface(
        shape = CircleShape,
        color = c.accent,
        shadowElevation = 6.dp,
        modifier = modifier
            .clip(CircleShape)
            .clickable(enabled = !loading, onClick = onClick)
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = c.onAccent
                )
            } else {
                Icon(
                    Icons.Filled.Replay,
                    contentDescription = null,
                    tint = c.onAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.detail_back_to_last_played),
                style = MaterialTheme.typography.labelLarge,
                color = c.onAccent
            )
        }
    }
}

@Composable
private fun OpEdOffsetDialog(
    opOffset: Int,
    edOffset: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val c = LocalDyLikeColors.current
    var op by remember { mutableStateOf(opOffset.toFloat()) }
    var ed by remember { mutableStateOf(edOffset.toFloat()) }
    val sliderColors = SliderDefaults.colors(
        thumbColor = c.accent,
        activeTrackColor = c.accent,
        inactiveTrackColor = c.hairline,
        activeTickColor = c.accent,
        inactiveTickColor = c.hairline
    )
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = c.surfaceRaised,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp)) {
                Text("片头片尾偏移", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Spacer(Modifier.height(16.dp))
                Text("片头 ${op.toInt().formatTime()}", style = DyLikeType.meta, color = c.textSecondary)
                Slider(op, { op = it }, valueRange = 0f..600f, colors = sliderColors)
                Spacer(Modifier.height(12.dp))
                Text("片尾 ${ed.toInt().formatTime()}", style = DyLikeType.meta, color = c.textSecondary)
                Slider(ed, { ed = it }, valueRange = 0f..600f, colors = sliderColors)
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    TextButton({ onConfirm(op.toInt(), ed.toInt()) }) { Text("确定") }
                }
            }
        }
    }
}

@Composable
private fun ShortCard(
    card: ShortCardUiModel,
    hideText: Boolean,
    isLastPlayed: Boolean,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(8.dp)
    val playLabel = stringResource(R.string.cd_play)
    // 渐变不随状态变化，remember 掉，避免每次重组重新分配 colorStops 数组
    val scrim = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.6f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.5f)
            )
        )
    }

    Column(
        Modifier.fillMaxWidth().clip(shape)
            .semantics(mergeDescendants = true) { contentDescription = card.title }
            .clickable(onClickLabel = playLabel, onClick = onClick)
    ) {
        // 尺寸只由 aspectRatio 决定：图片晚到也不改变卡片高度，瀑布流不会因此整体重排
        Box(
            Modifier.fillMaxWidth().aspectRatio(card.aspectRatio).clip(shape)
                .background(c.surfaceRaised)
                // 上次播放：描边高亮（border 在内容之后绘制，不会被图片盖住）
                .then(
                    if (isLastPlayed) Modifier.border(LAST_PLAYED_BORDER, c.accent, shape)
                    else Modifier
                )
        ) {
            ThumbImage(
                path = card.thumbPath,
                modifier = Modifier.fillMaxSize(),
                // 缩略图是后补的（生成晚于首帧）时，卡片高度不会跟着变，这里用 Crop 填满而不是留黑边
                contentScale = ContentScale.Crop
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.PlayCircle,
                        null,
                        tint = c.textTertiary,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Box(Modifier.fillMaxSize().background(scrim))
            if (isLastPlayed) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = c.accent,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text(
                        stringResource(R.string.detail_last_played),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = DyLikeType.meta,
                        color = c.onAccent
                    )
                }
            }
        }
        if (!hideText) {
            Spacer(Modifier.height(6.dp))
            Text(
                card.title,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 900)
@Composable
private fun DetailShortPreview() {
    val entries = remember {
        List(9) { index ->
            DetailEntryUi(
                card = ShortCardUiModel(
                    id = "preview-$index",
                    title = "第 ${index + 1} 集",
                    thumbPath = null,
                    aspectRatio = if (index % 3 == 0) 3f / 4f else 16f / 9f
                ),
                playIndex = index,
                lastPlay = index == 2
            )
        }
    }
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        DetailScreen(
            state = DetailUiState(
                phase = DetailPhase.Content,
                entries = entries,
                playList = emptyList(),
                totalCount = entries.size,
                shortMode = true,
                // 预览里带上「上次播放」：卡片描边 + 底部跳转按钮
                lastPlayedIndex = 2
            )
        )
    }
}
