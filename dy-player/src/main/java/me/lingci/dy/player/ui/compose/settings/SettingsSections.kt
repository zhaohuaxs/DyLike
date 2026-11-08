package me.lingci.dy.player.ui.compose.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.lingci.dy.player.R
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.ui.compose.components.GroupLabel
import me.lingci.dy.player.ui.compose.components.Segment
import me.lingci.dy.player.ui.compose.components.SegmentedRow
import me.lingci.dy.player.ui.compose.components.SettingRow
import me.lingci.dy.player.ui.compose.components.SliderRow
import me.lingci.dy.player.ui.compose.components.ToggleRow
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import java.util.Locale

/**
 * 设置项分段：每一段都是 `LazyListScope` 扩展，拼进对应分类详情页的 LazyColumn。
 *
 * 分段拆开写，是为了顺序调整、单独 Preview，以及同一个段被两处复用时不必复制代码
 * （`playbackSettings` 就同时给「播放」页与 Preview 用）。
 *
 * 约定：段首的分组标签由调用页给；段内需要细分时才自己加。
 */

// ---------- 播放与进度 ----------

fun LazyListScope.playbackSettings(
    state: PlayerSettingsState,
    onChange: (PlayerSettingsState) -> Unit,
    /** 打开「后台播放」时请求通知权限（API 33+），否则用户开了也看不到媒体通知 */
    onBackgroundPlayEnabled: () -> Unit
) {
    item {
        ToggleRow(
            stringResource(R.string.hint_long_video_mode),
            stringResource(R.string.hint_long_short_video_desc),
            state.longVideoMode
        ) { onChange(state.copy(longVideoMode = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_auto_next),
            stringResource(R.string.hint_auto_next_desc),
            state.autoNext
        ) { onChange(state.copy(autoNext = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_loop_list),
            stringResource(R.string.hint_loop_list_desc),
            state.loopList
        ) { onChange(state.copy(loopList = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_long_video_pip),
            stringResource(R.string.hint_long_video_pip_desc),
            state.longVideoPip
        ) { onChange(state.copy(longVideoPip = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_long_video_bg_play),
            stringResource(R.string.hint_long_video_bg_play_desc),
            state.longVideoBackgroundPlay
        ) { on ->
            onChange(state.copy(longVideoBackgroundPlay = on))
            if (on) onBackgroundPlayEnabled()
        }
    }

    item { GroupLabel(stringResource(R.string.settings_section_audio)) }
    item {
        ToggleRow(
            stringResource(R.string.hint_audio_fade),
            stringResource(R.string.hint_audio_fade_desc),
            state.audioFadeEnabled
        ) { onChange(state.copy(audioFadeEnabled = it)) }
    }
    item {
        val fadeOptions = fadeSegments()
        SegmentedRow(
            title = stringResource(R.string.hint_audio_fade_in_duration),
            desc = stringResource(R.string.hint_audio_fade_in_duration_desc),
            options = fadeOptions,
            selected = state.audioFadeInDuration,
            enabled = state.audioFadeEnabled,
            // 0.5s / 1s / 1.5s / 2s 都是两个字符，一行四格放得下，排成两行反而更长
            perLine = 4
        ) { onChange(state.copy(audioFadeInDuration = it)) }
    }
    item {
        val fadeOptions = fadeSegments()
        SegmentedRow(
            title = stringResource(R.string.hint_audio_fade_out_duration),
            desc = stringResource(R.string.hint_audio_fade_out_duration_desc),
            options = fadeOptions,
            selected = state.audioFadeOutDuration,
            enabled = state.audioFadeEnabled,
            perLine = 4
        ) { onChange(state.copy(audioFadeOutDuration = it)) }
    }

    item { GroupLabel(stringResource(R.string.settings_section_short_video)) }
    item {
        ToggleRow(
            stringResource(R.string.hint_short_random),
            stringResource(R.string.hint_short_random_desc),
            state.shortRandom
        ) { onChange(state.copy(shortRandom = it)) }
    }
}

@Composable
private fun fadeSegments(): List<Segment<Int>> = listOf(
    Segment(500, stringResource(R.string.settings_playback_duration_500)),
    Segment(1000, stringResource(R.string.settings_playback_duration_1000)),
    Segment(1500, stringResource(R.string.settings_playback_duration_1500)),
    Segment(2000, stringResource(R.string.settings_playback_duration_2000))
)

// ---------- 播放内核 ----------

/**
 * 先选「给谁配」，再选内核，最后只显示这个组合自己的选项。
 *
 * 内核是枚举不是开关；把 Exo 的网络栈和 MPV 的 vo 摆在同一屏，用户分不清哪项对当前内核有效。
 */
fun LazyListScope.kernelSettings(
    state: KernelSettingsState,
    onChange: (KernelSettingsState) -> Unit,
    shortTarget: Boolean,
    onShortTarget: (Boolean) -> Unit
) {
    // 存量数据里可能还留着 AUTO（旧枚举），界面按 Exo 显示，避免「两个都没选中」
    val rawCore = if (shortTarget) state.shortVideoCore else state.longVideoCore
    val core = if (rawCore == DyPlayerCore.MPV) DyPlayerCore.MPV else DyPlayerCore.EXO
    val coreOptions = listOf(
        Segment(DyPlayerCore.EXO, "Exo"),
        Segment(DyPlayerCore.MPV, "MPV")
    )

    item {
        SegmentedRow(
            title = stringResource(R.string.settings_kernel_target),
            desc = stringResource(R.string.settings_kernel_target_desc),
            options = listOf(
                Segment(false, stringResource(R.string.settings_section_long_video)),
                Segment(true, stringResource(R.string.settings_section_short_video))
            ),
            selected = shortTarget
        ) { onShortTarget(it) }
    }
    item {
        SegmentedRow(
            title = if (shortTarget) {
                stringResource(R.string.settings_kernel_short_video)
            } else {
                stringResource(R.string.settings_kernel_long_video)
            },
            desc = if (shortTarget) {
                stringResource(R.string.settings_kernel_short_video_desc)
            } else {
                stringResource(R.string.settings_kernel_long_video_desc)
            },
            options = coreOptions,
            selected = core
        ) {
            onChange(if (shortTarget) state.copy(shortVideoCore = it) else state.copy(longVideoCore = it))
        }
    }

    if (core == DyPlayerCore.MPV) {
        item { GroupLabel(stringResource(R.string.settings_kernel_mpv)) }
        item {
            SegmentedRow(
                title = stringResource(R.string.settings_kernel_mpv_output),
                desc = stringResource(R.string.settings_kernel_mpv_output_desc),
                options = listOf(
                    Segment(MpvVideoOutput.Gpu, "gpu"),
                    Segment(MpvVideoOutput.GpuNext, "gpu-next")
                ),
                selected = state.mpvOutput
            ) { onChange(state.copy(mpvOutput = it)) }
        }
        item {
            SegmentedRow(
                title = stringResource(R.string.settings_kernel_mpv_hwdec),
                desc = stringResource(R.string.settings_kernel_mpv_hwdec_desc),
                options = listOf(
                    Segment(MpvHwdec.Auto, stringResource(R.string.settings_kernel_mpv_hwdec_auto)),
                    Segment(MpvHwdec.MediaCodec, "MediaCodec"),
                    Segment(MpvHwdec.MediaCodecCopy, "MediaCodec-Copy"),
                    Segment(MpvHwdec.Off, stringResource(R.string.settings_kernel_mpv_hwdec_off))
                ),
                selected = state.mpvHwdec
            ) { onChange(state.copy(mpvHwdec = it)) }
        }
        // 短视频走 MPV 时 registry 会强制用 MPV 的 Surface 渲染器，顺序读只影响长视频
        if (!shortTarget) {
            item {
                ToggleRow(
                    stringResource(R.string.hint_lab_mpv_sequential_read),
                    stringResource(R.string.hint_lab_mpv_sequential_read_desc),
                    state.mpvSequentialRead
                ) { onChange(state.copy(mpvSequentialRead = it)) }
            }
        }
        item {
            ToggleRow(
                stringResource(R.string.hint_lab_mpv_special_render),
                stringResource(R.string.hint_lab_mpv_special_render_desc),
                state.mpvSpecialRender
            ) { onChange(state.copy(mpvSpecialRender = it)) }
        }
        if (shortTarget) {
            item { SettingsNoteLine(stringResource(R.string.settings_kernel_short_mpv_note)) }
        }
    } else {
        item { GroupLabel(stringResource(R.string.settings_kernel_exo)) }
        if (!shortTarget) {
            item {
                SegmentedRow(
                    title = stringResource(R.string.settings_kernel_exo_network),
                    desc = stringResource(R.string.settings_kernel_exo_network_desc),
                    options = listOf(
                        Segment(
                            ExoNetworkRoute.System,
                            stringResource(R.string.settings_kernel_exo_network_system)
                        ),
                        Segment(ExoNetworkRoute.OkHttp, "OkHttp")
                    ),
                    selected = state.exoNetwork
                ) { onChange(state.copy(exoNetwork = it)) }
            }
            item {
                SegmentedRow(
                    title = stringResource(R.string.settings_kernel_exo_render),
                    desc = stringResource(R.string.settings_kernel_exo_render_desc),
                    options = listOf(
                        Segment(false, "Texture"),
                        Segment(true, "Surface")
                    ),
                    selected = state.exoRenderSurface
                ) { onChange(state.copy(exoRenderSurface = it)) }
            }
        } else {
            item {
                SegmentedRow(
                    title = stringResource(R.string.settings_kernel_short_render),
                    desc = stringResource(R.string.settings_kernel_short_render_desc),
                    options = listOf(
                        Segment(false, stringResource(R.string.settings_kernel_short_render_standard)),
                        Segment(true, stringResource(R.string.settings_kernel_short_render_fast))
                    ),
                    selected = state.shortFastRender
                ) { onChange(state.copy(shortFastRender = it)) }
            }
        }
        item {
            ToggleRow(
                stringResource(R.string.hint_lab_mpv_super_resolution),
                stringResource(R.string.hint_lab_mpv_super_resolution_desc),
                state.exoSharpen
            ) { onChange(state.copy(exoSharpen = it)) }
        }
        item {
            SliderRow(
                title = stringResource(
                    R.string.settings_kernel_exo_sharpen_strength,
                    String.format(Locale.US, "%.1f", state.exoSharpenStrength)
                ),
                desc = stringResource(R.string.settings_kernel_exo_sharpen_strength_desc),
                value = state.exoSharpenStrength,
                valueRange = 0f..3f,
                steps = 5,
                enabled = state.exoSharpen
            ) { onChange(state.copy(exoSharpenStrength = it)) }
        }
    }

    // TLS 证书校验是两个内核共用的开关：MPV 侧是 tls-verify，Exo 侧只有 OkHttp 数据源能改证书校验，
    // 所以放在内核分组之外，切内核不会把它藏起来。
    item {
        ToggleRow(
            stringResource(R.string.settings_kernel_skip_tls_verify),
            stringResource(R.string.settings_kernel_skip_tls_verify_desc),
            state.skipTlsVerify
        ) { onChange(state.copy(skipTlsVerify = it)) }
    }

    item { SettingsNoteLine(stringResource(R.string.settings_kernel_note)) }
}

// ---------- 资源库与显示 ----------

fun LazyListScope.librarySettings(
    state: PlayerSettingsState,
    onChange: (PlayerSettingsState) -> Unit
) {
    item {
        ToggleRow(
            stringResource(R.string.settings_library_new_home),
            stringResource(R.string.settings_library_new_home_desc),
            state.newHome
        ) { onChange(state.copy(newHome = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_video_detail),
            stringResource(R.string.hint_video_detail_desc),
            state.videoDetailMode
        ) { onChange(state.copy(videoDetailMode = it)) }
    }
    item {
        SegmentedRow(
            title = stringResource(R.string.settings_library_cover_ratio),
            desc = stringResource(R.string.settings_library_cover_ratio_desc),
            options = listOf(Segment("3:4", "3:4"), Segment("1:1", "1:1"), Segment("4:3", "4:3")),
            selected = state.coverRatio
        ) { onChange(state.copy(coverRatio = it)) }
    }
}

// ---------- 弹幕 ----------

fun LazyListScope.danmakuSettings(
    state: DanmakuSettingsState,
    onChange: (DanmakuSettingsState) -> Unit,
    onPickFont: () -> Unit,
    onEditFilter: () -> Unit,
    onEditMerge: () -> Unit
) {
    item {
        ToggleRow(
            stringResource(R.string.hint_dm_gradient),
            stringResource(R.string.settings_dm_gradient_toggle_desc, state.gradientRatio.toString()),
            state.gradient
        ) { onChange(state.copy(gradient = it)) }
    }
    item {
        SliderRow(
            title = stringResource(R.string.settings_dm_gradient_ratio, state.gradientRatio),
            desc = stringResource(R.string.settings_dm_gradient_ratio_desc),
            value = state.gradientRatio.toFloat(),
            valueRange = 0f..100f,
            steps = 99,
            enabled = state.gradient
        ) { onChange(state.copy(gradientRatio = it.toInt())) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_dm_gradient_text_color),
            stringResource(R.string.hint_dm_gradient_text_color_desc),
            state.keepTextColor
        ) { onChange(state.copy(keepTextColor = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_dm_stroke_multiple),
            stringResource(R.string.hint_dm_stroke_multiple_desc, strokeLabel(state.strokeMultiple)),
            state.strokeMultipleMode
        ) { onChange(state.copy(strokeMultipleMode = it)) }
    }
    item {
        SliderRow(
            title = stringResource(
                R.string.settings_dm_stroke_multiple,
                strokeLabel(state.strokeMultiple)
            ),
            desc = stringResource(R.string.settings_dm_stroke_multiple_desc),
            value = state.strokeMultiple,
            valueRange = 1f..3f,
            enabled = state.strokeMultipleMode
        ) { onChange(state.copy(strokeMultiple = it)) }
    }

    item { GroupLabel(stringResource(R.string.settings_dm_section_font)) }
    item {
        ToggleRow(
            stringResource(R.string.hint_dm_font),
            stringResource(R.string.settings_dm_font_toggle_desc),
            state.fontMode
        ) { onChange(state.copy(fontMode = it)) }
    }
    item {
        // 选字体是「动作」不是开关，所以是 SettingRow；选完会自动打开上面的开关
        val fontName = state.fontName.ifBlank { stringResource(R.string.settings_dm_font_default) }
        SettingRow(
            stringResource(R.string.settings_dm_font_pick),
            stringResource(R.string.settings_dm_font_pick_desc, fontName),
            stringResource(R.string.action_select),
            onPickFont
        )
    }
    if (state.fontPath.isNotBlank()) {
        // 选错了得能退回去：文件选择器只能挑新文件，没有「不选」这条路
        item {
            SettingRow(
                stringResource(R.string.settings_dm_font_reset),
                stringResource(R.string.settings_dm_font_reset_desc),
                stringResource(R.string.action_clear),
                { onChange(state.copy(fontPath = "", fontName = "", fontMode = false)) }
            )
        }
    }

    item { GroupLabel(stringResource(R.string.settings_dm_section_behavior)) }
    item {
        ToggleRow(
            stringResource(R.string.settings_dm_merge),
            stringResource(R.string.settings_dm_merge_desc),
            state.mergeMode
        ) { onChange(state.copy(mergeMode = it)) }
    }
    item {
        // 阈值原来是「长按开关弹双 SeekBar 对话框」，没有任何可见提示；
        // 现在是一行可见的入口，副标题直接写当前两个阈值
        SettingRow(
            stringResource(R.string.settings_dm_merge_threshold),
            stringResource(
                R.string.settings_dm_merge_threshold_desc,
                state.mergeShow,
                state.mergeTop
            ),
            stringResource(R.string.action_adjust),
            onEditMerge
        )
    }
    item {
        ToggleRow(
            stringResource(R.string.settings_dm_show_time),
            stringResource(R.string.settings_dm_show_time_desc),
            state.showTime
        ) { onChange(state.copy(showTime = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.settings_dm_show_fps),
            stringResource(R.string.settings_dm_show_fps_desc),
            state.showFps
        ) { onChange(state.copy(showFps = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.settings_dm_filter),
            stringResource(R.string.settings_dm_filter_desc),
            state.filterMode
        ) { onChange(state.copy(filterMode = it)) }
    }
    item {
        SettingRow(
            stringResource(R.string.settings_dm_filter_words),
            if (state.filterKeywords.isEmpty()) {
                stringResource(R.string.settings_dm_filter_words_empty)
            } else {
                stringResource(R.string.settings_dm_filter_words_count, state.filterKeywords.size)
            },
            stringResource(R.string.action_edit),
            onEditFilter
        )
    }
}

private fun strokeLabel(value: Float): String = String.format(Locale.US, "%.2f", value)

// ---------- 实验室 ----------

fun LazyListScope.labSettings(
    state: LabSettingsState,
    onChange: (LabSettingsState) -> Unit
) {
    item {
        ToggleRow(
            stringResource(R.string.hint_lab_long_video_portrait),
            stringResource(R.string.hint_lab_long_video_portrait_desc),
            state.longVideoPortrait
        ) { onChange(state.copy(longVideoPortrait = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_lab_auto_play_on_launch),
            stringResource(R.string.hint_lab_auto_play_on_launch_desc),
            state.autoPlayOnLaunch
        ) { onChange(state.copy(autoPlayOnLaunch = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_lab_short_auto_load_progress),
            stringResource(R.string.hint_lab_short_auto_load_progress_desc),
            state.shortAutoLoadProgress
        ) { onChange(state.copy(shortAutoLoadProgress = it)) }
    }
    item {
        ToggleRow(
            stringResource(R.string.hint_debug_mode),
            stringResource(R.string.hint_debug_mode_desc),
            state.debugMode
        ) { onChange(state.copy(debugMode = it)) }
    }
}

// ---------- 共用小件 ----------

/** 行内说明：比 GroupLabel 轻，用来解释「什么时候生效」这类信息，别塞进每行 desc */
@Composable
fun SettingsNoteLine(text: String) {
    val c = LocalDyLikeColors.current
    Text(
        text,
        style = DyLikeType.meta,
        color = c.textTertiary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
    )
}

/** 过滤词表：一行一个词，比旧页面的 chip 输入更适合批量粘贴 */
@Composable
fun FilterWordsDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val c = LocalDyLikeColors.current
    var text by rememberSaveable(initial) { mutableStateOf(initial.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(stringResource(R.string.settings_dm_filter_words_title), color = c.textPrimary)
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_dm_filter_words_edit_desc),
                    style = DyLikeType.meta,
                    color = c.textSecondary
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 6,
                    maxLines = 12
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parseFilterWords(text)) }) {
                Text(stringResource(R.string.action_confirmed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * 合并阈值：两个滑杆替代旧 `DmMergeOptionDialog` 的双 SeekBar。
 *
 * 下限沿用旧弹窗（显示 ≥2、转顶部 ≥5），上限 20 / 50。滑杆只在本地改，
 * 点「确认」才写回，取消就等于没动过。
 */
@Composable
fun MergeThresholdDialog(
    show: Int,
    top: Int,
    onDismiss: () -> Unit,
    onConfirm: (show: Int, top: Int) -> Unit
) {
    val c = LocalDyLikeColors.current
    var showValue by rememberSaveable { mutableFloatStateOf(show.toFloat()) }
    var topValue by rememberSaveable { mutableFloatStateOf(top.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = {
            Text(stringResource(R.string.settings_dm_merge_threshold_title), color = c.textPrimary)
        },
        text = {
            Column {
                ThresholdSlider(
                    label = stringResource(R.string.hint_dm_merge_show, showValue.toInt().toString()),
                    value = showValue,
                    range = 2f..20f,
                    steps = 17
                ) { showValue = it }
                Spacer(Modifier.height(10.dp))
                ThresholdSlider(
                    label = stringResource(R.string.hint_dm_merge_top, topValue.toInt().toString()),
                    value = topValue,
                    range = 5f..50f,
                    steps = 44
                ) { topValue = it }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_dm_merge_threshold_note),
                    style = DyLikeType.meta,
                    color = c.textTertiary
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(showValue.toInt(), topValue.toInt()) }) {
                Text(stringResource(R.string.action_confirmed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ThresholdSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit
) {
    val c = LocalDyLikeColors.current
    Text(label, style = MaterialTheme.typography.titleSmall, color = c.textPrimary)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = c.accent,
            activeTrackColor = c.accent,
            inactiveTrackColor = c.track
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun parseFilterWords(raw: String): List<String> = raw
    .lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .toList()

// ---------- Preview ----------

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 1400)
@Composable
private fun SettingsSectionsPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        LazyColumn {
            item { GroupLabel("播放与进度") }
            playbackSettings(
                state = PlayerSettingsState(audioFadeEnabled = true),
                onChange = {},
                onBackgroundPlayEnabled = {}
            )
            item { GroupLabel("播放内核") }
            kernelSettings(
                state = KernelSettingsState(exoSharpen = true, exoSharpenStrength = 1.6f),
                onChange = {},
                shortTarget = false,
                onShortTarget = {}
            )
            item { GroupLabel("弹幕") }
            danmakuSettings(
                state = DanmakuSettingsState(filterKeywords = listOf("前排", "考古")),
                onChange = {},
                onPickFont = {},
                onEditFilter = {},
                onEditMerge = {}
            )
        }
    }
}

/**
 * 外观摘要：「我的」页分类行、播放页的外观入口、个人卡片共用同一套文案，
 * 避免三处各写一遍 `when (themeMode)` 后不同步。
 */
fun themeSummary(themeMode: ThemeMode, brand: BrandHue, dynamicColor: Boolean): String {
    val mode = when (themeMode) {
        ThemeMode.System -> "跟随系统"
        ThemeMode.Light -> "浅色"
        ThemeMode.Dark -> "深色"
    }
    return "$mode · ${brand.label}光色" + if (dynamicColor) " · 动态光" else ""
}
