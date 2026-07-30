package me.lingci.lib.player.widget.videoview

import android.content.Context
import android.util.AttributeSet
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.view.EffectType
import me.lingci.lib.player.widget.linstener.VideoScaleListener
import xyz.doikki.videoplayer.player.AbstractPlayer
import xyz.doikki.videoplayer.player.BaseVideoView

/**
 *   @author : happyc
 *   time    : 2025/03/23
 *   desc    : UI-only VideoView extension. Player core selection is owned by app modules.
 *   version : 1.0
 */
class CustomVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : BaseVideoView<AbstractPlayer>(context, attrs, defStyleAttr), VideoScaleListener {

    private var onPlayerInitializedListener: ((AbstractPlayer) -> Unit)? = null

    fun changeSysBar(show: Boolean) {
        decorView?.let {
            if (show) {
                showSysBar(it)
            } else {
                hideSysBar(it)
            }
        }
    }

    fun getCurrentPlayer(): AbstractPlayer? {
        return mMediaPlayer
    }

    fun hasPlayer(): Boolean {
        return getCurrentPlayer() != null
    }

    fun <T : Any> getPlayerCapability(clazz: Class<T>): T? {
        // Optional interface lookup keeps player-ui free of concrete backend imports while still letting
        // app modules consume capabilities implemented by the current backend.
        val player = getCurrentPlayer() ?: return null
        return if (clazz.isInstance(player)) clazz.cast(player) else null
    }

    fun setOnPlayerInitializedListener(listener: ((AbstractPlayer) -> Unit)?) {
        // Backends are recreated by BaseVideoView. App modules use this hook to reattach optional
        // capability listeners after a new player instance is created.
        onPlayerInitializedListener = listener
        getCurrentPlayer()?.let { player ->
            listener?.invoke(player)
        }
    }

    // 让上层手势逻辑只操作真实渲染层，避免把 controller 和字幕层一起变换。
    fun getRenderTransformView() = mRenderView?.view

    override fun initPlayer() {
        super.initPlayer()
        // Notify after BaseVideoView creates the concrete backend so callers never need reflection or
        // direct access to mMediaPlayer lifecycle internals.
        getCurrentPlayer()?.let { player ->
            onPlayerInitializedListener?.invoke(player)
        }
    }

    override fun setUrl(url: String?) {
        super.setUrl(url)
        scaleReset()
    }

    override fun setUrl(url: String?, headers: MutableMap<String, String>?) {
        //headers?.put("User-Agent", HttpUtil.EDGE_UA)
        super.setUrl(url, headers)
        scaleReset()
    }

    override fun release() {
        scaleReset()
        super.release()
    }

    override fun onScale(scale: Float) {
        mRenderView?.view?.let { view ->
            Log.d(this@CustomVideoView, "onScale", view.scaleX, view.scaleY, "y", view.translationY)
            view.scaleX = scale
            view.scaleY = scale
        }
    }

    private fun scaleReset() {
        mRenderView?.view?.let { view ->
            // 切源、释放和复用播放器时都回到初始几何状态，避免把上一个短视频的变换带到下一个。
            view.scaleX = 1f
            view.scaleY = 1f
            view.translationX = 0f
            view.translationY = 0f
        }
    }

    override fun onScroll(delta: Float, max: Float, type: Int) {
        onScroll1(delta, max, type)
    }

    fun onScroll1(delta: Float, max: Float, type: Int) {
        mRenderView?.view?.let { view ->
            var newPoint = ((if (type == 1) view.translationY else view.translationX) - delta) / 4
            newPoint = (-max).coerceAtLeast(newPoint.coerceAtMost(max))
            if (type == 1) {
                view.translationY = newPoint
            } else {
                view.translationX = newPoint
            }
        }
    }

