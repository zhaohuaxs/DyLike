package me.lingci.dy.player.ui.compose.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*

import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.ui.compose.model.mediaTypeLabel
import me.lingci.dy.player.ui.compose.theme.*

private val TileShape = RoundedCornerShape(10.dp)
private val CardShape = RoundedCornerShape(16.dp)
private val NavShape = RoundedCornerShape(28.dp)
private val NavItemShape = RoundedCornerShape(22.dp)

private val posterPalettes = listOf(
    listOf(Color(0xFF1C2733), Color(0xFF476A78), Color(0xFFD3A15F)),
    listOf(Color(0xFF241A24), Color(0xFF6B3C4B), Color(0xFFC98B6F)),
    listOf(Color(0xFF141A21), Color(0xFF2E4B5C), Color(0xFF7FB3BC)),
    listOf(Color(0xFF231814), Color(0xFF6B392B), Color(0xFFD29B5F)),
    listOf(Color(0xFF18211C), Color(0xFF355245), Color(0xFF8FAE8E)),
    listOf(Color(0xFF1B1826), Color(0xFF4A3A68), Color(0xFFA78FD4))
)

@Composable
fun Kicker(
    text: String,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    color: Color = Color.Unspecified
) {
    val c = LocalDyLikeColors.current
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = DyLikeType.overline,
        color = when {
            color != Color.Unspecified -> color
            accent -> c.accent
            else -> c.textTertiary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
        style = DyLikeType.overline,
        color = LocalDyLikeColors.current.textTertiary
    )
}

@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
    kicker: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (kicker != null) {
                Kicker(kicker, accent = true)
                Spacer(Modifier.height(4.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, color = c.textPrimary)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        if (action != null) action()
    }
}

