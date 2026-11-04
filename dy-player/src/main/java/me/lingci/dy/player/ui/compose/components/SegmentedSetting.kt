package me.lingci.dy.player.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors

/** 按钮组的一个选项。label 由调用方给出（中文走 stringResource，专有名词直接写字面量） */
@Immutable
data class Segment<T>(val value: T, val label: String)

/**
 * 单选按钮组：一串等宽胶囊按钮，选中项用强调色填充。
 *
 * 设置里凡是「二选一/三选一」的枚举型偏好都用它，不要再用「开/关」开关去表达多值语义
 * （例如内核 Exo/MPV、MPV 视频输出 gpu/gpu-next）。
 */
@Composable
fun <T> SegmentedRow(
    title: String,
    options: List<Segment<T>>,
    selected: T,
    modifier: Modifier = Modifier,
    desc: String? = null,
    enabled: Boolean = true,
    /**
     * 每行排几个。默认 ≤3 项单行、>3 项两列网格——360dp 宽度下 4 个长标签会被 Ellipsis 截断；
     * 标签很短时（如 `0.5s / 1s / 1.5s / 2s`）可显式给 4，一排放得下也更好比。
     */
    perLine: Int? = null,
    onSelect: (T) -> Unit
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) c.textPrimary else c.textTertiary
        )
        if (desc != null) {
            Text(
                desc,
                style = DyLikeType.meta,
                color = c.textSecondary.copy(alpha = if (enabled) 1f else .5f)
            )
        }
        Spacer(Modifier.height(10.dp))
        // 列数由调用方定；没指定时按选项数猜：>3 项排两列，避免标签被截成「MediaCodec…」
        val per = (perLine ?: if (options.size > 3) 2 else options.size).coerceIn(1, options.size.coerceAtLeast(1))
        val lines = options.chunked(per)
        Column(
            Modifier.fillMaxWidth()
                .clip(shape)
                .background(c.surface)
                .border(1.dp, c.hairline, shape)
                .padding(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            lines.forEach { line ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    line.forEach { option ->
                        SegmentButton(
                            label = option.label,
                            active = option.value == selected,
                            enabled = enabled,
                            modifier = Modifier.weight(1f)
                        ) { onSelect(option.value) }
                    }
                    // 末行不足时补占位，保证同一列的按钮等宽
                    repeat(per - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SegmentButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(9.dp))
            // 禁用时不要再填强调色：填充不变而文字转成禁用色，会变成「青底青字」看不清
            .background(if (active && enabled) c.accent else Color.Transparent)
            .clickable(enabled = enabled && !active, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> if (active) c.textSecondary else c.textTertiary
                active -> c.onAccent
                else -> c.textSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 滑杆行：拖动时只更新本地值，松手（onValueChangeFinished）才写回偏好，
 * 避免一次拖动把 SP 写几百次。
 */
@Composable
fun SliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    desc: String? = null,
    steps: Int = 0,
    enabled: Boolean = true,
    valueLabel: String? = null,
    onCommit: (Float) -> Unit
) {
    val c = LocalDyLikeColors.current
    // key 用外部值：偏好被别处改动（如备份还原）时滑杆能跟着复位
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) c.textPrimary else c.textTertiary,
                modifier = Modifier.weight(1f)
            )
            if (valueLabel != null) {
                Text(valueLabel, style = DyLikeType.timecode, color = c.accent)
            }
        }
        if (desc != null) {
            Text(desc, style = DyLikeType.meta, color = c.textSecondary.copy(alpha = if (enabled) 1f else .5f))
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = c.accent,
                activeTrackColor = c.accent,
                inactiveTrackColor = c.track,
                // 不加这三项时禁用态仍会用强调色画滑块，看起来「可拖动但其实点不动」
                disabledThumbColor = c.textTertiary.copy(alpha = .6f),
                disabledActiveTrackColor = c.track,
                disabledInactiveTrackColor = c.track
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
