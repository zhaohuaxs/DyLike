package me.lingci.lib.base.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewbinding.ViewBinding
import me.lingci.lib.base.R

/**
 * 定义抽象适配器类，使用泛型 T 表示数据类型，VB 表示 ViewBinding 类型
 *
 * 扩展能力（用于替代 BaseRecyclerViewAdapterHelper/BRVAH）：
 * 1. 子 View 点击：[addChildClickViewIds] + [setOnItemChildClickListener]
 * 2. 加载更多：[setOnLoadMoreListener] + [loadMoreComplete] / [loadMoreEnd]，
 *    滚动接近底部自动触发（[isAutoLoadMore]），数据不满一屏也可自动触发（[isEnableLoadMoreIfNotFullPage]）
 *
 * 空视图不在适配器内实现（作为普通 item 无法保证全屏居中），
 * 请使用 RecyclerView 扩展 [me.lingci.lib.base.ui.setEmptyView]。
 */
abstract class BaseAdapter<T, VB : ViewBinding>(
    protected var dataList: MutableList<T> = mutableListOf()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        /** 条目类型：正常数据项 */
        private const val TYPE_ITEM = 0

        /** 条目类型：加载更多 Footer */
        private const val TYPE_LOAD_MORE_FOOTER = 1

        /** 滚动预加载偏移：距尾部还有 N 个条目时提前触发加载更多 */
        private const val PRE_LOAD_NUMBER = 1
    }

    protected var onItemClick: ((item: T, position: Int) -> Unit)? = null

    // ============ 子 View 点击相关 ============

    /** 注册了点击事件的子 View id 集合 */
    private val childClickViewIds = mutableSetOf<Int>()

    private var onItemChildClickListener: ((view: View, position: Int) -> Unit)? = null

    // ============ 加载更多相关（对齐 BRVAH LoadMoreModule）============

    /** 加载更多状态 */
    private enum class LoadMoreStatus {
        /** 默认：可触发 */
        DEFAULT,
        /** 加载中：Footer 显示"正在加载更多" */
        LOADING,
        /** 加载结束：Footer 显示"没有更多数据"，不再触发 */
        END
    }

    private var loadMoreStatus = LoadMoreStatus.DEFAULT

    /** 是否允许触发加载更多（对齐 loadMoreModule.isEnableLoadMore） */
    private var enableLoadMore = false

    /** 滚动到底部是否自动触发（对齐 loadMoreModule.isAutoLoadMore，默认 true） */
    var isAutoLoadMore: Boolean = true

    /** 数据不满一屏时是否自动触发（对齐 loadMoreModule.isEnableLoadMoreIfNotFullPage，默认 true） */
    var isEnableLoadMoreIfNotFullPage: Boolean = true

    private var onLoadMoreListener: (() -> Unit)? = null

    /** 持有关联的 RecyclerView，用于"数据不满一屏时自动加载"的布局检查 */
    private var attachedRecyclerView: RecyclerView? = null

    /** 滚动监听，用于滚动到底部自动加载更多 */
    private val loadMoreScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            super.onScrolled(recyclerView, dx, dy)
            autoLoadMore(recyclerView)
        }
    }

    /** 数据变更后检查"数据不满一屏时自动加载更多" */
    private val fullPageObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() {
            checkLoadMoreIfNotFullPage()
        }

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
            checkLoadMoreIfNotFullPage()
        }

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
            checkLoadMoreIfNotFullPage()
        }
    }

    init {
        registerAdapterDataObserver(fullPageObserver)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_LOAD_MORE_FOOTER -> LoadMoreViewHolder(inflater.inflate(R.layout.item_base_load_more, parent, false))
            else -> BaseViewHolder(createBinding(inflater, parent))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder.itemViewType) {
            TYPE_ITEM -> bindItemHolder(holder as BaseViewHolder<VB>)
            TYPE_LOAD_MORE_FOOTER -> bindLoadMoreFooter(holder)
        }
    }

    /** 绑定正常数据项 */
    private fun bindItemHolder(holder: BaseViewHolder<VB>) {
        val binding = holder.binding
        // 绑定子 View 点击事件（对齐 BRVAH addChildClickViewIds 行为）
        if (childClickViewIds.isNotEmpty() && onItemChildClickListener != null) {
            for (id in childClickViewIds) {
                binding.root.findViewById<View>(id)?.setOnClickListener { v ->
                    val pos = holder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onItemChildClickListener?.invoke(v, pos)
                }
            }
        }
        // 获取最新的适配器位置，避免position过期
        val currentPos = holder.bindingAdapterPosition
        // 边界检查，防止异步数据更新导致崩溃
        if (currentPos < 0 || currentPos >= dataList.size) return
        bindData(binding, dataList[currentPos], currentPos)
    }

    /** 绑定加载更多 Footer 文案 */
    private fun bindLoadMoreFooter(holder: RecyclerView.ViewHolder) {
        val textRes = if (loadMoreStatus == LoadMoreStatus.END) {
            R.string.base_load_more_end
        } else {
            R.string.base_load_more_loading
        }
        holder.itemView.findViewById<TextView>(R.id.tv_base_load_more_text)?.setText(textRes)
    }

    override fun getItemViewType(position: Int): Int {
        // Footer 固定在最后一个位置
        if (position >= dataList.size) return TYPE_LOAD_MORE_FOOTER
        return TYPE_ITEM
    }

    override fun getItemCount(): Int = dataList.size + loadMoreFooterCount()

    /** Footer 显示条件：加载中或已加载完，且数据不为空（数据为空时由 EmptyDecoration 绘制空视图，不显示 Footer） */
    private fun loadMoreFooterCount(): Int =
        if (dataList.isNotEmpty() &&
            (loadMoreStatus == LoadMoreStatus.LOADING || loadMoreStatus == LoadMoreStatus.END)
        ) 1 else 0

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
        recyclerView.addOnScrollListener(loadMoreScrollListener)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        recyclerView.removeOnScrollListener(loadMoreScrollListener)
        if (attachedRecyclerView === recyclerView) attachedRecyclerView = null
    }

    fun onItemClick(listener: (item: T, position: Int) -> Unit) {
        this.onItemClick = listener
    }

    protected abstract fun createBinding(inflater: LayoutInflater, parent: ViewGroup): VB
    protected abstract fun bindData(binding: VB, item: T, position: Int)

    // ============ 子 View 点击 API ============

    /** 注册需要响应点击的子 View id（对齐 BRVAH 的 addChildClickViewIds） */
    fun addChildClickViewIds(vararg viewIds: Int) {
        childClickViewIds.addAll(viewIds.toList())
    }

    /** 设置子 View 点击回调，回调参数为被点击的 View 和条目位置 */
    fun setOnItemChildClickListener(listener: (view: View, position: Int) -> Unit) {
        onItemChildClickListener = listener
    }

    // ============ 加载更多 API（对齐 BRVAH LoadMoreModule）============

    /** 设置加载更多回调，设置后即启用加载更多 */
    fun setOnLoadMoreListener(listener: () -> Unit) {
        onLoadMoreListener = listener
        enableLoadMore = true
    }

    /** 加载更多开关；重新开启时重置"已加载完"状态，允许继续加载（对齐下拉刷新后恢复加载更多的场景） */
    var isEnableLoadMore: Boolean
        get() = enableLoadMore
        set(value) {
            enableLoadMore = value
            if (value && loadMoreStatus == LoadMoreStatus.END) {
                loadMoreStatus = LoadMoreStatus.DEFAULT
            }
        }

    /** 加载完成，隐藏 Footer，恢复可触发状态 */
    fun loadMoreComplete() {
        val wasFooterShowing = loadMoreStatus == LoadMoreStatus.LOADING
        loadMoreStatus = LoadMoreStatus.DEFAULT
        if (wasFooterShowing && dataList.isNotEmpty()) {
            // Footer 位置等于当前 itemCount（状态已重置，不再计入 Footer）
            notifyItemRemoved(itemCount)
        }
        checkLoadMoreIfNotFullPage()
    }

    /** 没有更多数据，Footer 显示"没有更多数据"，不再触发 */
    fun loadMoreEnd() {
        val wasFooterShowing = loadMoreStatus == LoadMoreStatus.LOADING
        loadMoreStatus = LoadMoreStatus.END
        enableLoadMore = false
        // 数据为空时不显示 Footer（空视图由 EmptyDecoration 绘制），无需插入
        if (dataList.isEmpty()) return
        if (wasFooterShowing) {
            // Footer 已存在，仅切换文案
            notifyItemChanged(dataList.size)
        } else {
            // Footer 从无到有，插入到最后
            notifyItemInserted(dataList.size)
        }
    }

    /** 加载失败，隐藏 Footer，恢复可触发状态（下次滚动可再次触发） */
    fun loadMoreFail() {
        val wasFooterShowing = loadMoreStatus == LoadMoreStatus.LOADING
        loadMoreStatus = LoadMoreStatus.DEFAULT
        if (wasFooterShowing && dataList.isNotEmpty()) {
            notifyItemRemoved(itemCount)
        }
    }

    // --- 数据操作方法优化 ---
    fun dataEmpty(): Boolean = dataList.isEmpty()

    fun getItem(position: Int): T? {
        // 修复：越界时返回 null 或处理，避免直接抛出异常
        return if (position in 0 until dataList.size) dataList[position] else null
    }

    fun addItem(item: T) {
        val position = dataList.size
        dataList.add(item)
        notifyItemInserted(position)
    }

    fun addItem(item: T, position: Int) {
        // 允许添加到末尾
        if (position in 0..dataList.size) {
            dataList.add(position, item)
            notifyItemInserted(position)
        } else {
            addItem(item)
        }
    }

    fun addItems(items: List<T>) {
        if (items.isEmpty()) return
        val startPosition = dataList.size
        dataList.addAll(items)
        notifyItemRangeInserted(startPosition, items.size)
    }

    fun updateItem(item: T, position: Int) {
        if (position in 0 until dataList.size) {
            dataList[position] = item
            notifyItemChanged(position)
        }
    }

    fun updateData(newData: List<T>) {
        dataList.clear()
        dataList.addAll(newData)
        notifyAllChanged()
    }

    fun removeItem(position: Int) {
        if (position in 0 until dataList.size) {
            dataList.removeAt(position)
            notifyItemRemoved(position)
            // 优化：移除后刷新后续位置，避免索引错乱
            notifyItemRangeChanged(position, dataList.size - position)
        }
    }

    fun removeEnd(position: Int) {
        if (position >= 0 && position < dataList.size - 1) {
            val size = dataList.size
            // subList 操作是原列表的视图，clear 会影响原列表
            dataList.subList(position + 1, size).clear()
            notifyItemRangeRemoved(position + 1, size - (position + 1))
        }
    }

    fun clearData(isRange: Boolean = false) {
        if (isRange) {
            val size = dataList.size
            dataList.clear()
            notifyItemRangeRemoved(0, size)
        } else {
            dataList.clear()
            notifyAllChanged()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun notifyAllChanged() {
        notifyDataSetChanged()
    }

    fun getData(): List<T> = dataList.toList()

    /** 滚动接近底部时自动触发加载更多（对齐 BRVAH isAutoLoadMore） */
    private fun autoLoadMore(recyclerView: RecyclerView) {
        if (!enableLoadMore || !isAutoLoadMore) return
        if (loadMoreStatus != LoadMoreStatus.DEFAULT) return
        if (dataList.isEmpty()) return
        val lastVisible = findLastVisibleItemPosition(recyclerView.layoutManager) ?: return
        if (lastVisible + PRE_LOAD_NUMBER >= itemCount - 1) {
            triggerLoadMore()
        }
    }

    /** 数据不满一屏时自动触发加载更多（对齐 BRVAH isEnableLoadMoreIfNotFullPage） */
    private fun checkLoadMoreIfNotFullPage() {
        val rv = attachedRecyclerView ?: return
        if (!isEnableLoadMoreIfNotFullPage || !enableLoadMore) return
        if (loadMoreStatus != LoadMoreStatus.DEFAULT) return
        if (dataList.isEmpty()) return
        // 延迟到本次布局完成后检查，避免布局未完成时判断不准
        rv.post {
            if (loadMoreStatus != LoadMoreStatus.DEFAULT) return@post
            if (!rv.isAttachedToWindow) return@post
            // 两个方向都不可滚动，说明数据不满一屏
            if (!rv.canScrollVertically(1) && !rv.canScrollHorizontally(1)) {
                triggerLoadMore()
            }
        }
    }

    /** 触发一次加载更多：插入 Footer 并回调监听 */
    private fun triggerLoadMore() {
        loadMoreStatus = LoadMoreStatus.LOADING
        // Footer 从无到有，插入到最后
        notifyItemInserted(dataList.size)
        onLoadMoreListener?.invoke()
    }

    /** 获取最后一个可见条目的位置，不支持的 LayoutManager 返回 null */
    private fun findLastVisibleItemPosition(layoutManager: RecyclerView.LayoutManager?): Int? {
        return when (layoutManager) {
            is LinearLayoutManager -> layoutManager.findLastVisibleItemPosition()
            is StaggeredGridLayoutManager -> layoutManager.findLastVisibleItemPositions(null).maxOrNull()
            else -> null
        }
    }

    /** 加载更多 Footer Holder */
    class LoadMoreViewHolder(view: View) : RecyclerView.ViewHolder(view)

    class BaseViewHolder<VB : ViewBinding>(
        val binding: VB
    ) : RecyclerView.ViewHolder(binding.root)

}
