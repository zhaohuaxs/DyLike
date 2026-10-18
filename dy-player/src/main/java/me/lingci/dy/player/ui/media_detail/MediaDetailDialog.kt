package me.lingci.dy.player.ui.media_detail

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.DialogMediaDetailBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.setCover
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.safeGetParcelable
import java.io.File

/**
 * 媒体库详情
 */
class MediaDetailDialog : DialogFragment() {

    companion object {
        const val TYPE_MEDIA = "media"
    }

    private var _binding: DialogMediaDetailBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }
    private lateinit var offsetDialog: OpEdOffsetDialog
    private lateinit var mediaDetailAdapter: MediaDetailAdapter
    private lateinit var mediaData: MediaData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogMediaDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.MaterialAlertDialog_Material3)
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onStart() {
        super.onStart()
        arguments?.let { bundle ->
            bundle.safeGetParcelable<MediaData>(TYPE_MEDIA)?.let { mediaData ->
                initData(mediaData)
            }
        }
    }

    private fun init() {
        initView()
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.toolbar.setNavigationOnClickListener { dismiss() }
        binding.toolbar.title = "媒体库详情"

        binding.playButton.setOnClickListener {
            doPlay(mediaDetailAdapter.getData(), 0, mediaData.isHistory())
        }

        offsetDialog = OpEdOffsetDialog()
        offsetDialog.onValueListener { op, ed ->
            mediaData.opOffset = op
            mediaData.edOffset = ed
            binding.tvOffset.text = getString(R.string.hint_media_op_ed_offset, mediaData.opValue(), mediaData.edValue())
            // updateMedia 已改 suspend（Room DAO 写入），fire-and-forget 落库
            lifecycleScope.launch {
                runCatching { MediaHelper.updateMedia(spUtil, mediaData) }
            }
        }

        binding.tvOffset.setOnClickListener {
            offsetDialog.arguments = OpEdOffsetDialog.buildData("", mediaData.opOffset, mediaData.edOffset)
            offsetDialog.show(childFragmentManager, offsetDialog.tag)
        }

        // 数据集
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        mediaDetailAdapter = MediaDetailAdapter(arrayListOf())
        binding.recyclerView.adapter = mediaDetailAdapter

        mediaDetailAdapter.onItemClick { _, position ->
            doPlay(mediaDetailAdapter.getData(), position, mediaData.isHistory())
        }

        mediaDetailAdapter.onLongItemClick { v, item, position ->
            if (mediaData.isHistory()) {
                val popupMenu = PopupMenu(requireContext(), v)
                popupMenu.menu.apply {
                    add(1, 1, 1, "删除当前")
                    add(1, 2, 2, "清除全部")
                }
                popupMenu.setOnMenuItemClickListener {
                    // 「删除当前/清除全部」改删 play_history（原 SP historyJson 整列表回写已废弃）
                    val removed = if (it.itemId == 1) mediaDetailAdapter.getData().getOrNull(position) else null
                    if (it.itemId == 1) {
                        mediaDetailAdapter.removeItem(position)
                    } else {
                        mediaDetailAdapter.clearData()
                    }
                    binding.tvInfo.text = "包含 ${mediaDetailAdapter.itemCount} 条历史记录"
                    binding.playButton.visibility = if (mediaDetailAdapter.itemCount  == 0) View.GONE else View.VISIBLE
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        runCatching {
                            if (removed != null) {
                                DatabaseManager.playHistoryDao.deleteByVideoId(removed.beanId())
                            } else {
                                DatabaseManager.playHistoryDao.deleteAll()
                            }
                        }
                    }
                    return@setOnMenuItemClickListener true
                }
                popupMenu.show()
            }
        }
    }

    private fun doPlay(videoData: List<VideoData>, int: Int, history: Boolean) {
        if (spUtil.longVideoMode) {
            LongVideoActivity.start(requireContext(), mediaData, ArrayList(videoData), int, history)
        } else {
            ShortVideoActivity.start(requireContext(), ArrayList(videoData), int, history)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initData(mediaData: MediaData) {
        this.mediaData = mediaData
        binding.tvTitle.text = mediaData.title
        binding.tvOffset.visibility = if (mediaData.type == MediaLibType.HISTORY) View.GONE else View.VISIBLE
        binding.tvOffset.text = getString(R.string.hint_media_op_ed_offset, mediaData.opValue(), mediaData.edValue())
        when(mediaData.type) {
            MediaLibType.HISTORY -> {
                // 历史改从 play_history 联查 video_data（结果已按最近播放倒序，替代 SP historyJson 读取）
                lifecycleScope.launch {
                    val videoData = DatabaseManager.playHistoryDao.listHistoryVideos()
                    binding.tvInfo.text = "包含 ${videoData.size} 条历史记录"
                    binding.playButton.visibility = if (videoData.isEmpty()) View.GONE else View.VISIBLE
                    mediaDetailAdapter.updateData(videoData.toMutableList())
                }
            }
            MediaLibType.DEFAULT, MediaLibType.LOCAL -> {
                var path = mediaData.path
                if (path.isBlank()) {
                    path = FileOperator.movieFolder.path
                }
                FileOperator.getSortedFiles(File(path), FileOperator.VIDEO_EXTENSIONS).map { VideoData(it) }.let { videoData ->
                    binding.tvInfo.text = "${mediaData.path} \n包含 ${videoData.size} 条媒体"
                    binding.playButton.visibility = if (videoData.isEmpty()) View.GONE else View.VISIBLE
                    mediaDetailAdapter.updateData(videoData)
                }
            }
            MediaLibType.ONLINE -> {
                // items 为 @Ignore 字段（Room 读出恒为空）：剧集改从 video_data 子表加载
                lifecycleScope.launch {
                    val videoData = DatabaseManager.videoDataDao.listByMedia(mediaData.id)
                    binding.tvInfo.text = "${mediaData.playType} \n包含 ${videoData.size} 条媒体"
                    mediaDetailAdapter.updateData(videoData.toMutableList())
                    Log.d(this@MediaDetailDialog, videoData.size)
                }
            }
            else -> {

            }
        }
        binding.ivThumb.setImageResource(R.drawable.ic_media_default)
        binding.ivThumb.setCover(mediaData)
    }

    fun setMedia(mediaData: MediaData) {
        val bundle = Bundle()
        bundle.putParcelable(TYPE_MEDIA, mediaData)
        arguments = bundle
    }

    override fun show(manager: FragmentManager, tag: String?) {
        if (isAdded && isVisible) {
            return
        }
        super.show(manager, tag)
    }

    fun showFull(fragmentManager: FragmentManager) {
        if (isAdded && isVisible) {
            return
        }
        val transaction = fragmentManager.beginTransaction()
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
        transaction
            .add(android.R.id.content, this)
            .addToBackStack(null)
            .commit()
    }

}
