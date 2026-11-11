package me.lingci.lib.base.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

// --- 枚举与配置 ---
enum class EffectType { STAR, SNOW, METEOR }

/**
 * 粒子效果配置类
 * 所有参数都有默认值，方便按需设置
 */
data class ParticleConfig(
    val effectType: EffectType = EffectType.STAR,
    val densityFactor: Float = 0.5f,          // 密度 (0.0 - 1.0)
    val particleScale: Float = 1.0f,          // 大小缩放 (0.1 - 5.0)
    val decorativeStarCount: Int = 0,          // 大颗装饰星数量 (默认为0，不显示)
    /**
     * 自定义星星颜色列表
     * 算法会自动保证白色为主，列表中的颜色会离散分布在星空里
     * 如果颜色过暗，会自动提亮
     */
    val starColors: List<Int> = emptyList()
)

/**
 * 高性能粒子特效容器 ViewGroup
 * 继承自 FrameLayout，可直接作为布局根节点使用。
 * 背景绘制粒子效果，子 View 自动悬浮在特效之上。
 */
open class ParticleEffectLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // 当前配置，默认为 null，表示不绘制任何粒子
    private var config: ParticleConfig? = null

    // --- 内部变量 ---
    private val particles = mutableListOf<Particle>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lastTime = System.currentTimeMillis()

    // 颜色缓存
    private val colorWhite = Color.WHITE
    private val colorYellow = Color.parseColor("#FFFACD")
    private val colorBlue = Color.parseColor("#ADD8E6")

    init {
        // 保持默认的 willNotDraw=true：没有粒子时不参与绘制，由 setup()/clear() 负责开关。
        //
        // 不要在这里设置 LAYER_TYPE_HARDWARE。本容器是 SurfaceView 渲染视图的父容器，
        // Android 10 及更早的合成路径上，开了硬件层后 SurfaceView 的「打洞」只作用在
        // 本层 RenderNode 上，洞被清成透明后露出的是窗口背景而不是下层的视频 surface，
        // 表现为视频区域被一块不透明纯黑盖住（弹幕、控制栏正常）。
        //   - MIX 2S(Android 10) + MPV(gpu/gpu-next)：HARDWARE 必黑，NONE 正常
        //   - 2510DRK44C(Android 17) + 同上配置：两者都正常（BLAST 合成，父层不再遮挡）
        // 硬件层对粒子绘制没有收益（仍在 onDraw 里逐帧 invalidate），因此统一用 NONE。
        setLayerType(LAYER_TYPE_NONE, null)
    }

    /**
     * 【核心方法】启动或更新粒子效果
     * 传入配置后，View 开始绘制粒子
     */
    fun setup(config: ParticleConfig) {
        this.config = config

        // 1. 开启绘制功能
        setWillNotDraw(false)

        // 2. 初始化粒子
        initParticles()

        // 3. 触发重绘
        invalidate()
    }

    /**
     * 【核心方法】清除粒子效果
     * 恢复为普通 FrameLayout
     */
    fun clear() {
        this.config = null
        particles.clear()

        // 关闭绘制功能，恢复默认行为，不再触发 onDraw
        setWillNotDraw(true)

        // 最后一次重绘以清空画面
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸改变时，如果有配置，则重新初始化
        if (config != null) {
            initParticles()
        }
    }

    private fun initParticles() {
        particles.clear()

        // 安全检查：如果无配置或尺寸为0，直接返回
        val cfg = config ?: return
        if (width == 0 || height == 0) return

        val baseCount = (width * height / 10000f).toInt()
        val count = (baseCount * cfg.densityFactor * 2).toInt()

        when (cfg.effectType) {
            EffectType.STAR -> {
                repeat(count) { particles.add(StarParticle(width, height)) }
                if (cfg.decorativeStarCount > 0) {
                    repeat(cfg.decorativeStarCount) {
                        particles.add(
                            DecorativeStarParticle(
                                width,
                                height
                            )
                        )
                    }
                }
            }

            EffectType.SNOW -> {
                repeat(count) { particles.add(SnowParticle(width, height)) }
                repeat(count / 2) {
                    particles.add(
                        SnowParticle(
                            width,
                            height,
                            isBackground = true
                        )
                    )
                }
            }

            EffectType.METEOR -> {
                repeat(count / 2) { particles.add(StarParticle(width, height, isStatic = true)) }
                if (cfg.decorativeStarCount > 0) {
                    repeat(cfg.decorativeStarCount / 2) {
                        particles.add(
                            DecorativeStarParticle(
                                width,
                                height
                            )
                        )
                    }
                }
                // 【修改】流星数量极少：全屏只有 2 到 4 颗潜在流星
                // 它们大部分时间在“休眠”，只有醒了才会划过
                val meteorCount = Random.nextInt(2, 5)
                repeat(meteorCount) {
                    particles.add(MeteorParticle(width, height, isSparse = true))
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 如果没有配置，不执行任何绘制逻辑
        if (config == null) return

        val now = System.currentTimeMillis()
        val deltaTime = (now - lastTime) / 1000f
        lastTime = now

        // 获取缩放系数
        val scale = config?.particleScale ?: 1.0f

        particles.forEach { particle ->
            particle.advance(deltaTime)
            particle.draw(canvas, paint, scale)
        }

        if (isShown) {
            invalidate()
        }
    }

    // ------------------- 粒子定义 (修改了 draw 方法签名以支持全局缩放) -------------------

    private abstract class Particle {
        abstract fun advance(deltaTime: Float)

        // 增加 scale 参数
        abstract fun draw(canvas: Canvas, paint: Paint, scale: Float)
    }

    // 1. 小星星
    private inner class StarParticle(
        private val maxX: Int, private val maxY: Int, private val isStatic: Boolean = false
    ) : Particle() {
        var x = Random.nextFloat() * maxX;
        var y = Random.nextFloat() * maxY
        val radius = Random.nextFloat() * 2f + 0.5f
        var alpha = Random.nextInt(255);
        var alphaSpeed = Random.nextFloat() * 200 + 50
        //val color = when (Random.nextInt(10)) { 0 -> colorYellow; 1 -> colorBlue; else -> colorWhite }

        // 【修改】调用 getStarColor 获取颜色，默认 80% 白色，20% 自定义色
        val color = getStarColor(Color.WHITE, 0.5f)

        override fun advance(deltaTime: Float) {
            if (isStatic) return
            alpha += (alphaSpeed * deltaTime).toInt()
            if (alpha > 255 || alpha < 50) {
                alphaSpeed = -alphaSpeed; alpha = alpha.coerceIn(50, 255)
            }
        }

        override fun draw(canvas: Canvas, paint: Paint, scale: Float) {
            // 【修复】强制设置为填充模式，防止被其他粒子（如装饰星）污染
            paint.style = Paint.Style.FILL
            paint.color = color; paint.alpha = alpha
            canvas.drawCircle(x, y, radius * scale, paint)
        }
    }

    // 2. 雪花
    private inner class SnowParticle(
        private val maxX: Int, private val maxY: Int, private val isBackground: Boolean = false
    ) : Particle() {
        var x = Random.nextFloat() * maxX;
        var y = Random.nextFloat() * maxY
        val radius: Float =
            if (isBackground) Random.nextFloat() * 2 + 1 else Random.nextFloat() * 4 + 2
        val speed: Float =
            if (isBackground) Random.nextFloat() * 20 + 10 else Random.nextFloat() * 50 + 30
        var angle = Random.nextFloat() * 360f;
        val swingSpeed = Random.nextFloat() * 2f + 1f
        val swingAmplitude = Random.nextFloat() * 2f + 1f

        override fun advance(deltaTime: Float) {
            y += speed * deltaTime; angle += swingSpeed * deltaTime * 10
            x += sin(angle.toDouble()).toFloat() * swingAmplitude
            if (y > maxY) {
                y = -radius; x = Random.nextFloat() * maxX
            }
            if (x < 0) x = maxX.toFloat(); if (x > maxX) x = 0f
        }

        override fun draw(canvas: Canvas, paint: Paint, scale: Float) {
            paint.color = Color.WHITE; paint.alpha = if (isBackground) 100 else 200
            paint.style = Paint.Style.FILL; canvas.drawCircle(x, y, radius * scale, paint)
        }
    }

    /**
     * 流星粒子：支持长间隔休眠
     */
    private inner class MeteorParticle(
        private val maxX: Int, private val maxY: Int,
        private val isSparse: Boolean = false // 是否为稀疏模式（长间隔）
    ) : Particle() {

        // 基础属性
        var startX = Random.nextFloat() * maxX
        var startY = Random.nextFloat() * (maxY / 2)
        val length = Random.nextFloat() * 100 + 80 // 流星更长一点
        val speed = Random.nextFloat() * 600 + 400 // 速度更快

        val angle = Math.toRadians(45.0 + Random.nextDouble() * 20)
        val dx = cos(angle).toFloat()
        val dy = sin(angle).toFloat()

        var life = 0f
        val maxLife = Random.nextFloat() * 1.5f + 0.5f // 飞行持续时间

        // 【新增】休眠相关变量
        var isSleeping = true
        var sleepTime = 0f

        // 初始化时先睡一会
        init {
            if (isSparse) {
                // 开始时随机休眠 0-10秒，避免所有流星同时出现
                sleepTime = Random.nextFloat() * 10f
            }
        }

        override fun advance(deltaTime: Float) {
            // 1. 休眠逻辑
            if (isSleeping) {
                sleepTime -= deltaTime
                if (sleepTime <= 0) {
                    // 睡醒了，准备划过
                    wakeUp()
                }
                return // 睡觉中不移动
            }

            // 2. 飞行逻辑
            life += deltaTime
            startX += dx * speed * deltaTime
            startY += dy * speed * deltaTime

            // 3. 结束判定
            if (startX > maxX || startY > maxY || life > maxLife) {
                if (isSparse) {
                    // 稀疏模式：飞完后进入休眠
                    goToSleep()
                } else {
                    // 普通模式：立即重置（保留旧逻辑兼容）
                    resetPosition()
                }
            }
        }

        override fun draw(canvas: Canvas, paint: Paint, scale: Float) {
            // 休眠中不绘制
            if (isSleeping) return

            val lifeRatio = life / maxLife
            // 淡入淡出
            val alpha = when {
                lifeRatio < 0.1f -> lifeRatio * 10
                lifeRatio > 0.8f -> (1f - lifeRatio) * 5
                else -> 1f
            }

            val currentLength = length * scale
            val endX = startX - dx * currentLength
            val endY = startY - dy * currentLength

            val shader = LinearGradient(
                startX, startY, endX, endY,
                intArrayOf(Color.TRANSPARENT, Color.WHITE),
                null,
                Shader.TileMode.CLAMP
            )

            paint.shader = shader
            paint.alpha = (alpha * 255).toInt()
            paint.strokeWidth = 2.5f * scale // 流星稍微粗一点
            paint.style = Paint.Style.STROKE

            canvas.drawLine(startX, startY, endX, endY, paint)
            paint.shader = null
        }

        // --- 辅助方法 ---

        private fun wakeUp() {
            isSleeping = false
            life = 0f
            resetPosition()
        }

        private fun goToSleep() {
            isSleeping = true
            // 【关键】随机休眠 5秒 到 15秒
            sleepTime = Random.nextFloat() * 10f + 5f
        }

        private fun resetPosition() {
            startX = Random.nextFloat() * maxX
            startY = Random.nextFloat() * (maxY / 3) // 从上方出现
        }
    }

    /**
     * 高级装饰星：✨ 闪光效果
     * 特征：内凹菱形 + 细长十字光芒
     */
    private inner class DecorativeStarParticle(
        private val maxX: Int, private val maxY: Int
    ) : Particle() {

        var x = Random.nextFloat() * maxX
        var y = Random.nextFloat() * maxY
        var baseRadius = Random.nextFloat() * 6 + 4 // 基础半径
        var alpha = Random.nextInt(255)
        var alphaSpeed = Random.nextFloat() * 150 + 50
        val rotation = Random.nextFloat() * 45f // 随机旋转角度
        //val color = if (Random.nextBoolean()) Color.WHITE else Color.parseColor("#E0FFFF")
        // 稍微降低白色的比例，让装饰星的颜色更显眼一点
        val color = getStarColor(Color.WHITE, 0.4f) // 复用之前的颜色逻辑

        private val starPath = Path()

        override fun advance(deltaTime: Float) {
            alpha += (alphaSpeed * deltaTime).toInt()
            if (alpha > 255 || alpha < 100) {
                alphaSpeed = -alphaSpeed
                alpha = alpha.coerceIn(100, 255)
            }
        }

        override fun draw(canvas: Canvas, paint: Paint, scale: Float) {
            val currentRadius = baseRadius * scale

            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(rotation)

            // ============================
            // 第一层：内凹菱形 (核心星体)
            // ============================
            // 关键修正：depthFactor 必须 < 0.5 才能产生明显的内凹
            // 0.2f 会产生非常尖锐的内凹，像 ✨ 的样子
            val depthFactor = 0.2f
            val controlDist = currentRadius * depthFactor

            starPath.reset()
            starPath.moveTo(0f, -currentRadius) // 上顶点

            // 连接 上 -> 右 (内凹)
            // 控制点 (controlDist, -controlDist) 位于 (0,-R) 和 (R,0) 连线的内侧
            starPath.quadTo(controlDist, -controlDist, currentRadius, 0f)

            // 右 -> 下
            starPath.quadTo(controlDist, controlDist, 0f, currentRadius)

            // 下 -> 左
            starPath.quadTo(-controlDist, controlDist, -currentRadius, 0f)

            // 左 -> 上
            starPath.quadTo(-controlDist, -controlDist, 0f, -currentRadius)
            starPath.close()

            paint.style = Paint.Style.FILL
            paint.color = color

            // 1. 绘制光晕 (放大1.5倍)
            paint.alpha = (alpha * 0.3f).toInt()
            canvas.save()
            canvas.scale(1.5f, 1.5f)
            canvas.drawPath(starPath, paint)
            canvas.restore()

            // 2. 绘制核心实体
            paint.alpha = alpha
            canvas.drawPath(starPath, paint)


            // ============================
            // 第二层：十字光芒 (✨ 的灵魂)
            // ============================
            // 绘制一个细长的十字，模拟强光衍射
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND

            // 十字长度比菱形长一点，宽度很细
            val crossLen = currentRadius * 2.5f
            val crossWidth = currentRadius * 0.15f
            paint.strokeWidth = crossWidth

            // 只有垂直和水平两条线
            paint.alpha = (alpha * 0.6f).toInt()
            canvas.drawLine(0f, -crossLen, 0f, crossLen, paint) // 垂直
            canvas.drawLine(-crossLen, 0f, crossLen, 0f, paint) // 水平

            canvas.restore()
        }
    }

    /**
     * 获取星星颜色
     * coreColor: 默认核心颜色（通常是白色）
     * ratio: 核心颜色占比 (例如 0.7 表示70%概率是核心色)
     */
    private fun getStarColor(coreColor: Int, ratio: Float = 0.7f): Int {
        val cfg = config ?: return coreColor

        // 1. 没有自定义颜色，使用默认逻辑
        if (cfg.starColors.isEmpty()) {
            // 默认逻辑：偶尔出现黄色或蓝色
            return when (Random.nextInt(10)) {
                0 -> Color.parseColor("#FFFACD") // 淡黄
                1 -> Color.parseColor("#ADD8E6") // 淡蓝
                else -> coreColor
            }
        }

        // 2. 有自定义颜色，按比例分配
        return if (Random.nextFloat() < ratio) {
            coreColor // 70% 概率是白色（主色调）
        } else {
            // 30% 概率从用户列表中随机选色，并进行智能提亮
            val rawColor = cfg.starColors.random()
            ensureBrightness(rawColor)
        }
    }

    /**
     * 智能提亮算法
     * 如果颜色明度低于阈值，强制提亮
     */
    private fun ensureBrightness(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // hsv[2] 代表明度
        val minBrightness = 0.6f // 设定最低明度阈值 (0.0-1.0)

        if (hsv[2] < minBrightness) {
            hsv[2] = minBrightness // 强制提亮
            // 可选：降低饱和度让颜色更"粉"一点，更像星光而不是LED灯
            // hsv[1] = hsv[1] * 0.8f
            return Color.HSVToColor(hsv)
        }

        return color
    }


    fun star() {
        // 设置为星空模式，密度 80%
        setup(
            ParticleConfig(
                effectType = EffectType.STAR,
                densityFactor = 0.8f,
                particleScale = 1.2f,
                decorativeStarCount = 5 // 增加 5 颗大星星
            )
        )
    }

    fun snow() {
        // 设置为雪花模式，密度 80%
        setup(
            ParticleConfig(
                effectType = EffectType.SNOW,
                densityFactor = 1.0f
            )
        )
    }

    fun meteor() {
        // 设置为流星雨模式，密度 80%
        setup(
            ParticleConfig(
                effectType = EffectType.METEOR,
                densityFactor = 0.4f,
                particleScale = 1.8f,
                decorativeStarCount = 3 // 增加 5 颗大星星
            )
        )
    }

    @SuppressLint("UseKtx")
    fun starColor() {
        setup(
            ParticleConfig(
                effectType = EffectType.STAR,
                densityFactor = 0.8f,
                particleScale = 1.6f,
                decorativeStarCount = 5,
                starColors = listOf(
                    Color.parseColor("#FFC0CB"), // 粉红
                    Color.parseColor("#E6E6FA"), // 淡紫
                    Color.parseColor("#FFD700"), // 金色
                    Color.parseColor("#A359F6"), // 紫色
                    Color.parseColor("#0099FF"), // 蓝色
                )
            )
        )
    }

}