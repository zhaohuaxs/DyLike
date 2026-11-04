package me.lingci.dy.player.ui.compose.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class WindowWidth { Compact, Medium, Expanded }

val LocalBottomBarClearance = staticCompositionLocalOf { 20.dp }

fun windowWidthClass(width: Dp): WindowWidth = when {
    width < 600.dp -> WindowWidth.Compact
    width < 840.dp -> WindowWidth.Medium
    else -> WindowWidth.Expanded
}

@Composable
fun CenteredContent(maxWidth: Dp, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = maxWidth).fillMaxSize()) { content() }
    }
}
