package me.lingci.dy.player.ui.compose.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

@Composable
fun DyLikeTheme(
    mode: ThemeMode,
    brand: BrandHue,
    dynamicColor: Boolean,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val dynamicScheme = remember(dynamicColor, dark) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
    }
    // 必须 remember：否则每次重组都产生新实例，既让下面的 remember(colors) 失效，
    // 又因 LocalDyLikeColors 是 staticCompositionLocalOf 而触发整棵子树重组
    val colors = remember(dark, brand, dynamicScheme) {
        dyLikeColors(dark, brand, dynamicScheme?.primary, dynamicScheme?.onPrimary)
    }
    val playerColors = remember(brand, dynamicScheme) {
        dyLikeColors(true, brand, dynamicScheme?.primary, dynamicScheme?.onPrimary)
    }
    val scheme = remember(colors) { dyLikeScheme(colors) }
    val view = LocalView.current
    SideEffect {
        if (!view.isInEditMode) {
            context.findActivity()?.window?.let { applySystemBarAppearance(it, view, dark) }
        }
    }
    CompositionLocalProvider(
        LocalDyLikeColors provides colors,
        LocalPlayerColors provides playerColors
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography,
            shapes = DyLikeShapes,
            content = content
        )
    }
}

private fun dyLikeScheme(c: DyLikeColors): ColorScheme {
    val base = if (c.dark) darkColorScheme() else lightColorScheme()
    val accentContainer = if (c.dark) {
        lerp(c.surfaceRaised, c.accent, .22f)
    } else {
        lerp(c.surfaceRaised, c.accent, .14f)
    }
    return base.copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = accentContainer,
        onPrimaryContainer = c.textPrimary,
        inversePrimary = c.accent,
        secondary = c.textSecondary,
        onSecondary = c.background,
        secondaryContainer = c.surfaceRaised,
        onSecondaryContainer = c.textPrimary,
        tertiary = c.info,
        onTertiary = if (c.dark) c.background else Color.White,
        tertiaryContainer = c.surfaceRaised,
        onTertiaryContainer = c.textPrimary,
        background = c.background,
        onBackground = c.textPrimary,
        surface = c.surface,
        onSurface = c.textPrimary,
        surfaceVariant = c.surfaceRaised,
        onSurfaceVariant = c.textSecondary,
        surfaceTint = c.accent,
        inverseSurface = c.textPrimary,
        inverseOnSurface = c.background,
        outline = c.textTertiary,
        outlineVariant = c.hairline,
        error = if (c.dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
        onError = if (c.dark) Color(0xFF690005) else Color.White,
        errorContainer = if (c.dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
        onErrorContainer = if (c.dark) Color(0xFFFFDAD6) else Color(0xFF410002),
        surfaceContainerLowest = c.background,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surfaceRaised,
        surfaceContainerHighest = c.surfaceOverlay,
        scrim = Color.Black
    )
}

internal fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Suppress("DEPRECATION")
private fun applySystemBarAppearance(window: Window, view: View, dark: Boolean) {
    window.statusBarColor = android.graphics.Color.TRANSPARENT
    window.navigationBarColor = android.graphics.Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, view).apply {
        isAppearanceLightStatusBars = !dark
        isAppearanceLightNavigationBars = !dark
    }
}