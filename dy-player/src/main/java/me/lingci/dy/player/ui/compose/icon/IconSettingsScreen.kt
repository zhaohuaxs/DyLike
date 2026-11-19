package me.lingci.dy.player.ui.compose.icon

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.AppIconImage
import me.lingci.dy.player.ui.compose.components.GroupLabel
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.StatePill
import me.lingci.dy.player.ui.compose.settings.SettingsNoteLine
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.util.ToastUtil

/**
 * 路由入口：切换成功后提示并退回上一页。
 *
 * 旧版这里会 `startActivity(MainActivity)` + finish，等于把整个应用外壳重启一遍；
 * 别名切换本身不影响运行中的进程，没必要。
 */
@Composable
fun IconSettingsRoute(
    onBack: () -> Unit,
    vm: IconSettingsViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val msgChanged = stringResource(R.string.icon_changed)
    val msgFailed = stringResource(R.string.icon_failed)

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    IconSettingsEvent.Switched -> {
                        ToastUtil.showToast(context, msgChanged)
                        onBack()
                    }

                    IconSettingsEvent.Failed -> ToastUtil.showToast(context, msgFailed)
                }
            }
        }
    }

    IconSettingsScreen(
        state = state,
        onBack = onBack,
        onSelect = vm::select
    )
}

@Composable
fun IconSettingsScreen(
    state: IconSettingsUiState,
    onBack: () -> Unit = {},
    onSelect: (LauncherIcon) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            Column(Modifier.padding(start = 6.dp)) {
                Kicker(stringResource(R.string.icon_kicker))
                Text(
                    stringResource(R.string.icon_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.icon_current, stringResource(labelRes(state.selected))),
                style = DyLikeType.meta,
                color = c.textSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
            GroupLabel(stringResource(R.string.settings_group_icon))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                LauncherIcon.entries.forEach { icon ->
                    IconOption(
                        icon = icon,
                        selected = icon == state.selected,
                        enabled = !state.busy,
                        onPick = { onSelect(icon) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            SettingsNoteLine(stringResource(R.string.icon_note))
        }
    }
}

@Composable
private fun IconOption(
    icon: LauncherIcon,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, if (selected) c.accent.copy(alpha = .55f) else c.hairline, shape)
            .padding(vertical = 20.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIconImage(
            resId = iconRes(icon),
            modifier = Modifier.size(72.dp)
        )
        Text(
            stringResource(labelRes(icon)),
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) c.accent else c.textPrimary
        )
        StatePill(
            text = stringResource(if (selected) R.string.icon_picked else R.string.icon_pick),
            selected = selected,
            onClick = { if (enabled && !selected) onPick() }
        )
    }
}

private fun labelRes(icon: LauncherIcon): Int = when (icon) {
    LauncherIcon.Default -> R.string.icon_option_default
    LauncherIcon.Line -> R.string.icon_option_line
}

/**
 * 线条图标只有 anydpi-v26 一套（自适应图标），minSdk 24 的设备取不到该资源，
 * 退回默认图标而不是让页面崩在 Resources.NotFoundException 上。
 */
private fun iconRes(icon: LauncherIcon): Int = when (icon) {
    LauncherIcon.Default -> R.mipmap.ic_launcher
    LauncherIcon.Line ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) R.mipmap.ic_launcher_line
        else R.mipmap.ic_launcher
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 520)
@Composable
private fun IconSettingsDefaultPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        IconSettingsScreen(state = IconSettingsUiState(selected = LauncherIcon.Default))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 520)
@Composable
private fun IconSettingsLinePreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        IconSettingsScreen(state = IconSettingsUiState(selected = LauncherIcon.Line))
    }
}