    fun onScroll2(delta: Float, max: Float, type: Int) {
        mRenderView?.view?.let { v ->
            // 1. 基础数据准备
            if (v.width == 0 || v.height == 0) return@let
            if (mPlayerContainer.width == 0 || mPlayerContainer.height == 0) return@let

            val displayMetrics = v.context.resources.displayMetrics
            val screenWidth = displayMetrics.widthPixels.toFloat()
            val screenHeight = displayMetrics.heightPixels.toFloat()

            // 获取当前缩放比例
            val scaleX = v.scaleX
            val scaleY = v.scaleY

            // 计算缩放后的实际尺寸
            val scaledWidth = v.width * scaleX
            val scaledHeight = v.height * scaleY

            val parentWidth = mPlayerContainer.width.toFloat()
            val parentHeight = mPlayerContainer.height.toFloat()

            // 确定当前操作的轴 (0: X, 1: Y)
            val isVertical = (type == 1)

            // 提取变量以简化逻辑
            val currentPos = if (isVertical) v.top.toFloat() else v.left.toFloat()
            val viewSize = if (isVertical) v.height.toFloat() else v.width.toFloat()
            val scaledSize = if (isVertical) scaledHeight else scaledWidth
            val parentSize = if (isVertical) parentHeight else parentWidth
            val currentTranslation = if (isVertical) v.translationY else v.translationX

            // 2. 计算新的目标偏移量 (应用阻尼 /4)
            val targetTranslation = ((currentTranslation) - delta) / 4f

            // 3. 核心逻辑：动态计算边界
            var minTranslation: Float
            var maxTranslation: Float

            if (scaledSize > parentSize) {
                // --- 情况 A: 缩放后比容器大 (允许移出边界，类似地图模式) ---
                // 逻辑：允许用户拖动查看图片的各个部分。
                // 极限位置1 (最左/最上): 图片的右/下边缘 对齐 容器的右/下边缘
                // 此时 translation = parentSize - scaledSize (这是一个负数)
                minTranslation = parentSize - scaledSize

                // 极限位置2 (最右/最下): 图片的左/上边缘 对齐 容器的左/上边缘
                // 假设初始布局是 left/top = 0，那么最大 translation 为 0
                // 但如果初始布局有 margin 或居中，这里需要加上 currentPos 的偏移补偿吗？
                // 通常 translation 是相对于 layout position 的。
                // 如果 layout position (left) 已经是 0，那么 max 就是 0。
                // 如果 layout position (left) 是 (parentWidth - viewWidth)/2 (居中)，那么逻辑会变复杂。

                // 更通用的写法：基于 "最终绝对位置" 来反推 translation 的限制
                // 绝对位置 = currentPos + translation
                // 限制绝对位置的范围：
                // 最小绝对位置：parentSize - scaledSize (让尾部对齐)
                // 最大绝对位置：0 (让头部对齐)

                // 对应的 translation 范围：
                // minTrans = (parentSize - scaledSize) - currentPos
                // maxTrans = 0 - currentPos

                minTranslation = (parentSize - scaledSize) - currentPos
                maxTranslation = -currentPos

            } else {
                // --- 情况 B: 缩放后比容器小 (限制在容器内，防止留白) ---
                // 逻辑：图片不能移出容器，必须完全可见。

                // 最小绝对位置：0 (左/上边缘对齐容器左/上)
                // 最大绝对位置：parentSize - scaledSize (右/下边缘对齐容器右/下)

                // 对应的 translation 范围：
                // minTrans = 0 - currentPos
                // maxTrans = (parentSize - scaledSize) - currentPos

                minTranslation = -currentPos
                maxTranslation = (parentSize - scaledSize) - currentPos
            }

            // 4. 应用限制 (Clamp)
            // 注意：如果 scaledSize > parentSize，minTranslation 是负数，maxTranslation 可能是 0 或正数，区间有效。
            // 如果 scaledSize <= parentSize，minTranslation 是负数，maxTranslation 也是负数(如果居中)或0，区间有效。

            // 特殊情况保护：如果计算出错导致 min > max (例如尺寸为0)，交换一下或直接不设限
            if (minTranslation > maxTranslation) {
                val temp = minTranslation
                minTranslation = maxTranslation
                maxTranslation = temp
            }

            val finalTranslation = targetTranslation.coerceIn(minTranslation, maxTranslation)

            // 5. 赋值
            if (isVertical) {
                v.translationY = finalTranslation
            } else {
                v.translationX = finalTranslation
            }
        }
    }

    override fun onEffectChange(type: EffectType?) {
        when (type) {
            EffectType.STAR -> mPlayerContainer.starColor()
            EffectType.SNOW -> mPlayerContainer.snow()
            EffectType.METEOR -> mPlayerContainer.meteor()
            else -> mPlayerContainer.clear()
        }
    }

    /**
     * 取出 player 实例供后台 Service 接管。
     * @return 当前 player，或 null(无 player)
     */
    fun detachPlayer(): AbstractPlayer? = detachPlayerForBackground()

    /**
     * 从后台 Service 取回 player 恢复显示。
     */
    fun attachPlayer(player: AbstractPlayer?) {
        if (player != null) attachPlayerFromBackground(player)
    }

}
