package me.lingci.dy.player.ui.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.ThemeMode

@Composable
fun LongVideoControlSettingsDetail(
    onBack: () -> Unit,
    vm: SettingsViewModel = settingsViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LongVideoControlSettingsContent(
        state = state.player,
        onBack = onBack,
        onChange = vm::applyPlayer
    )
}

@Composable
fun LongVideoControlSettingsContent(
    state: PlaybackSettingsState,
    onBack: () -> Unit,
    onChange: (PlaybackSettingsState) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_long_video_controls_title),
        kicker = stringResource(R.string.settings_long_video_controls_kicker),
        onBack = onBack,
        modifier = modifier
    ) {
        longVideoControlSettings(state = state, onChange = onChange)
    }
}

@Preview(name = "全部显示", showBackground = true, heightDp = 920)
@Composable
private fun LongVideoControlSettingsPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LongVideoControlSettingsContent(
            state = PlaybackSettingsState(),
            onBack = {},
            onChange = {}
        )
    }
}

@Preview(name = "精简控制栏", showBackground = true, heightDp = 920)
@Composable
private fun LongVideoControlSettingsHiddenPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LongVideoControlSettingsContent(
            state = PlaybackSettingsState(
                longVideoShowScreenshot = false,
                longVideoShowMediaInfo = false,
                longVideoShowDanmakuTools = false,
                longVideoShowTimeSync = false,
                longVideoShowSmoothCurve = false,
                longVideoShowStatusInfo = false,
                longVideoShowManualPip = false
            ),
            onBack = {},
            onChange = {}
        )
    }
}
