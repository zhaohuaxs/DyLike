package me.lingci.dy.player.ui.history

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.bumptech.glide.Glide
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ItemHistoryBinding
import me.lingci.dy.player.util.AppUtil
import me.lingci.lib.base.ui.BaseAdapter
import me.lingci.lib.base.util.CodeUtil
import me.lingci.dy.player.util.setCover
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 历史页适配器（替代原 SP historyJson 扁平列表）：
 * 单一 item_history 布局双形态（组项/文件项），进度条 + 时间展示，长按删除。
 */
class HistoryItemAdapter(
    private val dataSet: MutableList<HistoryDisplayItem>
) : BaseAdapter<HistoryDisplayItem, ItemHistoryBinding>(dataSet) {

    // BaseAdapter 无长按回调钩子，参照 PlaylistAdapter 自行定义
    private var onItemLongClick: ((item: HistoryDisplayItem, position: Int) -> Unit)? = null

    /** 最后播放时间展示格式 */
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun onItemLongClick(listener: (item: HistoryDisplayItem, position: Int) -> Unit) {
        this.onItemLongClick = listener
    }

    override fun createBinding(
        inflater: LayoutInflater,
        parent: ViewGroup
    ): ItemHistoryBinding {
        return ItemHistoryBinding.inflate(inflater, parent, false)
    }

    override fun bindData(
        binding: ItemHistoryBinding,
        item: HistoryDisplayItem,
        position: Int
    ) {
        when (item) {
            is HistoryDisplayItem.HistoryGroup -> bindGroup(binding, item)
            is HistoryDisplayItem.HistorySingle -> bindSingle(binding, item)
        }
        binding.root.setOnClickListener {
            onItemClick?.invoke(dataSet[position], position)
        }
        binding.root.setOnLongClickListener {
            onItemLongClick?.invoke(dataSet[position], position)
            true
        }
    }

    /** 组项：媒体库/播放列表聚合（封面用媒体封面，副标题"已播 N 集 · 集名"） */
    private fun bindGroup(binding: ItemHistoryBinding, item: HistoryDisplayItem.HistoryGroup) {
        when {
            // 媒体实体仍在：用媒体封面（内部容错 showFile/coverPath 缺失与 WebDAV headers）
            item.media != null -> binding.ivThumb.setCover(item.media)
            item.lastVideoUrl.isNotBlank() -> loadThumb(binding.ivThumb, item.lastVideoUrl)
            else -> binding.ivThumb.setImageResource(R.drawable.ic_media_default)
        }
        binding.tvTitle.text = item.title
        // RecyclerView 复用：文件项隐藏了副标题，组项绑定前恢复可见
        binding.tvSubtitle.visibility = View.VISIBLE
        binding.tvSubtitle.text =
            binding.root.context.getString(R.string.history_watched_count, item.epCount) +
                    " · " + item.lastVideoName
        bindProgress(binding, item.progress, item.duration)
        binding.tvTime.text = timeFormat.format(Date(item.lastPlayedAt))
    }

    /** 文件项：单文件/串流历史（缩略图用 saveThumb 缓存，副标题隐藏） */
    private fun bindSingle(binding: ItemHistoryBinding, item: HistoryDisplayItem.HistorySingle) {
        loadThumb(binding.ivThumb, item.videoData.videoUrl)
        binding.tvTitle.text = item.videoData.name
        binding.tvSubtitle.visibility = View.GONE
        bindProgress(binding, item.progress, item.duration)
        binding.tvTime.text = timeFormat.format(Date(item.lastPlayedAt))
    }

    /** 进度条：已播完标"已看完"隐藏进度条；未播完显示百分比 + "12:30/24:00"；时长未知隐藏 */
    private fun bindProgress(binding: ItemHistoryBinding, progress: Long, duration: Long) {
        val context = binding.root.context
        if (duration > 0 && progress >= duration) {
            binding.progressBar.visibility = View.GONE
            binding.tvProgressText.text = context.getString(R.string.history_finished)
        } else if (duration > 0) {
            binding.progressBar.visibility = View.VISIBLE
            binding.progressBar.progress = ((progress * 100) / duration).toInt()
            binding.tvProgressText.text = "${formatDuration(progress)}/${formatDuration(duration)}"
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvProgressText.text = ""
        }
    }

    /** 缩略图加载：saveThumb 缓存文件存在用 Glide，缺失回默认图标 */
    private fun loadThumb(imageView: ImageView, videoUrl: String) {
        val file = File(
            imageView.context.externalCacheDir,
            ".thumb/${CodeUtil.md5(videoUrl)}.${AppUtil.THUMB_TYPE}"
        )
        if (file.exists()) {
            Glide.with(imageView.context).load(file).into(imageView)
        } else {
            imageView.setImageResource(R.drawable.ic_video_default)
        }
    }

    /** 毫秒时长格式化：1 小时内 mm:ss，超过 h:mm:ss */
    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

}
