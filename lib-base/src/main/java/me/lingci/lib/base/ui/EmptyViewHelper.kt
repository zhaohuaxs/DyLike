package me.lingci.lib.base.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.LayoutRes
import androidx.recyclerview.widget.RecyclerView
import me.lingci.lib.base.R

/**
 * 空数据布局装饰器
 * 修复：使用 ItemDecoration 替代 addView，解决 CoordinatorLayout/ViewHolder NPE 问题
 * 特性：
 * 1. 兼容 LinearLayout/Grid/StaggeredGrid
 * 2. 自动监听数据切换
 * 3. 不影响 Item 动画和点击
 */
class EmptyDecoration(
    private val context: Context,
    @LayoutRes private val layoutResId: Int = 0,
    private val emptyText: String = "",
    private val onEmptyClick: (() -> Unit)? = null
) : RecyclerView.ItemDecoration() {

    private var emptyView: View? = null
    private val bounds = Rect()

    override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(canvas, parent, state)

        val adapter = parent.adapter ?: return
        val isEmpty = adapter.itemCount == 0

        if (isEmpty) {
            // 获取或创建空布局
            val view = getOrCreateEmptyView(parent)

            // 测量与布局 View
            // 关键：使用 parent 的宽高进行测量，确保在 Grid/Staggered 中也能全屏居中
            val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(parent.height, View.MeasureSpec.EXACTLY)
            view.measure(widthSpec, heightSpec)

            // 居中布局
            view.layout(0, 0, parent.width, parent.height)

            // 将 View 绘制到 Canvas 上
            view.draw(canvas)
        }
    }

    private fun getOrCreateEmptyView(parent: RecyclerView): View {
        if (emptyView == null) {
            var default = true
            emptyView = if (layoutResId != 0) {
                default = false
                LayoutInflater.from(context).inflate(layoutResId, parent, false)
            } else {
                LayoutInflater.from(context).inflate(R.layout.layout_empty, parent, false)
            }
            if (default) {
                emptyView?.findViewById<TextView>(R.id.tv_empty)?.text = emptyText
            }

            // 处理点击事件
            onEmptyClick?.let { click ->
                emptyView?.setOnClickListener { click() }
            }

            // 如果是自定义布局，确保 LayoutParams 正确
            emptyView?.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = Gravity.CENTER }
        }
        return emptyView!!
    }
}

/**
 * 扩展函数：设置空布局
 * 只有调用此方法才会启用功能
 *
 * 使用要求：
 * 1. 必须在 `recyclerView.adapter` 赋值之后调用，否则数据观察者无法注册
 * 2. 触发条件为 `adapter.itemCount == 0`，因此不能再同时使用适配器内的空视图逻辑
 *    （BaseAdapter 的 `setEmptyView` 已移除）
 * 3. 空布局会被按 RecyclerView 的宽高精确测量并绘制在 (0,0)，内部只需写 `gravity="center"` 即可全屏居中
 * 4. [onEmptyClick] 目前不会生效：空视图是绘制在 Canvas 上的，没有挂载进视图层级，收不到触摸事件
 */
fun RecyclerView.setEmptyView(
    @LayoutRes emptyLayoutRes: Int = 0,
    emptyText: String = "",
    onEmptyClick: (() -> Unit)? = null
) {
    // 查找是否已存在旧 Decoration，防止重复添加
    val existingDecoration = (0 until itemDecorationCount)
        .map { getItemDecorationAt(it) }
        .firstOrNull { it is EmptyDecoration }

    // 如果存在且配置一致，直接返回；否则移除旧的
    if (existingDecoration != null) {
        removeItemDecoration(existingDecoration)
    }

    // 添加新的 Decoration
    val decoration = EmptyDecoration(context, emptyLayoutRes, emptyText, onEmptyClick)
    addItemDecoration(decoration)

    // 关键：注册数据观察者，当数据变化时触发重绘
    // 注意：这里使用了 tag 保存 observer 引用，防止内存泄漏或重复注册
    val tagKey = R.id.tag_empty_view_observer
    val oldObserver = getTag(tagKey) as? RecyclerView.AdapterDataObserver

    // 安全移除旧观察者
    oldObserver?.let {
        adapter?.unregisterAdapterDataObserver(it)
    }

    val newObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            decoration.onDraw(Canvas(), this@setEmptyView, RecyclerView.State())
            // 强制请求重绘，确保 onDraw 被调用
            invalidate()
        }
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            invalidate()
        }
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            invalidate()
        }
    }

    adapter?.registerAdapterDataObserver(newObserver)
    setTag(tagKey, newObserver)

    // 立即刷新一次
    invalidate()

}