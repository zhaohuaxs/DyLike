package me.lingci.dy.player.ui.compose.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.model.formatDuration
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.CoverPlaceholder
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.ThumbImage
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.util.decodeHtmlEntities

/** 路由入口：只负责取 ViewModel、订阅状态与一次性事件，页面本身保持无状态。 */
@Composable
fun HistoryRoute(
    onBack: () -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    onPlay: (List<VideoData>, Int, Boolean) -> Unit = { _, _, _ -> },
    vm: HistoryViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // 事件收集：进程恢复时不丢事件，重复组合也不会重复消费
    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is HistoryEvent.PlaySingle -> onPlay(listOf(event.video), 0, vm.shortVideoMode)
                }
            }
        }
    }

    HistoryScreen(
        state = state,
        onBack = onBack,
        onOpenDetail = onOpenDetail,
        onPlaySingle = vm::playSingle,
        onDelete = vm::deleteItem,
        onClearAll = vm::clearAll
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit = {},
    onOpenDetail: (String) -> Unit = {},
    onPlaySingle: (HistoryItemUi.Single) -> Unit = {},
    onDelete: (HistoryItemUi) -> Unit = {},
    onClearAll: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current

    var deleteTarget by remember { mutableStateOf<HistoryItemUi?>(null) }
    var showClearAll by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Kicker("WATCH HISTORY")
                    Text(
                        stringResource(R.string.title_history),
                        style = MaterialTheme.typography.headlineSmall,
                        color = c.textPrimary
                    )
                }
                // 只有「清空历史」一个动作，不必再套一层下拉菜单（下拉菜单在 item 内还会随回收销毁）
                AppIconButton(
                    Icons.Default.MoreVert,
                    stringResource(R.string.action_more),
                    { showClearAll = true }
                )
            }
        }
        if (state.phase == HistoryPhase.Empty) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.hint_history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textTertiary
                    )
                }
            }
        } else {
            items(state.items, key = { it.key }) { entry ->
                HistoryRow(
                    entry = entry,
                    onClick = {
                        when (entry) {
                            is HistoryItemUi.Group ->
                                if (entry.exists && entry.sourceType == PlaySource.LIBRARY) {
                                    onOpenDetail(entry.mediaId)
                                }

                            is HistoryItemUi.Single -> onPlaySingle(entry)
                        }
                    },
                    onDelete = { deleteTarget = entry }
                )
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = {
                Text(
                    when (target) {
                        is HistoryItemUi.Group -> stringResource(R.string.hint_history_delete_group)
                        is HistoryItemUi.Single -> stringResource(R.string.hint_history_delete_single)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(target)
                }) { Text(stringResource(R.string.action_confirmed)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text(stringResource(R.string.menu_history_clear_all)) },
            text = { Text(stringResource(R.string.hint_history_clear_all)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearAll = false
                    onClearAll()
                }) { Text(stringResource(R.string.action_confirmed)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryItemUi,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val title = entry.title.decodeHtmlEntities()
    val subtitle = when (entry) {
        is HistoryItemUi.Group ->
            "${entry.epCount} 集 · ${formatRelativeTime(entry.lastPlayedAt)}"

        is HistoryItemUi.Single -> formatRelativeTime(entry.lastPlayedAt)
    }
    val progressRatio = if (entry.duration > 0) {
        (entry.progress.toFloat() / entry.duration).coerceIn(0f, 1f)
    } else 0f
    val remaining = if (entry.duration > entry.progress) {
        formatDuration(entry.duration - entry.progress)
    } else {
        stringResource(R.string.history_finished)
    }
    val openLabel = stringResource(R.string.cd_open)

    Row(
        Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$title，$subtitle" }
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HistoryThumb(entry, Modifier.width(124.dp).height(70.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                subtitle,
                style = DyLikeType.micro,
                color = c.textSecondary,
                modifier = Modifier.padding(top = 3.dp)
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.weight(1f).height(2.dp).clip(CircleShape).background(c.track)
                ) {
                    Box(
                        Modifier.fillMaxWidth(progressRatio).fillMaxHeight().background(c.accent)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(remaining, style = DyLikeType.micro, color = c.textTertiary)
            }
        }
        // 行内只有「删除」一个动作：直接开确认框，不为一个选项挂一个 DropdownMenu
        AppIconButton(
            Icons.Default.MoreVert,
            stringResource(R.string.action_more),
            onDelete
        )
    }
}

@Composable
private fun HistoryThumb(
    entry: HistoryItemUi,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(9.dp)
    val path = when (entry) {
        is HistoryItemUi.Group -> entry.coverPath
        is HistoryItemUi.Single -> entry.thumbPath
    }
    val headers = (entry as? HistoryItemUi.Group)?.coverHeaders ?: emptyMap()

    if (path == null) {
        Box(modifier.clip(shape).background(c.surfaceRaised))
    } else {
        ThumbImage(
            path = path,
            modifier = modifier.clip(shape),
            headers = headers,
            placeholder = { CoverPlaceholder() }
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val min = diff / 60000
    return when {
        min < 1 -> "刚刚"
        min < 60 -> "${min} 分钟前"
        min < 1440 -> "${min / 60} 小时前"
        min < 43200 -> "${min / 1440} 天前"
        else -> "${min / 43200} 周前"
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 720)
@Composable
private fun HistoryContentPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        HistoryScreen(
            state = HistoryUiState(
                phase = HistoryPhase.Content,
                items = listOf(
                    HistoryItemUi.Group(
                        key = "0|lib:preview",
                        sourceType = PlaySource.LIBRARY,
                        sourceKey = "lib:preview",
                        mediaId = "preview",
                        title = "示例媒体库",
                        epCount = 12,
                        lastPlayedAt = System.currentTimeMillis() - 3_600_000,
                        progress = 600_000,
                        duration = 1_440_000,
                        coverPath = null,
                        coverHeaders = emptyMap(),
                        exists = true
                    ),
                    HistoryItemUi.Single(
                        key = "2|standalone|v1",
                        videoId = "v1",
                        sourceKey = "standalone",
                        title = "本地单文件.mp4",
                        thumbPath = null,
                        lastPlayedAt = System.currentTimeMillis() - 86_400_000,
                        progress = 0,
                        duration = 0
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 480)
@Composable
private fun HistoryEmptyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        HistoryScreen(state = HistoryUiState(phase = HistoryPhase.Empty))
    }
}
