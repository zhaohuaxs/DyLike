package me.lingci.dy.player.ui.compose.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.util.loadImage

/**
 * 本地缩略图的内存缓存 + Composable 呈现。
 *
 * 为什么不用 AndroidView + Glide：
 * - 列表里每个卡片一个真实 View 就要多一层子组合（subcomposition），十几张卡片首帧叠加起来很可观；
 * - Glide 是异步的，首帧只能画占位图，滚回来也要重新走一遍请求；
 * - 这里把「已解码的 ImageBitmap」放进 LruCache，命中时 [ThumbImage] 在**第一次组合**就拿到终态，
 *   卡片尺寸不会因为图片晚到而变化——瀑布流就不会因为换图触发整体重排。
 *
 * 解码上限按卡片实际显示宽度取（3 列手机屏每列约 360px），避免把 1080p 封面整张塞进堆。
 */
object ThumbCache {

    private const val MAX_WIDTH = 384

    private val bitmaps = object : LruCache<String, ImageBitmap>(bitmapBudget()) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            value.width * value.height * 4
    }

    // 比例值只是 Float，容量按千级视频库给足，滚动一圈不会把已算过的比例挤掉
    private val ratios = LruCache<String, Float>(1024)

    private fun bitmapBudget(): Int = (Runtime.getRuntime().maxMemory() / 8)
        .coerceIn(8L * 1024 * 1024, 32L * 1024 * 1024)
        .toInt()

    fun cached(path: String): ImageBitmap? = bitmaps.get(path)

    suspend fun load(path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        bitmaps.get(path)?.let { return@withContext it }
        decode(path)?.also { bitmaps.put(path, it) }
    }

    /**
     * 只读文件头拿宽高比，不整张解码；结果按路径缓存，重复进入页面不再碰磁盘。
     * 网络地址没有本地文件可读，直接返回 [fallback]。
     */
    fun aspectRatio(path: String?, fallback: Float = DEFAULT_ASPECT): Float {
        if (path.isNullOrBlank() || !path.startsWith("/")) return fallback
        ratios.get(path)?.let { return it }
        val ratio = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.4f, 3f) else fallback
        } catch (t: Throwable) {
            fallback
        }
        ratios.put(path, ratio)
        return ratio
    }

    private fun decode(path: String): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val width = bounds.outWidth
        if (width <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (width / (sample * 2) >= MAX_WIDTH) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, options)?.let { bitmap ->
                if (bitmap.width > MAX_WIDTH) {
                    val height = (bitmap.height * (MAX_WIDTH.toFloat() / bitmap.width))
                        .toInt()
                        .coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bitmap, MAX_WIDTH, height, true)
                        .also { if (it !== bitmap) bitmap.recycle() }
                } else {
                    bitmap
                }.asImageBitmap()
            }
        }
    } catch (t: Throwable) {
        null
    }

    /** 短视频卡片没有缩略图时的兜底比例：竖版，避免一排全被拉成 16:9。 */
    const val DEFAULT_ASPECT = 3f / 4f
}

/**
 * 按路径展示缩略图；[path] 为空或解码失败时交给 [placeholder]。
 *
 * - 本地绝对路径：走 [ThumbCache]，首帧即可命中，尺寸由外部 Modifier 决定（一般配 aspectRatio），
 *   图片晚到不会改变布局尺寸；
 * - http(s) 封面（WebDAV / 在线库的 preview）：内存缓存只认本地文件，仍交给 Glide，
 *   [headers] 用于带上 WebDAV 的认证头。
 */
@Composable
fun ThumbImage(
    path: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    headers: Map<String, String> = emptyMap(),
    placeholder: @Composable BoxScope.() -> Unit = {}
) {
    if (path != null && !path.startsWith("/")) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            },
            update = { it.loadImage(path, headers) },
            modifier = modifier
        )
        return
    }

    // 初始值直接取缓存：命中时首帧就是终态，不闪占位图
    var image by remember(path) { mutableStateOf(path?.let { ThumbCache.cached(it) }) }
    LaunchedEffect(path) {
        if (path != null && image == null) {
            image = ThumbCache.load(path)
        }
    }
    Box(modifier) {
        val bitmap = image
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.matchParentSize()
            )
        } else {
            placeholder()
        }
    }
}
