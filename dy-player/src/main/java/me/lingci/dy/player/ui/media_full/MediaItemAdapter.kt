package me.lingci.dy.player.ui.media_full

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import androidx.constraintlayout.widget.ConstraintLayout
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ItemMediaListBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.util.setCover
import me.lingci.lib.base.ui.BaseAdapter

class MediaItemAdapter(
    private val dataSet: MutableList<MediaData>,
    private var typeMode: Boolean = false,
    private var coverRatio: String
) : BaseAdapter<MediaData, ItemMediaListBinding>(dataSet) {

    private var onLongItemClick: ((item: MediaData, position: Int) -> Unit)? = null
    private var batchMode = false
    private var sourceList: List<SourceData> = emptyList()
    // 当前设为启动自动播放的媒体库 id（空表示未设置）；用于控制卡片角标显隐
    private var autoPlayMediaId: String = ""

    override fun createBinding(inflater: LayoutInflater, parent: ViewGroup): ItemMediaListBinding {
        return ItemMediaListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    override fun bindData(binding: ItemMediaListBinding, item: MediaData, position: Int) {
        binding.tvTitle.text = item.title
        binding.ivThumb.setImageResource(R.drawable.ic_media_default)

        val cardRatio = when (coverRatio) {
            "1:1" -> "1:1"
            "4:3" -> "10:9"
            else -> "9:13"
        }

        val cardParams = binding.cardView.layoutParams as ConstraintLayout.LayoutParams
        cardParams.dimensionRatio = cardRatio
        binding.cardView.layoutParams = cardParams
        if (typeMode) {
            binding.ivType.visibility = View.VISIBLE
            when(item.type) {
                MediaLibType.LOCAL -> {
                    binding.ivType.setImageResource(R.drawable.ic_mobile_storage)
                }
                MediaLibType.ONLINE -> {
                    binding.ivType.setImageResource(R.drawable.ic_add_link_24)
                }
                MediaLibType.WEBDAV, MediaLibType.SMB -> {
                    binding.ivType.setImageResource(R.drawable.ic_cloud_storage)
                }
                else -> binding.ivType.visibility = View.GONE
            }
        } else {
            binding.ivType.visibility = View.GONE
        }
        binding.ivThumb.setCover(item, sourceList)
        // 显示启动自动播放角标（仅当该媒体库被设为启动自动播放时）
        binding.tvAutoPlayBadge.visibility =
            if (autoPlayMediaId.isNotEmpty() && autoPlayMediaId == item.id) View.VISIBLE else View.GONE
        changeSelect(binding, item, position)
        binding.root.setOnClickListener {
            if (position < dataSet.size) {
                if (batchMode && item.type > MediaLibType.DEFAULT) {
                    item.selected = !item.selected
                    changeSelect(binding, item, position)
                    //notifyItemChanged(position)
                }
                if (batchMode.not()) {
                    onItemClick?.invoke(item, position)
                }
            }
        }
        binding.root.setOnLongClickListener {
            if (position < dataSet.size) {
                if (batchMode.not()) {
                    onLongItemClick?.invoke(item, position)
                    return@setOnLongClickListener true
                }
            }
            false
        }
    }

    // loadFirstVideoThumbnail removed; restore original behavior

    private fun changeSelect(binding: ItemMediaListBinding, item: MediaData, position: Int) {
        binding.ivSelect.visibility = if (batchMode && item.type > MediaLibType.DEFAULT && item.selected) View.VISIBLE else View.GONE
        val targetScale = if (batchMode && item.type > MediaLibType.DEFAULT && item.selected) 0.98f else 1f
        if (binding.root.scaleX != targetScale) {
            val scaleAnimation = ScaleAnimation(
                binding.root.scaleX,
                targetScale,
                binding.root.scaleY,
                targetScale,
                binding.root.width / 2f,
                binding.root.height / 2f
            )
            scaleAnimation.duration = 200
            scaleAnimation.fillAfter = true
            scaleAnimation.setAnimationListener(object: Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation?) {

                }

                override fun onAnimationEnd(animation: Animation?) {
                    binding.root.scaleX = targetScale
                    binding.root.scaleY = targetScale
                }

                override fun onAnimationRepeat(animation: Animation?) {

                }
            })
            binding.root.startAnimation(scaleAnimation)
            //binding.root.scaleX = targetScale
            //binding.root.scaleY = targetScale
        }
    }

    fun onLongItemClick(onLongItemClick: (item: MediaData, position: Int) -> Unit) {
        this.onLongItemClick = onLongItemClick
    }

    fun setSourceList(sourceList: List<SourceData>) {
        this.sourceList = sourceList
        notifyAllChanged()
    }

    // 设置当前启动自动播放的媒体库 id，并刷新角标显示
    // 参数允许为空（SPManager.string 返回 String?），内部归一化为非空字符串处理
    fun setAutoPlayMediaId(autoPlayMediaId: String?) {
        val normalized = autoPlayMediaId ?: ""
        if (this.autoPlayMediaId != normalized) {
            this.autoPlayMediaId = normalized
            notifyAllChanged()
        }
    }

    fun getBatchMode(): Boolean {
        return batchMode
    }

    fun batchMode(position: Int) {
        batchMode = true
        dataSet[position].selected = true
        notifyAllChanged()
    }

    fun exitBatchMode() {
        batchMode = false
        dataSet.forEach {
            it.selected = false
        }
        notifyAllChanged()
    }

    fun selectAll() {
        dataSet.forEach {
            it.selected = it.type > MediaLibType.DEFAULT
        }
        notifyAllChanged()
    }

    fun selectInvert() {
        dataSet.forEach {
            if (it.type > MediaLibType.DEFAULT) {
                it.selected = !it.selected
            } else {
                it.selected = false
            }
        }
        notifyAllChanged()
    }

    fun listSelect(): List<MediaData> {
        return dataList.filter { it.selected }
    }

    fun removeSelect() {
        dataSet.removeIf{item -> item.selected}
        batchMode = false
        notifyAllChanged()
    }

    fun changeCoverRatio(currentRatio: String) {
        coverRatio = currentRatio
        notifyAllChanged()
    }

    fun sorted() {
        dataSet.reverse()
        notifyAllChanged()
    }

}
