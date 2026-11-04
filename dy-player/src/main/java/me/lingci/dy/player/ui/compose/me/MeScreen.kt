package me.lingci.dy.player.ui.compose.me

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.ui.compose.components.GroupLabel
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.ScreenTitle
import me.lingci.dy.player.ui.compose.components.SettingRow
import me.lingci.dy.player.ui.compose.settings.KernelSettingsState
import me.lingci.dy.player.ui.compose.settings.PlayerSettingsState
import me.lingci.dy.player.ui.compose.settings.SettingsNoteLine
import me.lingci.dy.player.ui.compose.settings.SettingsState
import me.lingci.dy.player.ui.compose.settings.SettingsViewModel
import me.lingci.dy.player.ui.compose.settings.settingsViewModel
import me.lingci.dy.player.ui.compose.settings.themeSummary
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode

/** 未查询完成时的占位，避免用 0 冒充「没有数据」 */
private const val PENDING = "—"

/**
 * 「我的」= 个人概览 + **设置索引**。
 *
 * 结构只有两层：本页列设置入口，点一行进分类页。原先的三层
 * （我的 → 全部设置 → 七个二级页）被砍掉两层中间的索引页。
 *
 * 入口分两类：五个**分类页**（播放 / 外观 / 播放内核 / 弹幕 / 实验室）与本页就地执行的**动作**
 * （备份还原 / 数据迁移 / 反馈与开源）。备份与反馈原本在「数据与关于」分类里，
 * 但那两类各只有一个动作，再套一层详情页只是多一次点击，所以直接提到本页；
 * 桌面图标属于外观，已并进外观页；版本号作为页脚说明，不再占一个入口。
 * 播放历史 / 播放列表不在这里，媒体库首页已有入口。
 */
@Composable
fun MeScreen(
    themeMode: ThemeMode,
    brand: BrandHue,
    dynamicColor: Boolean,
    version: String,
    onTheme: () -> Unit,
    onPlayback: () -> Unit,
    onKernel: () -> Unit,
    onDanmaku: () -> Unit,
    onLab: () -> Unit,
    onBackup: () -> Unit,
    onFeedback: () -> Unit,
    onMigration: () -> Unit,
    vm: MeViewModel = viewModel(),
    settings: SettingsViewModel = settingsViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settingsState by settings.state.collectAsStateWithLifecycle()
    MeContent(
        state = state,
        settings = settingsState,
        themeMode = themeMode,
        brand = brand,
        dynamicColor = dynamicColor,
        version = version,
        onTheme = onTheme,
        onPlayback = onPlayback,
        onKernel = onKernel,
        onDanmaku = onDanmaku,
        onLab = onLab,
        onBackup = onBackup,
        onFeedback = onFeedback,
        onMigration = onMigration
    )
}

