package me.lingci.dy.player.ui.compose.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

enum class ThemeMode { System, Light, Dark }

enum class BrandHue(val label: String, val seed: Color, val ambient: Color) {
    Purple("夜紫", Color(0xFF8B5CF6), Color(0xFF553C9E)),
    Pink("幕红", Color(0xFFD9577F), Color(0xFF8E2B4D)),
    Orange("胶片橙", Color(0xFFD98345), Color(0xFF8F4E1F)),
    Cyan("冷青", Color(0xFF2AA3B0), Color(0xFF14636D))
}

@Immutable
data class DyLikeColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceOverlay: Color,
    val hairline: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val ambient: Color,
    val like: Color,
    val danmaku: Color,
    val warning: Color,
    val info: Color,
    val success: Color,
    val buffered: Color,
    val track: Color
)

fun dyLikeColors(
    dark: Boolean,
    brand: BrandHue,
    dynamicAccent: Color? = null,
    dynamicOnAccent: Color? = null
): DyLikeColors {
    val accent = dynamicAccent ?: if (dark) {
        lerp(brand.seed, Color.White, .28f)
    } else {
        lerp(brand.seed, Color.Black, .22f)
    }
    val onAccent = dynamicOnAccent ?: if (dark) Color(0xFF17121C) else Color.White
    val ambient = if (dynamicAccent != null) {
        lerp(accent, if (dark) Color.Black else Color.White, .40f)
    } else {
        brand.ambient
    }
    return if (dark) {
        DyLikeColors(
            dark = true,
            background = Color(0xFF0A0A0D),
            surface = Color(0xFF111115),
            surfaceRaised = Color(0xFF18181E),
            surfaceOverlay = Color(0xFF202026),
            hairline = Color.White.copy(alpha = .08f),
            textPrimary = Color(0xFFF2EEE6),
            textSecondary = Color(0xFFA9A399),
            textTertiary = Color(0xFF8A837A),
            accent = accent,
            onAccent = onAccent,
            ambient = ambient,
            like = Color(0xFFFF5F7A),
            danmaku = Color(0xFFFFE08A),
            warning = Color(0xFFFFB86B),
            info = Color(0xFF6FC7D6),
            success = Color(0xFF7FD6A8),
            buffered = Color.White.copy(alpha = .26f),
            track = Color.White.copy(alpha = .14f)
        )
    } else {
        DyLikeColors(
            dark = false,
            background = Color(0xFFF3F0E9),
            surface = Color(0xFFFAF8F3),
            surfaceRaised = Color(0xFFFFFFFF),
            surfaceOverlay = Color(0xFFFFFFFF),
            hairline = Color(0xFF1E1B17).copy(alpha = .10f),
            textPrimary = Color(0xFF1E1B17),
            textSecondary = Color(0xFF5E5850),
            textTertiary = Color(0xFF6E675E),
            accent = accent,
            onAccent = onAccent,
            ambient = ambient,
            like = Color(0xFFC62E56),
            danmaku = Color(0xFF9C6D00),
            warning = Color(0xFFA85B00),
            info = Color(0xFF116E7A),
            success = Color(0xFF2E7D5B),
            buffered = Color(0xFF1E1B17).copy(alpha = .20f),
            track = Color(0xFF1E1B17).copy(alpha = .10f)
        )
    }
}

val LocalDyLikeColors = staticCompositionLocalOf<DyLikeColors> {
    error("DyLikeColors not provided; wrap content in DyLikeTheme")
}

val LocalPlayerColors = staticCompositionLocalOf<DyLikeColors> {
    error("LocalPlayerColors not provided; wrap content in DyLikeTheme")
}

val DyLikeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(9.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)