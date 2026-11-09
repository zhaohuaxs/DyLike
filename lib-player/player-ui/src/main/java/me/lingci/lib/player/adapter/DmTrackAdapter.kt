package me.lingci.lib.player.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.lingci.lib.base.util.Log
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.entity.DmTrackMode
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.ui.databinding.DmTrackItemBinding
import kotlin.math.absoluteValue

/**
 *   author : happyc
 *   e-mail : bafs.jy@live.com
 *   time   : 2025/01/20
 *   desc   : 弹幕轨道
 *   version: 1.0
 */
@Suppress("MemberVisibilityCanBePrivate")
class DmTrackAdapter(private var items: ArrayList<DmTrack>) :
    RecyclerView.Adapter<DmTrackAdapter.ViewHolder>() {

    private var currentShow: Int = -1
    private var trackMode = DmTrackMode.SINGLE_SWITCH

    private var onChangeTrack: ((name: DmTrack, hide: Boolean, position: Int) -> Unit)? = null
    private var onDmOffset: ((offset: Long) -> Unit)? = null

    fun setOnChangeTrackListener(onChangeTrack: ((track: DmTrack, hide: Boolean, position: Int) -> Unit)) {
        this.onChangeTrack = onChangeTrack
    }

    fun setOnDmOffsetListener(onDmOffset: ((offset: Long) -> Unit)) {
        this.onDmOffset = onDmOffset
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(DmTrackItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val binding = holder.binding
        val item = items[position]
        binding.tvTitle.text = item.title
        binding.tvTitle.isSelected = item.selected
        binding.cbSelect.visibility = if (trackMode == DmTrackMode.MULTI_MERGE) View.VISIBLE else View.GONE
        binding.cbSelect.isSelected = item.checked
        binding.contentOffsetView.visibility = if (item.showAction) View.VISIBLE else View.GONE

        setOffsetValue(binding, item.offset)
        binding.offsetReset.setOnClickListener {
            binding.offsetReset.visibility = View.GONE
            binding.offsetValue.text = ""
            item.offset = 0
            PlayerInitializer.Danmu.offsetPosition = 0
            onDmOffset?.invoke(0)
        }
        binding.offsetAdd10.setOnClickListener {
            setOffset(binding, item, 10000)
        }
        binding.offsetAdd2.setOnClickListener {
            setOffset(binding, item, 2000)
        }
        binding.offsetAdd05.setOnClickListener {
            setOffset(binding, item, 500)
        }
        binding.offsetSub10.setOnClickListener {
            setOffset(binding, item, -10000)
        }
        binding.offsetSub2.setOnClickListener {
            setOffset(binding, item, -2000)
        }
        binding.offsetSub05.setOnClickListener {
            setOffset(binding, item, -500)
        }

        // offset调节
        binding.ivAction.setOnClickListener {
            val showing = item.showAction
            hideAction()
            if (showing.not()) {
                items[position].showAction = true
                notifyItemChanged(position)
                if (item.selected.not()) {
                    changeSelect(position)
                    // 切换轨道时保持 track.offset 不变，num 应为 0；
                    // 传 item.offset 会导致 offset = track.offset + item.offset 翻倍
                    setOffset(binding, item, 0)
                    onChangeTrack?.invoke(item, false, position)
                }
            }
        }
        binding.cbSelect.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition > -1) {
                items[adapterPosition].checked = !binding.cbSelect.isSelected
                notifyItemChanged(adapterPosition)
            }
        }
        binding.tvTitle.setOnClickListener {
            if (trackMode == DmTrackMode.MULTI_MERGE) {
                return@setOnClickListener
            }
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition > -1) {
                if (items.count { it.selected } == 1 && items[adapterPosition].selected) {
                    return@setOnClickListener
                }
                changeSelect(adapterPosition)
                setOffset(binding, items[adapterPosition], 0)
                onChangeTrack?.invoke(items[adapterPosition], true, adapterPosition)
            }
        }
    }

    fun changeOffset(holder: ViewHolder, binding: DmTrackItemBinding) {
        val adapterPosition = holder.absoluteAdapterPosition
        if (adapterPosition > -1) {
            hideCurrentAction()
            items[adapterPosition].showAction = !items[adapterPosition].showAction
            notifyItemChanged(adapterPosition)
            currentShow = adapterPosition
            if (!items[adapterPosition].selected) {
                changeSelect(adapterPosition)
                setOffset(binding, items[adapterPosition], 0)
                onChangeTrack?.invoke(items[adapterPosition], false, adapterPosition)
            }
        }
        Log.d(this@DmTrackAdapter, "action", items[adapterPosition])
    }

    fun setOffset(binding: DmTrackItemBinding, track: DmTrack, num: Long) {
        PlayerInitializer.Danmu.offsetPosition.let {
            val offset = track.offset + num
            setOffsetValue(binding, offset)
            track.offset = offset
            PlayerInitializer.Danmu.offsetPosition = offset
            onDmOffset?.invoke(offset)
        }
    }

    @SuppressLint("SetTextI18n")
    fun setOffsetValue(binding: DmTrackItemBinding, offset: Long) {
        if (offset == 0L) {
            binding.offsetReset.visibility = View.GONE
            binding.offsetValue.text = ""
        } else if (offset > 0) {
            binding.offsetReset.visibility = View.VISIBLE
            binding.offsetValue.text = "提前 ${(offset.absoluteValue.toFloat()/1000)} s"
        } else {
            binding.offsetReset.visibility = View.VISIBLE
            binding.offsetValue.text = "延迟 ${(offset.absoluteValue.toFloat()/1000)} s"
        }
    }

    override fun getItemCount(): Int = items.size

    fun setData(list: ArrayList<DmTrack>) {
        items.clear()
        items.addAll(list)
        Log.d(this@DmTrackAdapter, "setData", items)
        notifyAllData()
    }

    /**
     * 判等包含压缩包条目级 key，避免同一压缩包内不同条目被判为同一轨道。
     * mineType 用归一化值比较，兼容旧数据的大小写差异。
     */
    private fun sameTrack(first: DmTrack, second: DmTrack): Boolean {
        return first.path == second.path
            && first.title == second.title
            && first.normalizedMineType() == second.normalizedMineType()
            && first.archiveEntryName == second.archiveEntryName
            && first.archiveEntryRaw == second.archiveEntryRaw
            && first.archiveEntryIndex == second.archiveEntryIndex
    }

    /**
     * 旧数据升级判定：同文件同标题，但已有轨道缺少条目字段（旧版本只存了 title），
     * 而新选中轨道带有条目 key。命中时在旧轨道上补齐字段，避免产生重复项。
     */
    private fun isLegacyEntryUpgrade(old: DmTrack, incoming: DmTrack): Boolean {
        if (old.path != incoming.path) return false
        if (old.title != incoming.title) return false
        if (old.normalizedMineType() != incoming.normalizedMineType()) return false
        val oldComplete = old.archiveEntryName.isNotBlank() && old.archiveEntryIndex >= 0
        val incomingComplete = incoming.archiveEntryName.isNotBlank() && incoming.archiveEntryIndex >= 0
        return !oldComplete && incomingComplete
    }

    fun findData(dmTrack: DmTrack): Boolean {
        return items.any { sameTrack(it, dmTrack) }
    }

    fun findTrack(dmTrack: DmTrack): DmTrack? {
        return items.firstOrNull { sameTrack(it, dmTrack) }
    }

    fun addData(dmTrack: DmTrack) {
        if (findData(dmTrack)) {
            return
        }
        // 旧轨道重新选中同一条目：原地补齐条目 key，调用方随后的保存即完成持久化升级
        val legacyIndex = items.indexOfFirst { isLegacyEntryUpgrade(it, dmTrack) }
        if (legacyIndex > -1) {
            val legacy = items[legacyIndex]
            legacy.archiveEntryName = dmTrack.archiveEntryName
            legacy.archiveEntryRaw = dmTrack.archiveEntryRaw
            legacy.archiveEntryIndex = dmTrack.archiveEntryIndex
            Log.d(this@DmTrackAdapter, "upgrade legacy entry", legacy.title, legacy.archiveEntryIndex)
            notifyItemChanged(legacyIndex)
            return
        }
        items.add(dmTrack)
        notifyItemInserted(items.size - 1)
    }

    fun getData(): ArrayList<DmTrack> {
        return items
    }

    fun checked(): Boolean {
        return items.stream().filter { it.checked }.count() > 0
    }

    fun removedChecked() {
        items.removeIf { it.checked }
        notifyAllData()
    }

    fun clear() {
        items.clear()
        notifyAllData()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyAllData() {
        notifyDataSetChanged()
    }

    private fun hideAction() {
        items.forEachIndexed { position, item ->
            if (item.showAction) {
                item.showAction = false
                notifyItemChanged(position)
            }
        }
    }

    private fun currentPositionRightful(): Boolean {
        return currentShow > -1 && currentShow < items.size
    }

    private fun hideCurrentAction() {
        if (currentPositionRightful() && items[currentShow].showAction) {
            items[currentShow].showAction = false
            notifyItemChanged(currentShow)
            currentShow = -1
        }
    }

    fun changeSelect(position: Int) {
        clearSelect()
        items[position].selected = true
        notifyAllData()
    }

    fun clearSelect() {
        items.filter { it.selected }.forEach {
            it.selected = false
        }
    }

    fun checkSelect() {
        items.forEach {
            it.selected = it.checked
        }
        notifyAllData()
    }

    fun setTrackMode(trackMode: DmTrackMode) {
        this.trackMode = trackMode
        hideCurrentAction()
        notifyAllData()
    }

    fun selectAll() {
        items.forEach {
            it.checked = true
        }
        notifyAllData()
    }

    inner class ViewHolder(val binding: DmTrackItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.tag = this
        }

    }

}