/** 无状态内容层：只吃状态与回调，可直接 Preview / 测试 */
@Composable
fun MeContent(
    state: MeUiState,
    settings: SettingsState,
    themeMode: ThemeMode,
    brand: BrandHue,
    dynamicColor: Boolean,
    version: String,
    onTheme: () -> Unit,
    onPlayback: () -> Unit,
    onKernel: () -> Unit,
    onDanmaku: () -> Unit,
    onLab: () -> Unit,
    onBackup: () -> Unit,
    onFeedback: () -> Unit,
    onMigration: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTitle("我的", kicker = "PROFILE & SETTINGS") }
        item {
            ProfileCard(
                state = state,
                themeMode = themeMode,
                brand = brand,
                dynamicColor = dynamicColor,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        item { GroupLabel(stringResource(R.string.settings_group_all)) }
        item {
            SettingRow(
                stringResource(R.string.settings_cat_playback),
                stringResource(R.string.settings_cat_playback_desc),
                stringResource(R.string.settings_enter),
                onPlayback
            )
        }
        item {
            SettingRow(
                stringResource(R.string.settings_group_appearance),
                // 副标题直接写当前主题，省得为了看一眼配色点进页面再退出来
                themeSummary(themeMode, brand, dynamicColor),
                stringResource(R.string.settings_enter),
                onTheme
            )
        }
        item {
            SettingRow(
                stringResource(R.string.settings_kernel_title),
                kernelSummary(settings.kernel),
                stringResource(R.string.settings_enter),
                onKernel
            )
        }
        item {
            SettingRow(
                stringResource(R.string.settings_danmaku_title),
                stringResource(R.string.settings_cat_danmaku_desc),
                stringResource(R.string.settings_enter),
                onDanmaku
            )
        }
        item {
            // 实验室独立成页：它原本挂在「播放内核」页脚，但里面是竖屏/自动播放/调试这类
            // 与内核无关的开关，混在一起会让人以为改内核才需要进来
            SettingRow(
                stringResource(R.string.settings_lab_title),
                stringResource(R.string.hint_lab_desc),
                stringResource(R.string.settings_enter),
                onLab
            )
        }

        // 下面三行是动作，不是分类：右侧是「打开/查看」，点了就地执行，不再下钻
        item {
            SettingRow(
                stringResource(R.string.hint_data_backup),
                stringResource(R.string.settings_backup_desc),
                stringResource(R.string.settings_open),
                onBackup
            )
        }
        // 迁移入口只在「还没迁移」时出现，迁完就不再是设置项
        if (settings.lab.migrationPending) {
            item {
                SettingRow(
                    stringResource(R.string.migration_entry),
                    stringResource(R.string.migration_entry_desc),
                    stringResource(R.string.settings_open),
                    onMigration
                )
            }
        }
        item {
            SettingRow(
                stringResource(R.string.settings_about_feedback),
                stringResource(R.string.settings_about_feedback_desc),
                stringResource(R.string.settings_view),
                onFeedback
            )
        }
        item { SettingsNoteLine("DyLike $version") }

        item { Spacer(Modifier.height(96.dp)) }
    }
}

/** 内核摘要：让用户不进页面就知道现在跑的是哪个内核 */
private fun kernelSummary(kernel: KernelSettingsState): String {
    fun label(core: DyPlayerCore) = if (core == DyPlayerCore.MPV) "MPV" else "Exo"
    return "长视频 ${label(kernel.longVideoCore)} · 短视频 ${label(kernel.shortVideoCore)}"
}

@Composable
private fun ProfileCard(
    state: MeUiState,
    themeMode: ThemeMode,
    brand: BrandHue,
    dynamicColor: Boolean,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(c.ambient.copy(alpha = .30f), c.surface, c.surface)))
            .border(1.dp, c.hairline, shape)
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(c.accent.copy(alpha = .14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Theaters, null, tint = c.accent, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Kicker("PRIVATE CINEMA")
                    Spacer(Modifier.height(3.dp))
                    Text("DyLike", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
                    Text("你的私人视频空间", style = DyLikeType.meta, color = c.textSecondary)
                }
                Text("V2", style = DyLikeType.timecode, color = c.accent)
            }
            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = c.hairline)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatBlock(state.mediaCount?.toString() ?: PENDING, "影片")
                StatBlock(state.sourceCount?.toString() ?: PENDING, "来源")
                StatBlock(state.monthPlayCount?.toString() ?: PENDING, "本月观看")
            }
            Spacer(Modifier.height(12.dp))
            Text(
                themeSummary(themeMode, brand, dynamicColor),
                style = DyLikeType.micro,
                color = c.textTertiary
            )
        }
    }
}

@Composable
private fun StatBlock(value: String, label: String) {
    val c = LocalDyLikeColors.current
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        Text(label, style = DyLikeType.micro, color = c.textTertiary)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 1000)
@Composable
private fun MeContentPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MeContent(
            state = MeUiState(mediaCount = 12, sourceCount = 3, monthPlayCount = 27),
            settings = SettingsState(
                player = PlayerSettingsState(longVideoMode = true, autoNext = true, loopList = true)
            ),
            themeMode = ThemeMode.Dark,
            brand = BrandHue.Purple,
            dynamicColor = false,
            version = "2.0.0",
            onTheme = {},
            onPlayback = {},
            onKernel = {},
            onDanmaku = {},
            onLab = {},
            onBackup = {},
            onFeedback = {},
            onMigration = {}
        )
    }
}
