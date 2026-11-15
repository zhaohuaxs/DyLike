package me.lingci.lib.player.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.player.ui.databinding.EpisodeTitleItemBinding

/**
 *   author : happyc
 *   e-mail : bafs.jy@live.com
 *   time   : 2025/01/20
 *   desc   : 剧集
 *   version: 1.0
 */
class EpisodeSelectAdapter(
    private var items: MutableList<TitleItem>
) : RecyclerView.Adapter<EpisodeSelectAdapter.ViewHolder>() {

    private var onItemClick: ((item: TitleItem, position: Int) -> Unit)? = null

    /**
     * 当前选中下标。千条级选集面板每次切集（含 autoNext 连播）都会走 selected()，
     * 用缓存替代 indexOfFirst 全表扫描，把每次切集从 O(n) 降到 O(1)。
     */
    private var selectedPosition = -1

    fun setOnItemClickListener(onItemClick: (item: TitleItem, position: Int) -> Unit) {
        this.onItemClick = onItemClick
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(EpisodeTitleItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.binding.title.text = items[position].title
        holder.binding.title.isSelected = items[position].selected
        holder.itemView.setOnClickListener {
            onItemClick?.invoke(items[position], position)
            selected(position)
        }
    }

    override fun getItemCount(): Int = items.size

    @SuppressLint("NotifyDataSetChanged")
    fun setData(list: List<TitleItem>) {
        items.clear()
        items.addAll(list)
        selectedPosition = list.indexOfFirst { it.selected }
        notifyDataSetChanged()
    }

    fun addData(item: TitleItem) {
        items.add(item)
        notifyItemInserted(items.size - 1)
    }

    fun selected(position: Int) {
        if (position < 0 || position >= items.size) return
        if (selectedPosition == position && items[position].selected) return
        if (selectedPosition in items.indices) {
            items[selectedPosition].selected = false
            notifyItemChanged(selectedPosition)
        }
        items[position].selected = true
        notifyItemChanged(position)
        selectedPosition = position
    }

    fun cleanSelect() {
        if (selectedPosition in items.indices) {
            items[selectedPosition].selected = false
            notifyItemChanged(selectedPosition)
        }
        selectedPosition = -1
    }

    @SuppressLint("NotifyDataSetChanged")
    fun reverse() {
        items.reverse()
        if (selectedPosition in items.indices) {
            selectedPosition = items.size - 1 - selectedPosition
        }
        notifyDataSetChanged()
    }

    inner class ViewHolder(
        val binding: EpisodeTitleItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

    }
}