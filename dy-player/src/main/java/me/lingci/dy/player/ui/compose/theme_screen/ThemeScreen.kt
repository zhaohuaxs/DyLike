package me.lingci.dy.player.ui.compose.theme_screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.SettingRow
import me.lingci.dy.player.ui.compose.components.ToggleRow
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.LocalPlayerColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.theme.dyLikeColors

@Composable
fun ThemeScreen(
    mode: ThemeMode,
    brand: BrandHue,
    dynamic: Boolean,
    navLabels: Boolean,
    onBack: () -> Unit,
    onMode: (ThemeMode) -> Unit,
    onBrand: (BrandHue) -> Unit,
    onDynamic: (Boolean) -> Unit,
    onNavLabels: (Boolean) -> Unit,
    onIcon: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Column(Modifier.padding(start = 6.dp)) {
                Kicker("APPEARANCE")
                Text("外观与主题", style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            }
        }
        Kicker(
            "色彩模式",
            Modifier.padding(start = 20.dp, top = 22.dp, bottom = 10.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ThemeMode.entries.forEach { m ->
                ModeCard(
                    mode = m,
                    selected = mode == m,
                    brand = brand,
                    modifier = Modifier.weight(1f),
                    onClick = { onMode(m) }
                )
            }
        }
        Kicker(
            "放映光色",
            Modifier.padding(start = 20.dp, top = 28.dp, bottom = 6.dp)
        )
        Text(
            "只改变光色与环境氛围，不改变放映厅暗面。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textSecondary,
            modifier = Modifier.padding(horizontal = 20.dp)
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BrandHue.entries.forEach { hue ->
                BrandCard(
                    hue = hue,
                    selected = brand == hue,
                    modifier = Modifier.weight(1f),
                    onClick = { onBrand(hue) }
                )
            }
        }
        ToggleRow(
            "动态取色",
            "仅提取系统主色作为放映光，保留影院暗面",
            dynamic,
            onDynamic
        )
        ToggleRow(
            "导航栏显示标签",
            if (navLabels) "图标 + 标签，选中项展开" else "图标为主，选中项展开文字",
            navLabels,
            onNavLabels
        )
        // 桌面图标属于「外观」而不是「关于」：用户换图标是想换桌面上的样子，不是查版本
        Kicker(
            stringResource(R.string.settings_group_icon),
            Modifier.padding(start = 20.dp, top = 26.dp, bottom = 4.dp)
        )
        SettingRow(
            stringResource(R.string.settings_about_icon),
            stringResource(R.string.settings_about_icon_desc),
            stringResource(R.string.settings_open),
            onIcon
        )
        Kicker(
            "LIVE PREVIEW",
            Modifier.padding(start = 20.dp, top = 26.dp, bottom = 10.dp)
        )
        ThemePreview(Modifier.padding(horizontal = 20.dp))
        Text(
            "动态取色开启后只借用系统主色，暗面与文本仍由应用控制；默认关闭以保持品牌辨识度。强调色只出现在播放、进度与激活状态。",
            style = MaterialTheme.typography.bodyMedium,
            color = c.textTertiary,
            modifier = Modifier.padding(20.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeCard(
    mode: ThemeMode,
    selected: Boolean,
    brand: BrandHue,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val current = LocalDyLikeColors.current
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val preview = dyLikeColors(dark, brand)
    val shape = RoundedCornerShape(13.dp)
    Column(modifier.clip(shape).clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(9.dp))
                .background(preview.background)
                .border(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) current.accent else current.hairline,
                    shape = RoundedCornerShape(9.dp)
                )
        ) {
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 9.dp)
                    .width(34.dp).height(2.dp).clip(CircleShape).background(preview.accent)
            )
            Box(
                Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 15.dp)
                    .width(22.dp).height(2.dp).clip(CircleShape).background(preview.textSecondary.copy(alpha = .5f))
            )
            Box(
                Modifier.align(Alignment.Center).size(14.dp).clip(CircleShape).background(preview.accent)
            )
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .fillMaxWidth().height(2.dp).clip(CircleShape).background(preview.track)
            ) {
                Box(
                    Modifier.fillMaxWidth(.62f).fillMaxHeight().clip(CircleShape).background(preview.accent)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (mode) {
                    ThemeMode.System -> "跟随系统"
                    ThemeMode.Light -> "浅色"
                    ThemeMode.Dark -> "深色"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) current.textPrimary else current.textSecondary,
                maxLines = 1
            )
            if (selected) {
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(3.dp).clip(CircleShape).background(current.accent))
            }
        }
    }
}

@Composable
private fun BrandCard(
    hue: BrandHue,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape)
            .background(c.surface)
            .border(1.dp, if (selected) c.accent else c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(hue.seed, hue.ambient))),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            hue.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) c.textPrimary else c.textSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun ThemePreview(modifier: Modifier = Modifier) {
    val c = LocalDyLikeColors.current
    val player = LocalPlayerColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.hairline, shape)
    ) {
        Box(
            Modifier.fillMaxWidth().height(170.dp).background(
                Brush.linearGradient(
                    listOf(c.ambient.copy(alpha = .55f), c.background, c.surfaceOverlay)
                )
            )
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .58f)))
                )
            )
            Box(
                Modifier.align(Alignment.Center).size(44.dp).clip(CircleShape).background(player.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = player.onAccent, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp)) {
                Kicker("NOW PLAYING", color = player.accent)
                Spacer(Modifier.height(4.dp))
                Text("星海来信", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "本地 · HEVC · 1080p · 5.1",
                    color = Color.White.copy(alpha = .62f),
                    style = DyLikeType.micro
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("12:30", color = Color.White.copy(alpha = .8f), style = DyLikeType.timecode)
                    Box(
                        Modifier.weight(1f).padding(horizontal = 10.dp).height(3.dp)
                            .clip(CircleShape).background(Color.White.copy(alpha = .18f))
                    ) {
                        Box(
                            Modifier.fillMaxWidth(.68f).fillMaxHeight().background(player.accent)
                        )
                    }
                    Text("18:24", color = Color.White.copy(alpha = .6f), style = DyLikeType.timecode)
                }
            }
        }
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("播放器色彩预览", style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
                Text(
                    "强调色只用于进度、播放与激活状态",
                    style = DyLikeType.meta,
                    color = c.textSecondary
                )
            }
            Kicker("PALETTE")
        }
    }
}