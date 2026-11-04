package me.lingci.dy.player.ui.compose.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.GroupLabel
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.util.FileOperator

/**
 * 分类详情页。
 *
 * 结构约定：「我的」页 = 设置索引（个人卡片 + 分类入口 + 备份/反馈），这里是每个分类的**唯一**下钻层，
 * 不再有「我的 → 全部设置 → 分类 → 页」这种三层结构。
 * 分类只保留播放 / 内核 / 弹幕三类；外观独立成页（[me.lingci.dy.player.ui.compose.theme_screen.ThemeScreen]），
 * 原来的「数据与关于」被拆掉：备份与反馈提到「我的」，桌面图标并入外观。
 */

@Composable
private fun SettingsDetailScaffold(
    title: String,
    kicker: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit
) {
    val c = LocalDyLikeColors.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, "返回", onBack)
            Column(Modifier.padding(start = 6.dp)) {
                Kicker(kicker)
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            content()
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ---------- 播放 ----------

@Composable
fun PlaybackSettingsDetail(
    onBack: () -> Unit,
    vm: SettingsViewModel = settingsViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 后台播放要发媒体通知，开开关时顺手把 API 33+ 的通知权限要了
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    PlaybackSettingsContent(
        state = state,
        onBack = onBack,
        onPlayerChange = vm::applyPlayer,
        onBackgroundPlayEnabled = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )
}

@Composable
fun PlaybackSettingsContent(
    state: SettingsState,
    onBack: () -> Unit,
    onPlayerChange: (PlayerSettingsState) -> Unit,
    onBackgroundPlayEnabled: () -> Unit
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_cat_playback),
        kicker = "PLAYBACK",
        onBack = onBack
    ) {
        // 页标题已经写了「播放」，这里不再重复一个同名分组标签
        playbackSettings(
            state = state.player,
            onChange = onPlayerChange,
            onBackgroundPlayEnabled = onBackgroundPlayEnabled
        )
        item { GroupLabel(stringResource(R.string.settings_library_title)) }
        librarySettings(state = state.player, onChange = onPlayerChange)
    }
}

// ---------- 播放内核 ----------

@Composable
fun KernelSettingsDetail(
    onBack: () -> Unit,
    vm: SettingsViewModel = settingsViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var shortTarget by rememberSaveable { mutableStateOf(false) }

    KernelSettingsContent(
        state = state,
        shortTarget = shortTarget,
        onShortTarget = { shortTarget = it },
        onBack = onBack,
        onKernelChange = vm::applyKernel
    )
}

@Composable
fun KernelSettingsContent(
    state: SettingsState,
    shortTarget: Boolean,
    onShortTarget: (Boolean) -> Unit,
    onBack: () -> Unit,
    onKernelChange: (KernelSettingsState) -> Unit
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_kernel_title),
        kicker = "PLAYER CORE",
        onBack = onBack
    ) {
        // 只放内核相关的项；实验室是独立分类，不跟内核混在一页
        kernelSettings(
            state = state.kernel,
            onChange = onKernelChange,
            shortTarget = shortTarget,
            onShortTarget = onShortTarget
        )
    }
}

// ---------- 实验室 ----------

@Composable
fun LabSettingsDetail(
    onBack: () -> Unit,
    vm: SettingsViewModel = settingsViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LabSettingsContent(
        state = state,
        onBack = onBack,
        onLabChange = vm::applyLab
    )
}

@Composable
fun LabSettingsContent(
    state: SettingsState,
    onBack: () -> Unit,
    onLabChange: (LabSettingsState) -> Unit
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_lab_title),
        kicker = "LAB",
        onBack = onBack
    ) {
        labSettings(state = state.lab, onChange = onLabChange)
        item { SettingsNoteLine(stringResource(R.string.settings_lab_note)) }
    }
}

// ---------- 弹幕 ----------

@Composable
fun DanmakuSettingsDetail(
    onBack: () -> Unit,
    vm: SettingsViewModel = settingsViewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showFilterEditor by rememberSaveable { mutableStateOf(false) }
    var showMergeEditor by rememberSaveable { mutableStateOf(false) }

    // 复用旧页面的文件选择器，只列字体扩展名；复制文件与写偏好交给 ViewModel
    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val path = result.data?.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)?.firstOrNull()
        if (!path.isNullOrBlank()) vm.applyDanmakuFont(path)
    }

    DanmakuSettingsContent(
        state = state,
        onBack = onBack,
        onDanmakuChange = vm::applyDanmaku,
        onPickFont = {
            context.findActivity()?.let {
                FileSelectorActivity.startSingle(it, FileOperator.FONT_EXTENSIONS, fontPicker)
            }
        },
        onEditFilter = { showFilterEditor = true },
        onEditMerge = { showMergeEditor = true }
    )

    if (showFilterEditor) {
        FilterWordsDialog(
            initial = state.danmaku.filterKeywords,
            onDismiss = { showFilterEditor = false },
            onConfirm = { words ->
                vm.applyDanmaku(state.danmaku.copy(filterKeywords = words))
                showFilterEditor = false
            }
        )
    }
    if (showMergeEditor) {
        MergeThresholdDialog(
            show = state.danmaku.mergeShow,
            top = state.danmaku.mergeTop,
            onDismiss = { showMergeEditor = false },
            onConfirm = { show, top ->
                vm.applyDanmaku(state.danmaku.copy(mergeShow = show, mergeTop = top))
                showMergeEditor = false
            }
        )
    }
}

@Composable
fun DanmakuSettingsContent(
    state: SettingsState,
    onBack: () -> Unit,
    onDanmakuChange: (DanmakuSettingsState) -> Unit,
    onPickFont: () -> Unit,
    onEditFilter: () -> Unit,
    onEditMerge: () -> Unit
) {
    SettingsDetailScaffold(
        title = stringResource(R.string.settings_danmaku_title),
        kicker = "DANMAKU",
        onBack = onBack
    ) {
        danmakuSettings(
            state = state.danmaku,
            onChange = onDanmakuChange,
            onPickFont = onPickFont,
            onEditFilter = onEditFilter,
            onEditMerge = onEditMerge
        )
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 1600)
@Composable
private fun PlaybackSettingsPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        PlaybackSettingsContent(
            state = SettingsState(
                player = PlayerSettingsState(longVideoMode = true, autoNext = true, loopList = true)
            ),
            onBack = {},
            onPlayerChange = {},
            onBackgroundPlayEnabled = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 1200)
@Composable
private fun KernelSettingsPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        KernelSettingsContent(
            state = SettingsState(),
            shortTarget = false,
            onShortTarget = {},
            onBack = {},
            onKernelChange = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 800)
@Composable
private fun LabSettingsPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LabSettingsContent(
            state = SettingsState(),
            onBack = {},
            onLabChange = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 1400)
@Composable
private fun DanmakuSettingsPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        DanmakuSettingsContent(
            state = SettingsState(danmaku = DanmakuSettingsState(mergeMode = true)),
            onBack = {},
            onDanmakuChange = {},
            onPickFont = {},
            onEditFilter = {},
            onEditMerge = {}
        )
    }
}