@Composable
fun SectionHeader(
    title: String,
    trailing: String = "查看全部",
    modifier: Modifier = Modifier,
    kicker: String? = null,
    onClick: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    Row(
        modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (kicker != null) {
                Kicker(kicker)
                Spacer(Modifier.height(3.dp))
            }
            Text(title, style = DyLikeType.section, color = c.textPrimary)
        }
        Row(
            Modifier.clip(CircleShape).clickable { onClick() }.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(trailing, color = c.textSecondary, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(1.dp))
            Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.height(44.dp).clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .padding(start = 13.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Search, null, tint = c.textTertiary, modifier = Modifier.size(17.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = c.textPrimary, fontSize = 14.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).padding(horizontal = 9.dp),
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = c.textTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (value.isNotEmpty()) {
            AppIconButton(
                icon = Icons.Default.Close,
                contentDescription = "清空",
                onClick = { onValueChange("") },
                modifier = Modifier.size(30.dp),
                iconSize = 15.dp
            )
        }
    }
}

@Composable
fun AppIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = LocalDyLikeColors.current.textSecondary,
    iconSize: Dp = 21.dp
) {
    Box(
        modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(Color.Black.copy(alpha = .30f))
            .border(1.dp, Color.White.copy(alpha = .16f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription, tint = Color.White.copy(alpha = .92f), modifier = Modifier.size(size * .44f))
    }
}

@Composable
fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.height(46.dp).clip(shape)
            .background(if (enabled) c.accent else c.accent.copy(alpha = .35f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.onAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c.onAccent, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
fun GhostButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier.height(46.dp).clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = c.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
fun ToggleRow(
    title: String,
    summary: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent,
                checkedTrackColor = c.accent,
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = c.textTertiary,
                uncheckedTrackColor = c.surfaceRaised,
                uncheckedBorderColor = c.hairline
            )
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    summary: String? = null,
    value: String? = null,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = c.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        if (value != null) {
            Text(value, color = c.textSecondary, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null, tint = c.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
    HorizontalDivider(color = c.hairline, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
fun StatePill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    val bg = if (selected) c.accent.copy(alpha = .14f) else c.surfaceRaised.copy(alpha = .68f)
    val fg = if (selected) c.accent else c.textSecondary
    val border = if (selected) c.accent.copy(alpha = .30f) else c.hairline
    Text(
        text,
        color = fg,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier.clip(CircleShape)
            .background(bg)
            .border(1.dp, border, CircleShape)
            .clickable { onClick() }
            .heightIn(min = 32.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .wrapContentHeight(Alignment.CenterVertically)
    )
}

@Composable
fun MetaChip(
    text: String,
    icon: ImageVector? = null,
    onDark: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(7.dp)
    val bg = if (onDark) Color.White.copy(alpha = .10f) else c.surfaceRaised.copy(alpha = .72f)
    val fg = if (onDark) Color.White.copy(alpha = .86f) else c.textSecondary
    val borderColor = if (onDark) Color.White.copy(alpha = .16f) else c.hairline
    Row(
        modifier.clip(shape).background(bg).border(1.dp, borderColor, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = fg, style = DyLikeType.micro, maxLines = 1)
    }
}

@Composable
fun ShortcutChip(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.height(34.dp).clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = c.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = c.textSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun SourceBadge(text: String, modifier: Modifier = Modifier) {
    val icon = when {
        text.contains("WebDAV", ignoreCase = true) -> Icons.Default.CloudQueue
        text.contains("SMB", ignoreCase = true) -> Icons.Default.Storage
        text.contains("在线", ignoreCase = true) -> Icons.Default.Link
        else -> Icons.Default.Folder
    }
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier.clip(shape)
            .background(Color.Black.copy(alpha = .38f))
            .border(1.dp, Color.White.copy(alpha = .14f), shape)
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(10.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, color = Color.White.copy(alpha = .9f), style = DyLikeType.micro, maxLines = 1)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterTile(
    media: MediaUiModel,
    index: Int,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    duration: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val player = LocalPlayerColors.current
    val colors = posterPalettes[index % posterPalettes.size]
    val openLabel = stringResource(R.string.cd_open_library)
    val moreLabel = stringResource(R.string.cd_more_actions)
    val source = mediaTypeLabel(media.type)
    val title = media.title
    Column(
        modifier.clip(RoundedCornerShape(12.dp))
            // 合并子节点并给整卡一个可读描述：卡内文字是零散的 Text 节点，
            // 不合并的话 TalkBack 会逐个朗读，读不出「这是一张卡片」
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(title, source).joinToString("，")
            }
            .then(
                if (onLongClick != null) Modifier.combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClickLabel = openLabel,
                    onLongClickLabel = moreLabel,
                    onClick = onClick,
                    onLongClick = onLongClick
                ) else Modifier.clickable(onClickLabel = openLabel, onClick = onClick)
            )
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(TileShape)
                .background(Brush.linearGradient(colors))
                .border(1.dp, Color.White.copy(alpha = .06f), TileShape)
        ) {
            CoverImage(cover = media, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.75f to Color.Transparent,
                            1f to Color.Black.copy(alpha = .38f)
                        )
                    )
                )
            )
            Text(
                (index + 1).toString().padStart(2, '0'),
                color = Color.White.copy(alpha = .07f),
                style = DyLikeType.timecode.copy(fontSize = 40.sp, lineHeight = 44.sp),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 10.dp)
            )
            SourceBadge(source, Modifier.align(Alignment.TopStart).padding(8.dp))

            if (duration != null) {
                Text(
                    duration,
                    color = Color.White.copy(alpha = .86f),
                    style = DyLikeType.micro,
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .padding(end = 8.dp, bottom = if (progress != null) 10.dp else 7.dp)
                )
            }
            if (progress != null) {
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                        .background(Color.White.copy(alpha = .18f))
                ) {
                    Box(
                        Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                            .background(player.accent)
                    )
                }
            }
        }
        Text(
            title,
            modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = DyLikeType.posterTitle,
            color = c.textPrimary
        )
    }
}

