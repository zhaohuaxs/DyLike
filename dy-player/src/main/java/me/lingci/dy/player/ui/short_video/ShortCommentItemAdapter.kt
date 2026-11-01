package me.lingci.dy.player.ui.short_video

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.lingci.dy.player.databinding.ItemShortCommetListBinding
import me.lingci.lib.base.ui.BaseAdapter
import me.lingci.lib.dm.view.entity.xml.DmItem
import xyz.doikki.videoplayer.util.PlayerUtils

/**
 * 短视频评论/笔记item
 */
class ShortCommentItemAdapter(
    dataSet: MutableList<DmItem>
) : BaseAdapter<DmItem, ItemShortCommetListBinding>(dataSet) {

    private var onItemLongClick: ((item: DmItem, position: Int) -> Unit)? = null
    private var onItemMoreClick: ((view: View, item: DmItem, position: Int) -> Unit)? = null

    override fun createBinding(
        inflater: LayoutInflater,
        parent: ViewGroup
    ): ItemShortCommetListBinding {
        return ItemShortCommetListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    override fun bindData(binding: ItemShortCommetListBinding, item: DmItem, position: Int) {
        binding.tvTitle.text = item.content
        binding.tvCurrentTime.text = try {
            if (item.style.isNotBlank()) PlayerUtils.stringForTime(item.style.toInt()) else "00:00"
        } catch (_: NumberFormatException) {
            "00:00"
        }
        binding.tvSendTime.text = item.extend
        binding.root.setOnClickListener {
            onItemClick?.invoke(item, position)
        }
        binding.root.setOnLongClickListener {
            onItemLongClick?.invoke(item, position)
            true
        }
        binding.buttonMore.setOnClickListener {
            onItemMoreClick?.invoke(it, item, position)
        }
    }

    fun onItemLongClick(onItemLongClick: (item: DmItem, position: Int) -> Unit) {
        this.onItemLongClick = onItemLongClick
    }

    fun onItemMoreClick(onItemMoreClick: (view: View, item: DmItem, position: Int) -> Unit) {
        this.onItemMoreClick = onItemMoreClick
    }

    fun sorted() {
        dataList.reverse()
        notifyAllChanged()
    }

}