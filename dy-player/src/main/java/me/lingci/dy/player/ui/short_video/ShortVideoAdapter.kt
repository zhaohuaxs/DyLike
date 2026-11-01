package me.lingci.dy.player.ui.short_video

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import me.lingci.dy.player.databinding.ItemShortVideoBinding
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.ShortTitleFormatter
import me.lingci.dy.player.view.ShortVideoControlView

/**
 * 短视频item
 */
class ShortVideoAdapter(
    private val mVideoData: List<VideoData>
) : RecyclerView.Adapter<ShortVideoAdapter.ViewHolder>() {

    private var mOnShortVideoListener: ShortVideoControlView.OnShortVideoListener? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemShortVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding).apply {
            itemView.tag = this
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bindItem(mVideoData[position])
        holder.binding.shortVideoView.setOnLikeClickListener(mOnShortVideoListener)
    }

    override fun getItemCount(): Int {
        return mVideoData.size
    }

    fun setOnShortVideoListener(listener: ShortVideoControlView.OnShortVideoListener) {
        mOnShortVideoListener = listener
    }

    fun changeVisibility() {
        if (itemCount > 0) {
            notifyItemRangeChanged(0, itemCount)
        }
    }

    class ViewHolder(
        val binding: ItemShortVideoBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        lateinit var mVideoData: VideoData

        init {
            itemView.tag = this
        }

        fun bindItem(item: VideoData) {
            mVideoData = item
            binding.shortVideoView.changeVisibility()
            binding.shortVideoView.setTitle(ShortTitleFormatter.format(item.name))
            binding.shortVideoView.setLike(item.like)

        }

        val shortVideoControlView: ShortVideoControlView
            get() = binding.shortVideoView
        val playerContainer: FrameLayout
            get() = binding.container
    }

}
