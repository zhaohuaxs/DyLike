package me.lingci.dy.player.ui.playlist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.lingci.dy.player.databinding.ItemPlaylistBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.lib.base.ui.BaseAdapter

class PlaylistAdapter(
    dataSet: MutableList<MediaData>
) : BaseAdapter<MediaData, ItemPlaylistBinding>(dataSet) {

    private var onItemLongClick: ((item: MediaData, position: Int) -> Unit)? = null
    // 当前设为启动自动播放的播放列表 id（空表示未设置）；用于控制卡片角标显隐
    private var autoPlayPlaylistId: String = ""

    fun onItemLongClick(listener: (item: MediaData, position: Int) -> Unit) {
        this.onItemLongClick = listener
    }

    override fun createBinding(inflater: LayoutInflater, parent: ViewGroup): ItemPlaylistBinding {
        return ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    @SuppressLint("SetTextI18s")
    override fun bindData(binding: ItemPlaylistBinding, item: MediaData, position: Int) {
        binding.tvTitle.text = item.title
        binding.tvCount.text = "包含 ${item.items.size} 条媒体"
        // 显示启动自动播放角标（仅当该播放列表被设为启动自动播放时）
        binding.tvAutoPlayBadge.visibility =
            if (autoPlayPlaylistId.isNotEmpty() && autoPlayPlaylistId == item.id) View.VISIBLE else View.GONE
        // 显示播放类型角标（仅当显式设为短/长视频时显示；全局模式不显示）
        val modeText = when (item.playMode) {
            1 -> "短"
            2 -> "长"
            else -> null
        }
        binding.tvPlayModeBadge.visibility = if (modeText != null) View.VISIBLE else View.GONE
        if (modeText != null) binding.tvPlayModeBadge.text = modeText
        binding.root.setOnClickListener {
            if (position < dataList.size) {
                onItemClick?.invoke(item, position)
            }
        }
        binding.root.setOnLongClickListener {
            if (position < dataList.size) {
                onItemLongClick?.invoke(item, position)
            }
            true
        }
    }

    // 设置启动自动播放播放列表 id，刷新角标显示
    // 参数允许为空（SPManager.string 返回 String?），内部归一化为非空字符串处理
    fun setAutoPlayPlaylistId(autoPlayPlaylistId: String?) {
        val normalized = autoPlayPlaylistId ?: ""
        if (this.autoPlayPlaylistId != normalized) {
            this.autoPlayPlaylistId = normalized
            notifyAllChanged()
        }
    }

}
