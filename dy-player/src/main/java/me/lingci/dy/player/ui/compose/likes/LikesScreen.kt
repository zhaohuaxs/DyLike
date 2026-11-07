package me.lingci.dy.player.ui.compose.likes

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.ThumbImage
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode

/**
 * 「我的喜欢」入口。
 *
 * 起播走的是一次性事件：状态里只有纯 UI 模型，`List<VideoData>` 由 ViewModel 在事件里补齐，
 * 再交给播放页——这是播放层的既有契约，不该让 Room 实体回流进 [LikesUiState]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LikesRoute(
    onBack: () -> Unit,
    onPlay: (List<VideoData>, Int) -> Unit,
    vm: LikesViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is LikesEvent.Play -> onPlay(event.videos, event.index)
                }
            }
        }
    }

    LikesScreen(state = state, onBack = onBack, onPlay = vm::play)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LikesScreen(
    state: LikesUiState,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit
) {
    val c = LocalDyLikeColors.current
    val gridState = rememberLazyStaggeredGridState()
    var hideText by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

    val items = state.items
    val toggleLabel = stringResource(if (hideText) R.string.action_show_text else R.string.action_hide_text)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), onBack)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Kicker("MY FAVORITES")
                Text(
                    stringResource(R.string.title_likes),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary
                )
            }
            Text(
                stringResource(R.string.likes_count, items.size),
                style = DyLikeType.meta,
                color = c.textTertiary
            )
            Box {
                AppIconButton(
                    Icons.Default.MoreVert,
                    stringResource(R.string.cd_more_actions),
                    { menuOpen = true }
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(toggleLabel) },
                        leadingIcon = {
                            Icon(
                                if (hideText) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = c.textSecondary
                            )
                        },
                        onClick = { hideText = !hideText; menuOpen = false }
                    )
                }
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.FavoriteBorder,
                        null,
                        tint = c.textTertiary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.likes_empty),
                        style = DyLikeType.meta,
                        color = c.textTertiary
                    )
                }
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalItemSpacing = 10.dp
                ) {
                    itemsIndexed(items, key = { _, item -> item.key }) { index, item ->
                        LikeCard(
                            item = item,
                            hideText = hideText,
                            onClick = { onPlay(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LikeCard(
    item: LikeItemUi,
    hideText: Boolean,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(8.dp)
    val playLabel = stringResource(R.string.cd_play)
    val thumbPath = item.thumbPath

    // 只做宽高测量：解码 bounds 拿不到就按 16:9 兜底，图像加载仍交给 ThumbImage
    val aspectRatio by produceState(16f / 9f, thumbPath) {
        value = if (thumbPath != null) {
            withContext(Dispatchers.IO) {
                try {
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(thumbPath, opts)
                    val w = opts.outWidth
                    val h = opts.outHeight
                    if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.4f, 3f) else 16f / 9f
                } catch (e: Exception) {
                    16f / 9f
                }
            }
        } else 16f / 9f
    }

    Column(
        Modifier.fillMaxWidth().clip(shape)
            .semantics(mergeDescendants = true) {
                contentDescription = item.title
            }
            .clickable(onClickLabel = playLabel, onClick = onClick)
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(aspectRatio).clip(shape).background(c.surfaceRaised)
        ) {
            ThumbImage(
                path = thumbPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                placeholder = {
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.PlayCircle,
                            null,
                            tint = c.textTertiary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.6f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.5f)
                        )
                    )
                )
            )
        }
        if (!hideText) {
            Spacer(Modifier.height(6.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 640)
@Composable
private fun LikesEmptyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LikesScreen(state = LikesUiState(phase = LikesPhase.Empty), onBack = {}, onPlay = {})
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun LikesGridPreview() {
    val items = listOf(
        LikeItemUi("a1", "2024 演唱会 · 第 01 集", null),
        LikeItemUi("a2", "城市夜景延时摄影", null),
        LikeItemUi("a3", "第 02 集：返场", null),
        LikeItemUi("a4", "旅途随拍合集", null)
    )
    DyLikeTheme(ThemeMode.Light, BrandHue.Purple, false) {
        LikesScreen(
            state = LikesUiState(phase = LikesPhase.Content, items = items),
            onBack = {},
            onPlay = {}
        )
    }
}
