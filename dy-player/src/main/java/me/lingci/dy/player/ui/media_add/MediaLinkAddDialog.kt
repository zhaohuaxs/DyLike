package me.lingci.dy.player.ui.media_add

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.DialogMediaLinkAddBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.ui.media_detail.MediaDetailAdapter
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.loadImage
import me.lingci.lib.base.dailog.BaseBottomDialog
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.safeGetParcelable

/**
 * 在线媒体库
 */
class MediaLinkAddDialog : BaseBottomDialog<DialogMediaLinkAddBinding>() {

    companion object {
        const val TYPE_MEDIA = "media"
        const val TYPE_UPDATED = "updated"
    }

    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }
    private lateinit var mediaDetailAdapter: MediaDetailAdapter
    private var mediaData: MediaData? = null
    private var updated: Boolean = false
    private var onSave: ((media: MediaData, updated: Boolean) -> Unit)? = null

    fun onSave(onSave: (media: MediaData, updated: Boolean) -> Unit) {
        this.onSave = onSave
    }

    override fun onStart() {
        super.onStart()
        arguments?.let { bundle ->
            bundle.safeGetParcelable<MediaData>(TYPE_MEDIA)?.let { mediaData ->
                binding.toolbar.title = "在线媒体库修改"
                // Room3：编辑既有媒体库时 items 为 @Ignore 字段（Room 读出恒为空），从 video_data 子表回填
                viewLifecycleOwner.lifecycleScope.launch {
                    mediaData.items =
                        DatabaseManager.videoDataDao.listByMedia(mediaData.id).toMutableList()
                    initData(mediaData)
                }
            }
            bundle.getBoolean(TYPE_UPDATED).let {
                updated = it
            }
        }
    }

    override fun createBinding(
        inflater: LayoutInflater,
        container: ViewGroup?
    ): DialogMediaLinkAddBinding {
        return DialogMediaLinkAddBinding.inflate(inflater, container, false)
    }

    override fun init() {
        initView()
        initAnalysis()
    }

    private fun initView() {
        binding.toolbar.title = "在线媒体库新增"
        binding.toolbar.setNavigationIcon(me.lingci.lib.base.R.drawable.ic_close)
        binding.toolbar.setNavigationOnClickListener {
            dismiss()
        }

        binding.tvTitle.text = "新增媒体库"
        binding.tvInfo.text = "待输入"

        binding.saveButton.setOnClickListener {
            mediaData?.let {
                onSave?.invoke(it, updated)
                dismiss()
            }
        }

        // 数据集
        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        mediaDetailAdapter = MediaDetailAdapter(arrayListOf())
        binding.recyclerView.adapter = mediaDetailAdapter

        mediaDetailAdapter.onItemClick { _, position ->

        }

        mediaDetailAdapter.onLongItemClick { v, item, position ->
            val popupMenu = PopupMenu(requireContext(), v)
            popupMenu.menu.apply {
                add(1, 1, 1, "删除当前")
                add(1, 2, 2, "清除全部")
            }
            popupMenu.setOnMenuItemClickListener {
                if (it.itemId == 1) {
                    mediaDetailAdapter.removeItem(position)
                } else {
                    mediaDetailAdapter.clearData()
                }
                return@setOnMenuItemClickListener true
            }
            popupMenu.show()
        }
    }

    private fun initAnalysis() {
        binding.linkButton.setOnClickListener {
            val linkString = binding.inputValue.editText?.text.toString().trim()
            if (linkString.isBlank()) {
                ToastUtil.showToast(requireContext(), "请输入媒体链接")
                return@setOnClickListener
            }
            val mediaData = MediaHelper.createMediaFromString(linkString)
            if (mediaData.items.isNotEmpty()) {
                initData(mediaData)
            } else {
                binding.saveButton.visibility = View.GONE
            }

        }
    }

    @SuppressLint("SetTextI18n")
    private fun initData(mediaData: MediaData) {
        this.mediaData = mediaData
        binding.saveButton.visibility = View.VISIBLE
        binding.tvTitle.text = mediaData.title
        binding.tvInfo.text =
            mediaData.playType.ifBlank { "包含 ${mediaData.items.size} 条媒体" }
        binding.ivThumb.setImageResource(R.drawable.ic_media_default)
        binding.ivThumb.loadImage(mediaData.showFile)
        mediaDetailAdapter.updateData(mediaData.items)
    }

    fun setMedia(mediaData: MediaData?) {
        val bundle = Bundle()
        bundle.putParcelable(TYPE_MEDIA, mediaData)
        bundle.putBoolean(TYPE_UPDATED, mediaData != null)
        arguments = bundle
    }

}
