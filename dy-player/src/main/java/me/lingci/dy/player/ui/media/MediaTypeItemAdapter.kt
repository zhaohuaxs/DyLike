package me.lingci.dy.player.ui.media

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import me.lingci.dy.player.databinding.ItemMediaTypeListBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.MediaTypeEntity
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.ui.media_full.MediaItemAdapter
import me.lingci.lib.base.ui.BaseAdapter

class MediaTypeItemAdapter(
    dataSet: MutableList<MediaTypeEntity>,
    private var coverRatio: String
) : BaseAdapter<MediaTypeEntity, ItemMediaTypeListBinding>(dataSet) {

    private var onMoreItemClick: ((item: MediaTypeEntity, position: Int) -> Unit)? = null
    private var onMediaItemClick: ((item: MediaData, storageId: String, position: Int) -> Unit)? = null
    private var onMediaLongItemClick: ((item: MediaData, storageId: String, position: Int) -> Unit)? = null
    private var onRefreshClick: ((item: MediaTypeEntity, position: Int) -> Unit)? = null
    private var onResetClick: ((item: MediaTypeEntity, position: Int) -> Unit)? = null
    private var orientation: Int = Configuration.ORIENTATION_UNDEFINED
    private var sourceList: List<SourceData> = emptyList()
    private var shuffleStates: Map<String, MediaShuffleState> = emptyMap()
    // 当前启动自动播放的媒体库 id，转发给内部嵌套的 MediaItemAdapter 用于显示角标
    private var autoPlayMediaId: String = ""

    override fun createBinding(
        inflater: LayoutInflater,
        parent: ViewGroup
    ): ItemMediaTypeListBinding {
        return ItemMediaTypeListBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    }

    @SuppressLint("SetTextI18n")
    override fun bindData(binding: ItemMediaTypeListBinding, item: MediaTypeEntity, position: Int) {
        binding.typeName.text = item.title
        binding.typeMore.text = "全部(${item.size})"
        binding.typeMore.setOnClickListener {
            onMoreItemClick?.invoke(item, position)
        }

        val showActions = item.allMediaList.size > 6
        binding.layoutActions.visibility = if (showActions) View.VISIBLE else View.GONE
        if (showActions) {
            val categoryKey = "${item.type.value}:${item.storageId}"
            val hasShuffleState = shuffleStates[categoryKey]?.currentDisplayIds?.isNotEmpty() == true
            binding.tvReset.visibility = if (hasShuffleState) View.VISIBLE else View.GONE
            binding.layoutRefresh.setOnClickListener {
                onRefreshClick?.invoke(item, position)
            }
            binding.tvReset.setOnClickListener {
                onResetClick?.invoke(item, position)
            }
        }

        binding.recyclerView.layoutManager = GridLayoutManager(
            binding.root.context,
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 3
        )
        val mediaItemAdapter = MediaItemAdapter(item.mediaList, coverRatio = coverRatio)
        mediaItemAdapter.setSourceList(sourceList)
        // 转发启动自动播放媒体库 id，使内部卡片显示角标
        mediaItemAdapter.setAutoPlayMediaId(autoPlayMediaId)
        binding.recyclerView.adapter = mediaItemAdapter
        mediaItemAdapter.onItemClick { media, position ->
            onMediaItemClick?.invoke(media, item.storageId, position)
        }
        mediaItemAdapter.onLongItemClick { media, position ->
            onMediaLongItemClick?.invoke(media, item.storageId, position)
        }
    }

    fun onMediaLongItemClick(onMediaLongItemClick: (item: MediaData, storageId: String, position: Int) -> Unit) {
        this.onMediaLongItemClick = onMediaLongItemClick
    }

    fun onMediaItemClick(onMediaItemClick: (item: MediaData, storageId: String, position: Int) -> Unit) {
        this.onMediaItemClick = onMediaItemClick
    }

    fun onMoreItemClick(onMoreItemClick: (item: MediaTypeEntity, position: Int) -> Unit) {
        this.onMoreItemClick = onMoreItemClick
    }

    fun onRefreshClick(onRefreshClick: (item: MediaTypeEntity, position: Int) -> Unit) {
        this.onRefreshClick = onRefreshClick
    }

    fun onResetClick(onResetClick: (item: MediaTypeEntity, position: Int) -> Unit) {
        this.onResetClick = onResetClick
    }

    fun setShuffleStates(states: Map<String, MediaShuffleState>) {
        this.shuffleStates = states
    }

    fun resetLayout(orientation: Int) {
        this.orientation = orientation
        notifyAllChanged()
    }

    fun changeCoverRatio(currentRatio: String) {
        coverRatio = currentRatio
        notifyAllChanged()
    }

    fun setSourceList(sourceList: List<SourceData>) {
        this.sourceList = sourceList
        notifyAllChanged()
    }

    // 设置启动自动播放媒体库 id，刷新内部嵌套 MediaItemAdapter 的角标显示
    // 参数允许为空（SPManager.string 返回 String?），内部归一化为非空字符串处理
    fun setAutoPlayMediaId(autoPlayMediaId: String?) {
        val normalized = autoPlayMediaId ?: ""
        if (this.autoPlayMediaId != normalized) {
            this.autoPlayMediaId = normalized
            notifyAllChanged()
        }
    }

}
