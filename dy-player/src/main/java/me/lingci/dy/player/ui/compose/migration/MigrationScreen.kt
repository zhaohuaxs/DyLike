package me.lingci.dy.player.ui.compose.migration

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.GhostButton
import me.lingci.dy.player.ui.compose.components.GroupLabel
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.PrimaryButton
import me.lingci.dy.player.ui.compose.settings.SettingsNoteLine
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode

/**
 * 路由入口：订阅状态，完成后交给导航层收尾（退回上一页，库内数据由 Room Flow 自动刷新）。
 *
 * 迁移期间屏蔽返回：执行器是单事务，中途退出只会得到「回滚 + 观感上什么都没发生」，
 * 不如直接挡住，避免用户以为中断造成了数据损坏。
 */
@Composable
fun MigrationRoute(
    onBack: () -> Unit,
    onDone: () -> Unit = onBack,
    vm: MigrationViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    BackHandler(enabled = state.running) { /* 迁移中不允许返回 */ }

    MigrationScreen(
        state = state,
        onBack = onBack,
        onStart = vm::start,
        onRetry = vm::retry,
        onDone = onDone
    )
}

@Composable
fun MigrationScreen(
    state: MigrationUiState,
    onBack: () -> Unit = {},
    onStart: () -> Unit = {},
    onRetry: () -> Unit = {},
    onDone: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 迁移中不提供退出入口；用等宽占位保持标题不跳动
            if (state.running) {
                Spacer(Modifier.size(42.dp))
            } else {
                AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            }
            Column(Modifier.padding(start = 6.dp)) {
                Kicker(stringResource(R.string.migration_kicker))
                Text(
                    stringResource(R.string.migration_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (state.running) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = c.accent
            )
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            val showResult = state.phase == MigrationPhase.Done
            SettingsNoteLine(
                stringResource(
                    if (showResult) R.string.migration_done_desc else R.string.migration_desc
                )
            )

            GroupLabel(
                stringResource(
                    if (showResult) R.string.migration_section_result else R.string.migration_will_migrate
                )
            )
            if (state.phase == MigrationPhase.Loading) {
                LoadingNote()
            } else {
                CountsCard(state.shownCounts)
            }

            if (state.phase == MigrationPhase.Prepared && state.preview.isEmpty) {
                SettingsNoteLine(stringResource(R.string.migration_empty_hint))
            }

            when (state.phase) {
                MigrationPhase.Done -> {
                    val outcome = state.outcome
                    Text(
                        stringResource(R.string.migration_success),
                        style = MaterialTheme.typography.titleSmall,
                        color = c.success,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    if (outcome != null) OutcomeNotes(outcome)
                }

                MigrationPhase.Failed -> {
                    val error = state.error
                    val detail = error?.detail.orEmpty()
                    val text = if (error?.duringPreparation == true) {
                        stringResource(R.string.migration_prepare_failed, detail)
                    } else {
                        stringResource(R.string.migration_failed, detail)
                    }
                    Text(
                        text,
                        style = DyLikeType.meta,
                        color = c.warning,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                else -> Unit
            }

            SettingsNoteLine(stringResource(R.string.migration_progress_restore_hint))
            SettingsNoteLine(stringResource(R.string.migration_notes))
            Spacer(Modifier.height(24.dp))
        }

        BottomActions(
            state = state,
            onBack = onBack,
            onStart = onStart,
            onRetry = onRetry,
            onDone = onDone
        )
    }
}

/** 七项统计：label 左对齐、数值右对齐，行距固定，结果态与预统计态共用 */
@Composable
private fun CountsCard(counts: MigrationCounts, modifier: Modifier = Modifier) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(18.dp)
    val rows = listOf(
        stringResource(R.string.migration_stat_sources) to counts.sources.toString(),
        stringResource(R.string.migration_stat_media) to counts.media.toString(),
        stringResource(R.string.migration_stat_videos) to counts.videos.toString(),
        stringResource(R.string.migration_stat_history) to counts.history.toString(),
        stringResource(R.string.migration_stat_favorites) to counts.favorites.toString(),
        stringResource(R.string.migration_stat_playlists) to
                stringResource(R.string.migration_stat_playlists_value, counts.playlists, counts.playlistItems),
        stringResource(R.string.migration_stat_shuffles) to counts.shuffles.toString()
    )
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        rows.forEach { (label, value) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "○ $label",
                    style = DyLikeType.meta,
                    color = c.textSecondary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(12.dp))
                Text(value, style = DyLikeType.timecode, color = c.textPrimary)
            }
        }
    }
}