@Composable
fun MediaThumb(
    index: Int,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    showPlay: Boolean = true
) {
    val player = LocalPlayerColors.current
    val shape = RoundedCornerShape(9.dp)
    val colors = posterPalettes[index % posterPalettes.size]
    Box(
        modifier.clip(shape)
            .background(Brush.linearGradient(colors))
            .border(1.dp, Color.White.copy(alpha = .06f), shape)
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .42f)))
            )
        )
        if (showPlay) {
            Box(
                Modifier.align(Alignment.Center).size(28.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = .20f))
                    .border(1.dp, Color.White.copy(alpha = .18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    null,
                    tint = Color.White.copy(alpha = .8f),
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        if (progress != null) {
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp)
                    .background(Color.White.copy(alpha = .18f))
            ) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(player.accent)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SourceCard(
    icon: ImageVector,
    title: String,
    detail: String,
    tint: Color,
    status: String? = null,
    statusColor: Color = Color.Unspecified,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = .13f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (status != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(4.dp).clip(CircleShape)
                            .background(if (statusColor != Color.Unspecified) statusColor else c.success)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(status, style = DyLikeType.micro, color = c.textTertiary)
                }
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalDyLikeColors.current
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(66.dp).clip(RoundedCornerShape(20.dp)).background(c.accent.copy(alpha = .10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.MovieFilter, null, tint = c.accent, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.clip(CircleShape).clickable { onAction() }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action, color = c.accent, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = c.accent, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun DyLikeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Color.Unspecified
) {
    val c = LocalDyLikeColors.current
    val active = if (accent == Color.Unspecified) c.accent else accent
    Box(
        modifier.fillMaxWidth().height(32.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
                setProgress { target ->
                    onValueChange(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onValueChange((down.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        onValueChange((change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            val inset = 9.dp.toPx()
            val usable = (size.width - inset * 2).coerceAtLeast(1f)
            val x = inset + usable * value.coerceIn(0f, 1f)
            drawLine(c.track, Offset(inset, y), Offset(size.width - inset, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(active, Offset(inset, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(active.copy(alpha = .22f), 9.dp.toPx(), Offset(x, y))
            drawCircle(c.textPrimary, 5.dp.toPx(), Offset(x, y))
        }
    }
}

data class NavBarItem(val key: String, val label: String, val icon: ImageVector)

@Composable
fun FloatingNavBar(
    items: List<NavBarItem>,
    selectedKey: String,
    showLabels: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    Box(
        modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = NavShape,
            color = c.surface,
            contentColor = c.textPrimary,
            modifier = Modifier.widthIn(max = 520.dp)
                .border(1.dp, c.hairline, NavShape)
        ) {
            Row(
                Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { item ->
                    val selected = item.key == selectedKey
                    val itemBackground by animateColorAsState(
                        targetValue = if (selected) c.textPrimary.copy(alpha = .06f) else Color.Transparent,
                        animationSpec = tween(220),
                        label = "navItemBackground"
                    )
                    val itemForeground by animateColorAsState(
                        targetValue = if (selected) c.accent else c.textSecondary,
                        animationSpec = tween(220),
                        label = "navItemForeground"
                    )
                    val itemHorizontalPadding by animateDpAsState(
                        targetValue = if (showLabels) 10.dp else if (selected) 18.dp else 16.dp,
                        animationSpec = tween(220),
                        label = "navItemPadding"
                    )
                    val interactionSource = remember(item.key) { MutableInteractionSource() }
                    val indication = if (showLabels) LocalIndication.current else null
                    Row(
                        modifier = Modifier
                            .then(if (showLabels) Modifier.weight(1f) else Modifier)
                            .height(48.dp)
                            .clip(NavItemShape)
                            .background(itemBackground)
                            .clickable(interactionSource, indication = indication) { onSelect(item.key) }
                            .padding(horizontal = itemHorizontalPadding),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            item.icon,
                            if (showLabels || selected) null else item.label,
                            tint = itemForeground,
                            modifier = Modifier.size(22.dp)
                        )
                        AnimatedVisibility(
                            visible = showLabels || selected,
                            enter = fadeIn(tween(160)) + expandHorizontally(tween(220)),
                            exit = fadeOut(tween(120)) + shrinkHorizontally(tween(220))
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    item.label,
                                    color = if (selected) c.textPrimary else c.textSecondary,
                                    style = DyLikeType.navLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selected) {
                                    Spacer(Modifier.width(5.dp))
                                    Box(Modifier.size(3.dp).clip(CircleShape).background(c.accent))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 封面：只吃「已解析好的」[MediaUiModel]，渲染交给 [ThumbImage]。
 *
 * 解析（`CoverHelper.resolveCover`，含 WebDAV 认证头）在 ViewModel 里完成，
 * 组件不再接触 Room 实体与资源列表——属性只有一个，重组也就只有一档。
 * 本地路径命中 [ThumbCache] 首帧即终态；http 封面连同认证头一起走 Glide 兜底。
 */
@Composable
fun CoverImage(cover: MediaUiModel?, modifier: Modifier = Modifier) {
    ThumbImage(
        path = cover?.coverPath,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        headers = cover?.coverHeaders ?: emptyMap(),
        placeholder = { CoverPlaceholder() }
    )
}

/** 封面缺失时的兜底图，与旧 ImageView 分支里的 `setImageResource` 保持一致。 */
@Composable
internal fun BoxScope.CoverPlaceholder() {
    Image(
        painter = painterResource(R.drawable.ic_media_default),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.matchParentSize()
    )
}

@Composable
fun ResumeCard(
    title: String,
    episode: String,
    remaining: String,
    progress: Float,
    coverPath: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val player = LocalPlayerColors.current
    val continueLabel = stringResource(R.string.cd_continue_play)
    Box(
        modifier.aspectRatio(16f / 9f)
            .clip(CardShape)
            .background(Brush.linearGradient(listOf(Color(0xFF1A2129), Color(0xFF4D3A48), Color(0xFF1B1310))))
            .border(1.dp, Color.White.copy(alpha = .07f), CardShape)
            .semantics(mergeDescendants = true) {
                contentDescription = "$title，$episode，剩余 $remaining"
            }
            .clickable(onClickLabel = continueLabel, onClick = onClick)
    ) {
        if (coverPath != null) {
            // 直接用「已解析路径」渲染：继续观看的缩略图来自播放进度缓存，不是媒体封面，
            // 走 CoverImage 反而要先造一个假的 MediaUiModel
            ThumbImage(
                path = coverPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(Color.Black.copy(alpha = .52f), Color.Transparent))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .76f)))
            )
        )
        Text(
            "CONTINUE",
            color = Color.White.copy(alpha = .55f),
            style = DyLikeType.overline,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp)
        )
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp)) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                episode,
                color = Color.White.copy(alpha = .68f),
                style = DyLikeType.meta,
                maxLines = 1,
                modifier = Modifier.padding(top = 1.dp)
            )
            Spacer(Modifier.height(9.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(remaining, color = Color.White.copy(alpha = .8f), style = DyLikeType.timecode)
                Spacer(Modifier.weight(1f))
                Text(
                    "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    color = Color.White.copy(alpha = .8f),
                    style = DyLikeType.timecode
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = .20f))) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                        .background(player.accent)
                )
            }
        }
    }
}


@Composable
fun FloatingNavRail(
    items: List<NavBarItem>,
    selectedKey: String,
    showLabels: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    val railWidth by animateDpAsState(
        targetValue = if (showLabels) 96.dp else 72.dp,
        animationSpec = tween(240),
        label = "navRailWidth"
    )
    val itemHeight by animateDpAsState(
        targetValue = if (showLabels) 64.dp else 52.dp,
        animationSpec = tween(240),
        label = "navRailItemHeight"
    )
    Box(
        modifier.fillMaxHeight().padding(horizontal = 14.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = NavShape,
            color = c.surface,
            contentColor = c.textPrimary,
            modifier = Modifier.width(railWidth)
                .border(1.dp, c.hairline, NavShape)
        ) {
            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items.forEach { item ->
                    val selected = item.key == selectedKey
                    val itemBackground by animateColorAsState(
                        targetValue = if (selected) c.textPrimary.copy(alpha = .06f) else Color.Transparent,
                        animationSpec = tween(220),
                        label = "navRailItemBackground"
                    )
                    val itemForeground by animateColorAsState(
                        targetValue = if (selected) c.accent else c.textSecondary,
                        animationSpec = tween(220),
                        label = "navRailItemForeground"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .clip(NavItemShape)
                            .background(itemBackground)
                            .clickable { onSelect(item.key) }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                item.icon,
                                if (showLabels) null else item.label,
                                tint = itemForeground,
                                modifier = Modifier.size(24.dp)
                            )
                            AnimatedVisibility(
                                visible = showLabels,
                                enter = fadeIn(tween(160)) + expandVertically(tween(220)),
                                exit = fadeOut(tween(120)) + shrinkVertically(tween(220))
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        item.label,
                                        color = if (selected) c.textPrimary else c.textSecondary,
                                        style = DyLikeType.navLabel,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (selected) {
                            Box(
                                Modifier.align(Alignment.CenterStart).padding(start = 9.dp)
                                    .width(2.dp).height(20.dp).clip(CircleShape).background(c.accent)
                            )
                        }
                    }
                }
            }
        }
    }
}
