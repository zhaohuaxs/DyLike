package me.lingci.dy.player.ui.playlist

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.lingci.dy.player.databinding.ItemPlaylistBinding
import me.lingci.dy.player.room.entity.Playlist
import me.lingci.lib.base.ui.BaseAdapter

// Room3：数据模型从 PLAYLIST 类型的 MediaData 切换为 playlist 表实体（无 items/path/storageType 字段）
class PlaylistAdapter(
    dataSet: MutableList<Playlist>
) : BaseAdapter<Playlist, ItemPlaylistBinding>(dataSet) {

    private var onItemLongClick: ((item: Playlist, position: Int) -> Unit)? = null
    // 当前设为启动自动播放的播放列表 id（空表示未设置）；用于控制卡片角标显隐
    private var autoPlayPlaylistId: String = ""
    // 各播放列表条目数缓存（Playlist 实体无 items 字段，由 Activity 经 playlistItemDao 异步计数后注入）
    private var itemCounts: Map<String, Int> = emptyMap()

    fun onItemLongClick(listener: (item: Playlist, position: Int) -> Unit) {
        this.onItemLongClick = listener
    }

    /** 注入各播放列表条目数（playlistItemDao.countByPlaylist 的异步查询结果） */
    fun setItemCounts(counts: Map<String, Int>) {
        itemCounts = counts
    }

    override fun createBinding(inflater: LayoutInflater, parent: ViewGroup): ItemPlaylistBinding {
        return ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    @SuppressLint("SetTextI18s")
    override fun bindData(binding: ItemPlaylistBinding, item: Playlist, position: Int) {
        binding.tvTitle.text = item.title
        // 条目数来自异步注入的计数缓存（Room3：Playlist 实体无 items 字段）
        binding.tvCount.text = "包含 ${itemCounts[item.id] ?: 0} 条媒体"
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
