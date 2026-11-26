package me.lingci.lib.dm.view.widget

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import kotlin.math.ceil
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.android.SimpleTextCacheStuffer
import me.lingci.lib.dm.view.entity.DmStyleExtend

open class GradientViewCacheStuffer(
    private val strokeMultiple: Float = 1.2f
) : SimpleTextCacheStuffer() {

    private val defaultColors = intArrayOf(Color.RED, Color.MAGENTA, Color.BLUE)
    private val defaultColorsHash = defaultColors.contentHashCode()
    private val strokePaintLocal = ThreadLocal.withInitial {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    }
    private val strokePaint get() = strokePaintLocal.get()!!
    private val cacheLock = Any()
    private val positionCache = HashMap<Int, FloatArray>()
    private val shaderCache = object : LinkedHashMap<ShaderKey, LinearGradient>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ShaderKey, LinearGradient>?): Boolean {
            return size > MAX_SHADER_CACHE_SIZE
        }
    }

    override fun drawText(
        danmaku: BaseDanmaku?,
        lineText: String?,
        canvas: Canvas?,
        left: Float,
        top: Float,
        paint: TextPaint?,
        fromWorkerThread: Boolean
    ) {
        val item = danmaku ?: return
        val textPaint = paint ?: return
        val extend = item.tag as? DmStyleExtend
        if (extend == null) {
            super.drawText(item, lineText, canvas, left, top, textPaint, fromWorkerThread)
            return
        }
        if (extend.strokeMode) {
            super.drawText(item, lineText, canvas, left, top, textPaint, fromWorkerThread)
            return
        }
        val drawCanvas = canvas ?: return
        val colors = resolveGradientColors(extend)
        val textWidth = textPaint.measureText(lineText ?: item.text?.toString() ?: "")
        textPaint.shader = buildShader(colors, resolveGradientHash(extend, colors), textWidth)
        drawCanvas.save()
        drawCanvas.translate(left, top)
        try {
            super.drawText(item, lineText, drawCanvas, 0f, 0f, textPaint, fromWorkerThread)
        } finally {
            textPaint.shader = null
            drawCanvas.restore()
        }
    }

    override fun drawStroke(
        danmaku: BaseDanmaku?,
        lineText: String?,
        canvas: Canvas?,
        left: Float,
        top: Float,
        paint: Paint?
    ) {
        val item = danmaku ?: return
        val sourcePaint = paint ?: return
        val extend = item.tag as? DmStyleExtend
        if (extend == null || !extend.strokeMode) {
            super.drawStroke(item, lineText, canvas, left, top, sourcePaint)
            return
        }

        val drawCanvas = canvas ?: return
        val colors = resolveGradientColors(extend)
        val gradientHash = resolveGradientHash(extend, colors)
        val textWidth = sourcePaint.measureText(lineText ?: item.text?.toString() ?: "")
        prepareStrokePaint(sourcePaint)
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = sourcePaint.strokeWidth * strokeMultiple
        strokePaint.shader = buildShader(colors, gradientHash, textWidth)
        strokePaint.xfermode = null
        drawCanvas.save()
        drawCanvas.translate(left, top)
        try {
            super.drawStroke(item, lineText, drawCanvas, 0f, 0f, strokePaint)
        } finally {
            strokePaint.shader = null
            strokePaint.xfermode = null
            drawCanvas.restore()
        }
    }

    override fun clearCaches() {
        super.clearCaches()
        synchronized(cacheLock) {
            positionCache.clear()
            shaderCache.clear()
        }
    }

    private fun prepareStrokePaint(source: Paint) {
        strokePaint.set(source)
        strokePaint.shader = null
        strokePaint.xfermode = null
        strokePaint.isAntiAlias = true
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.strokeCap = Paint.Cap.ROUND
    }

    private fun resolveGradientColors(extend: DmStyleExtend): IntArray {
        val colors = extend.gradientColorArray()
        return if (colors == null || colors.size < 2) defaultColors else colors
    }

    private fun resolveGradientHash(extend: DmStyleExtend, colors: IntArray): Int {
        return if (colors === defaultColors) defaultColorsHash else extend.gradientHash() ?: defaultColorsHash
    }

    private fun buildShader(colors: IntArray, colorsHash: Int, width: Float): LinearGradient {
        val widthPx = maxOf(1, ceil(width.toDouble()).toInt())
        val key = ShaderKey(widthPx, colorsHash, colors.size)
        synchronized(cacheLock) {
            shaderCache[key]?.let { return it }
        }
        val shader = LinearGradient(
            0f,
            0f,
            widthPx.toFloat(),
            0f,
            colors,
            getPositions(colors.size),
            Shader.TileMode.CLAMP
        )
        synchronized(cacheLock) {
            shaderCache[key] = shader
        }
        return shader
    }

    private fun getPositions(segments: Int): FloatArray {
        synchronized(cacheLock) {
            positionCache[segments]?.let { return it }
            return generateArray(segments).also {
                positionCache[segments] = it
            }
        }
    }

    private fun generateArray(segments: Int): FloatArray {
        if (segments <= 2) {
            return floatArrayOf(0.2f, 0.8f)
        }
        val array = FloatArray(segments)
        array[0] = 0.2f
        for (i in 1 until segments - 1) {
            array[i] = i.toFloat() / (segments - 1)
        }
        array[segments - 1] = 0.8f
        return array
    }

    private data class ShaderKey(
        val widthPx: Int,
        val colorsHash: Int,
        val colorCount: Int
    )

    companion object {
        private const val MAX_SHADER_CACHE_SIZE = 32
    }

}
