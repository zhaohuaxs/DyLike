package me.lingci.dy.player.ui.compose.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 应用图标。
 *
 * 不能直接用 `painterResource(R.mipmap.ic_launcher)`：API 26+ 上它解析成
 * `mipmap-anydpi-v26/ic_launcher.xml`（adaptive-icon），既不是 VectorDrawable 也不是位图，
 * Compose 会抛 `IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported`。
 * 这里改成把任意 Drawable（含 AdaptiveIconDrawable）画进 Bitmap 再交给画家。
 */
@Composable
fun AppIconImage(
    @DrawableRes resId: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 按固定像素栅格化：图标尺寸在这两处都是固定 dp，不需要随测量尺寸反复重绘
    val px = with(density) { IconRasterSize.roundToPx() }
    val painter = remember(context, resId, px) { loadIconPainter(context, resId, px) }
    Image(painter = painter, contentDescription = contentDescription, modifier = modifier)
}

private val IconRasterSize: Dp = 96.dp

private fun loadIconPainter(context: Context, @DrawableRes resId: Int, sizePx: Int): Painter {
    val drawable = runCatching { AppCompatResources.getDrawable(context, resId) }.getOrNull()
        ?: return ColorPainter(Color.Transparent)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(Canvas(bitmap))
    return BitmapPainter(bitmap.asImageBitmap())
}