/** 完成后的提示：跳过项、合并保留项、孤儿归并，逐条只在命中时出现 */
@Composable
private fun OutcomeNotes(outcome: MigrationOutcome) {
    Column(Modifier.fillMaxWidth()) {
        if (outcome.skippedShuffles > 0) {
            SettingsNoteLine(stringResource(R.string.migration_skip_shuffle_note, outcome.skippedShuffles))
        }
        if (outcome.skippedExisting > 0) {
            SettingsNoteLine(stringResource(R.string.migration_merge_note, outcome.skippedExisting))
        }
        if (outcome.hasVirtualMedia) {
            SettingsNoteLine(stringResource(R.string.migration_virtual_note))
        }
    }
}

@Composable
private fun LoadingNote() {
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            stringResource(R.string.migration_loading),
            style = DyLikeType.meta,
            color = LocalDyLikeColors.current.textTertiary
        )
    }
}

@Composable
private fun BottomActions(
    state: MigrationUiState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onRetry: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state.phase) {
            MigrationPhase.Done -> {
                PrimaryButton(
                    text = stringResource(R.string.migration_done),
                    modifier = Modifier.weight(1f),
                    onClick = onDone
                )
            }

            MigrationPhase.Running -> {
                PrimaryButton(
                    text = stringResource(R.string.migration_running),
                    enabled = false,
                    modifier = Modifier.weight(1f),
                    onClick = {}
                )
            }

            // 待迁移 / 失败共用一套布局：左「稍后再说」右主按钮，与旧页的操作区一致
            else -> {
                val failed = state.phase == MigrationPhase.Failed
                GhostButton(
                    text = stringResource(R.string.migration_later),
                    modifier = Modifier.weight(1f),
                    onClick = onBack
                )
                Spacer(Modifier.width(10.dp))
                PrimaryButton(
                    text = stringResource(
                        if (failed) R.string.migration_retry else R.string.migration_start
                    ),
                    // 预统计还没回来时不能点，否则会拿空预览去跑迁移
                    enabled = failed || state.phase == MigrationPhase.Prepared,
                    modifier = Modifier.weight(2f),
                    onClick = if (failed) onRetry else onStart
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 640)
@Composable
private fun MigrationPreparedPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MigrationScreen(
            state = MigrationUiState(
                phase = MigrationPhase.Prepared,
                preview = MigrationCounts(
                    sources = 5, media = 37, videos = 812, history = 46,
                    favorites = 12, playlists = 3, playlistItems = 27, shuffles = 4
                )
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 640)
@Composable
private fun MigrationRunningPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MigrationScreen(
            state = MigrationUiState(
                phase = MigrationPhase.Running,
                preview = MigrationCounts(sources = 5, media = 37, videos = 812)
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 640)
@Composable
private fun MigrationDonePreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MigrationScreen(
            state = MigrationUiState(
                phase = MigrationPhase.Done,
                outcome = MigrationOutcome(
                    counts = MigrationCounts(
                        sources = 5, media = 37, videos = 812, history = 46,
                        favorites = 12, playlists = 3, playlistItems = 27, shuffles = 2
                    ),
                    skippedShuffles = 2,
                    skippedExisting = 6,
                    hasVirtualMedia = true
                )
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 640)
@Composable
private fun MigrationFailedPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MigrationScreen(
            state = MigrationUiState(
                phase = MigrationPhase.Failed,
                error = MigrationError(duringPreparation = false, detail = "FOREIGN KEY constraint failed")
            )
        )
    }
}
