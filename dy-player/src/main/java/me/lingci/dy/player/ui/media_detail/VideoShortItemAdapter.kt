package me.lingci.dy.player.ui.media_detail

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.DiffUtil
import com.bumptech.glide.Glide
import android.view.View
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ItemVideoShortListBinding
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.AppUtil
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseAdapter
import java.io.File

class VideoShortItemAdapter(
    private val dataSet: MutableList<VideoData>
) : BaseAdapter<VideoData, ItemVideoShortListBinding>(dataSet) {

    companion object {
        // 缩略图宽高比缓存：key 为 videoUrl 的 md5，value 为宽高比字符串
        // 避免每次 bindData 都通过 BitmapFactory 解码图片尺寸造成 IO 开销
        private val ratioCache = mutableMapOf<String, String>()
    }

    private var onLongItemClick: ((v: View, item: VideoData, position: Int) -> Unit)? = null
    private var batchMode = false

    override fun createBinding(
        inflater: LayoutInflater,
        parent: ViewGroup
    ): ItemVideoShortListBinding {
        return ItemVideoShortListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    override fun bindData(
        binding: ItemVideoShortListBinding,
        item: VideoData,
        position: Int
    ) {
        // key 必须与 MediaManger.scanVideoThumb 一致（那边用的是 VideoData.md5()，
        // 有条目 id 时返回 id 而不是 videoUrl 的 md5），否则生成的缩略图永远对不上文件名
        val cacheKey = item.md5()
        File(
            binding.ivThumb.context.externalCacheDir,
            ".thumb/${cacheKey}.${AppUtil.THUMB_TYPE}"
        ).let { thumbFile ->
            if (thumbFile.exists()) {
                val params: ConstraintLayout.LayoutParams = binding.ivThumb.layoutParams as ConstraintLayout.LayoutParams
                // 优先从缓存获取宽高比，避免重复解码图片
                val cachedRatio = ratioCache[cacheKey]
                if (cachedRatio != null) {
                    params.dimensionRatio = cachedRatio
                } else {
                    try {
                        val wh = getWh(thumbFile.path)
                        val width = wh.first
                        val height = wh.second
                        val ratio = if (width > 0 && height > 0 && height > width) "${width}:${height}" else "1:1"
                        params.dimensionRatio = ratio
                        ratioCache[cacheKey] = ratio
                    } catch (e: Exception) {
                        params.dimensionRatio = "3:4"
                    }
                }
                binding.ivThumb.layoutParams = params
                // 保留当前图片作为占位，避免刷新闪烁；drawable 可能为 null（首次加载时）
                val request = Glide.with(binding.ivThumb.context).load(thumbFile)
                binding.ivThumb.drawable?.let { request.placeholder(it) }
                request.into(binding.ivThumb)
            } else {
                loadDefault(binding)
            }
        }
        binding.tvTitle.text = item.name
        binding.viewBorder.visibility = if (item.lastPlay && !batchMode) View.VISIBLE else View.GONE
        changeSelect(binding, item)
        binding.root.setOnClickListener {
            if (position < dataSet.size) {
                if (batchMode) {
                    item.selected = !item.selected
                    changeSelect(binding, item)
                } else {
                    onItemClick?.invoke(item, position)
                }
            }
        }
        binding.root.setOnLongClickListener {
            if (position < dataSet.size && !batchMode) {
                onLongItemClick?.invoke(it, item, position)
                return@setOnLongClickListener true
            }
            false
        }
    }

    private fun changeSelect(binding: ItemVideoShortListBinding, item: VideoData) {
        binding.ivSelect.visibility = if (batchMode && item.selected) View.VISIBLE else View.GONE
        val targetAlpha = if (batchMode && item.selected) 0.6f else 1f
        binding.ivThumb.alpha = targetAlpha
    }

    fun loadDefault(binding: ItemVideoShortListBinding) {
        binding.ivThumb.setImageResource(R.drawable.ic_video_default)
        val params: ConstraintLayout.LayoutParams = binding.ivThumb.layoutParams as ConstraintLayout.LayoutParams
        params.dimensionRatio = "3:4"
        binding.ivThumb.layoutParams = params
    }

    fun getWh(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, options)
        val width = options.outWidth
        val height = options.outHeight
        return Pair(width, height)
    }

    fun onLongItemClick(onLongItemClick: (v: View, item: VideoData, position: Int) -> Unit) {
        this.onLongItemClick = onLongItemClick
    }

    fun getBatchMode(): Boolean = batchMode

    fun batchMode(position: Int) {
        batchMode = true
        if (position in dataSet.indices) {
            dataSet[position].selected = true
        }
        notifyAllChanged()
    }

    fun exitBatchMode() {
        batchMode = false
        dataSet.forEach { it.selected = false }
        notifyAllChanged()
    }

    fun selectAll() {
        dataSet.forEach { it.selected = true }
        notifyAllChanged()
    }

    fun selectInvert() {
        dataSet.forEach { it.selected = !it.selected }
        notifyAllChanged()
    }

    fun listSelect(): List<VideoData> {
        return dataList.filter { it.selected }
    }

    fun removeSelect() {
        dataSet.removeIf { it.selected }
        batchMode = false
        notifyAllChanged()
    }

    /**
     * 使用 DiffUtil 增量更新数据，替代 BaseAdapter 的全量 notifyDataSetChanged
     * 注意：此方法直接操作 dataSet（与父类 dataList 同一引用），保持数据源一致
     * 用于搜索过滤、排序等场景，避免瀑布流位置跳动
     */
    fun updateDataWithDiff(newData: List<VideoData>) {
        val oldList = dataSet.toList()
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newData.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].beanId() == newData[newItemPosition].beanId()
            }
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = oldList[oldItemPosition]
                val new = newData[newItemPosition]
                return old.name == new.name
                        && old.videoUrl == new.videoUrl
                        && old.lastPlay == new.lastPlay
                        && old.type == new.type
            }
        })
        dataSet.clear()
        dataSet.addAll(newData)
        diffResult.dispatchUpdatesTo(this)
    }

    fun sorted() {
        val reversed = dataSet.reversed().toMutableList()
        updateDataWithDiff(reversed)
    }

    fun sortByName(ascending: Boolean) {
        val sorted = dataSet.toMutableList()
        sorted.sortBy { it.name.lowercase() }
        if (!ascending) sorted.reverse()
        updateDataWithDiff(sorted)
    }

    fun sortByModifiedTime(ascending: Boolean) {
        val sorted = dataSet.toMutableList()
        sorted.sortBy { videoData ->
            if (videoData.type == StorageType.LOCAL_STORAGE) {
                File(videoData.videoUrl).lastModified()
            } else {
                0L
            }
        }
        if (!ascending) sorted.reverse()
        updateDataWithDiff(sorted)
    }

    fun getLastPlayPosition(): Int {
        return dataSet.indexOfFirst { it.lastPlay }
    }

    fun updateLastPlay(playLast: String) {
        dataSet.forEach { it.lastPlay = it.videoUrl == playLast }
        notifyAllChanged()
    }

    /**
     * 精确更新 lastPlay 标记，只刷新变化的 item
     * @return Pair(旧lastPlay位置, 新lastPlay位置)，-1 表示无
     */
    fun updateLastPlayPrecise(playLast: String): Pair<Int, Int> {
        var oldPosition = -1
        var newPosition = -1
        dataSet.forEachIndexed { index, videoData ->
            val wasLastPlay = videoData.lastPlay
            videoData.lastPlay = videoData.videoUrl == playLast
            if (wasLastPlay && !videoData.lastPlay) oldPosition = index
            if (videoData.lastPlay) newPosition = index
        }
        return oldPosition to newPosition
    }

}
