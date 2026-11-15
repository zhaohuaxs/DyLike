package me.lingci.dy.player.ui.media_add

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.DialogMediaManagerBinding
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.loadImage
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.mediaChildSize
import me.lingci.lib.base.util.pathNoRoot
import java.io.File

/**
 * @author : happyc
 * time    : 2024/09/21
 * desc    :
 * version : 1.0
 */
open class MediaManagerDialog(
    private val onSave: (source: MediaData, update: Boolean) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var binding: DialogMediaManagerBinding
    private val viewModel: MediaManagerViewModel by activityViewModels()
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }
    private lateinit var rootPath: String
    private var sourceBean: MediaData? = null
    private lateinit var mediaSearchItemAdapter: MediaSearchItemAdapter
    private lateinit var mFolderImageItemAdapter: FolderImageItemAdapter
    private var currentMediaFile: FileEntity? = null
    private var currentCoverFile: FileEntity? = null
    private var currentVideoInfo: MediaData? = null
    private var coverModel = 0
    private var updateModel = false

    companion object {
        const val KEY_SOURCE = "version"

        fun newInstance(
            bean: MediaData?,
            onClick: (media: MediaData, update: Boolean) -> Unit
        ): MediaManagerDialog {
            val f = MediaManagerDialog(onClick)
            val args = Bundle()
            args.putParcelable(KEY_SOURCE, bean)
            f.arguments = args
            return f
        }

    }

    constructor() : this(onSave = { _, _ -> })

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        setStyle(STYLE_NORMAL, com.google.android.material.R.style.Theme_Material3_DayNight_BottomSheetDialog)
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            sourceBean = it.getParcelable(KEY_SOURCE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DialogMediaManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        Log.d(this@MediaManagerDialog, "onViewStateRestored")
        super.onViewStateRestored(savedInstanceState)
    }

    private fun init() {
        updateModel = false
        initViewModel()
        initCoverModel()
        initSelectFolderInfo()
        initRecyclerViewAdapter()
        setSelectFolderAdapter()
    }

    private fun initViewModel() {
        viewModel.videoList.observe(viewLifecycleOwner) { list ->
            binding.recyclerView.post {
                setQueryMediaAdapter()
                mediaSearchItemAdapter.setData(if (list == null) ArrayList() else ArrayList(list))
            }
        }
        viewModel.mediaData.observe(viewLifecycleOwner) { mediaBean ->
            Log.d(this@MediaManagerDialog, "media", mediaBean)
            coverModel = 0
            binding.radioCoverMode.check(R.id.radio_default)
            if (mediaBean.title.isBlank()) {
                updateModel = false
                binding.textTitle.text = getString(R.string.hint_media_add)
                resetSelect()
            } else {
                updateModel = true
                binding.textTitle.text = getString(R.string.hint_media_edit)
                binding.radioPlayMode.check(getPlayModelId(mediaBean.playMode))
                currentMediaFile =
                    FileEntity(name = mediaBean.title, path = mediaBean.path, isFile = false)
                currentCoverFile = null
                // 根据 coverType 回显封面模式
                when (mediaBean.coverType) {
                    CoverType.DEFAULT -> {
                        coverModel = 0
                        binding.radioCoverMode.check(R.id.radio_default)
                        currentCoverFile = null  // 清空，确保保存时 showFile 为空
                    }
                    CoverType.CUSTOM -> {
                        mediaBean.showFile.let { path ->
                            if (path.isNotBlank() && path != "null") {
                                coverModel = 1
                                binding.radioCoverMode.check(R.id.radio_local)
                                currentCoverFile = FileEntity(path = path, isFile = true)
                            } else {
                                coverModel = 0
                                binding.radioCoverMode.check(R.id.radio_default)
                                currentCoverFile = null
                            }
                        }
                    }
                    CoverType.AUTO -> {
                        coverModel = 2
                        binding.radioCoverMode.check(R.id.radio_auto)
                        currentCoverFile = null  // 清空，由系统自动获取
                    }
                }
                changeCover()
                binding.actionSelectDir.visibility = View.GONE
                binding.actionReselect.visibility = View.VISIBLE
                binding.textSelectDir.text = mediaBean.path.replace(rootPath, "")
                binding.textName.setText(mediaBean.title)
                binding.textName.isEnabled = true
            }
        }
    }

    private fun initCoverModel() {
        binding.radioCoverMode.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_default -> {
                    coverModel = 0
                }

                R.id.radio_local -> {
                    coverModel = 1
                }

                R.id.radio_auto -> {
                    coverModel = 2
                }
            }
            changeCover()
        }
    }

    private fun getPlayModel(): Int {
        return when (binding.radioPlayMode.checkedRadioButtonId) {
            R.id.radio_short -> 1
            R.id.radio_long -> 2
            else -> 0
        }
    }

    private fun getPlayModelId(playModel: Int): Int {
        return when (playModel) {
            1 -> R.id.radio_short
            2 -> R.id.radio_long
            else -> R.id.radio_global
        }
    }

    private fun changeCover() {
        if (!isSelected()) {
            return
        }
        hideContentView()
        if (coverModel == 0) {
            binding.ivCover.visibility = View.VISIBLE
            binding.ivCover.setImageResource(R.drawable.ic_media_default)
        }
        if (coverModel == 1) {
            if (isSetCover()) {
                binding.ivCover.visibility = View.VISIBLE
                binding.ivCover.loadImage(currentCoverFile!!.path)
            } else {
                binding.actionSelectCover.visibility = View.VISIBLE
            }
        }
        if (coverModel == 2) {
            if (isBinding()) {
                binding.ivCover.visibility = View.VISIBLE
                binding.ivCover.loadImage(currentVideoInfo!!.showFile)
            } else {
                viewModel.searchMedia(currentMediaFile!!.name)
            }
        }
    }

    private fun initSelectFolderInfo() {
        rootPath = FileOperator.rootFolder.path
        hideActionView()
        sourceBean?.let { source ->
            binding.actionSelectDir.visibility = View.GONE
            binding.textName.isEnabled = true
            binding.textTitle.text = getString(R.string.hint_media_edit)
            binding.textName.setText(source.title)
            binding.radioPlayMode.check(getPlayModelId(source.playMode))
        }
        // 开始选择文件夹
        binding.actionSelectDir.setOnClickListener {
            showSelectView()
            changeFolderItem(FileEntity(name = "root", path = rootPath))
        }
        // 选择封面
        binding.actionSelectCover.setOnClickListener {
            hideContentView()
            setSelectImageAdapter()
            changeCoverItem(FileEntity(name = "root", path = rootPath))
        }
        // 变更封面
        binding.ivCover.setOnLongClickListener {
            if (coverModel == 1) {
                hideContentView()
                setSelectImageAdapter()
                changeCoverItem(FileEntity(name = "root", path = rootPath))
            }
            return@setOnLongClickListener true
        }
        // 确认选择文件夹媒体库
        binding.actionSelect.setOnClickListener {
            Log.d(this, mFolderImageItemAdapter.findOne())
            mFolderImageItemAdapter.findOne()?.let { fileBean ->
                File(fileBean.path).let { file ->
                    Log.d(this@MediaManagerDialog, file)
                    if (file.mediaChildSize() == 0) {
                        ToastUtil.showToast(requireContext(), "该目录不不包含媒体，请重新选择")
                        return@setOnClickListener
                    }
                    currentMediaFile = fileBean
                    binding.actionSelect.isEnabled = false
                    binding.actionSelect.visibility = View.GONE
                    binding.actionReselect.visibility = View.VISIBLE
                    binding.textName.setText(fileBean.name)
                    binding.textName.isEnabled = true
                    viewModel.setText(fileBean.name)
                    if (coverModel == 0) {
                        changeCover()
                    }
                    if (coverModel == 1) {
                        hideContentView()
                        binding.actionSelectCover.visibility = View.VISIBLE
                    }
                    if (coverModel == 2) {
                        viewModel.searchMedia(fileBean.name)
                    }
                }
            }
        }
        // 重置选择
        binding.actionReselect.setOnClickListener {
            resetSelect()
        }
        // 取消|确认
        binding.actionCancel.setOnClickListener { dismiss() }
        binding.actionConfirmed.setOnClickListener {
            if (binding.textName.text.isNullOrBlank()) {
                ToastUtil.showToast(requireContext(), "请选择媒体目录")
                return@setOnClickListener
            }
            val mediaData = MediaData()
            mediaData.title = binding.textName.text.toString().trim()
            mediaData.path = currentMediaFile?.path.toString()
            // 根据封面模式设置 coverType
            mediaData.coverType = when (coverModel) {
                0 -> CoverType.DEFAULT   // 用户选择默认封面，有cover显示cover，没有占位图
                1 -> CoverType.CUSTOM    // 本地自选封面，显示用户选择的图片
                2 -> CoverType.AUTO      // 自动匹配，自动获取媒体库缩略图
                else -> CoverType.AUTO
            }
            // showFile 由 coverType 决定
            mediaData.showFile = when (mediaData.coverType) {
                CoverType.DEFAULT -> ""  // 默认封面，清空 showFile
                CoverType.CUSTOM -> if (currentCoverFile == null) "" else currentCoverFile!!.path
                CoverType.AUTO -> ""     // 自动匹配，清空 showFile（由系统自动获取）
            }
            mediaData.type = MediaLibType.LOCAL
            mediaData.storageType = StorageType.LOCAL_STORAGE
            mediaData.playMode = getPlayModel()
            sourceBean?.let {
                mediaData.id = it.id
            }
            if (mediaData.id.isBlank()) {
                mediaData.id = LibraryCompat.mediaId(mediaData)
            }
            onSave(mediaData, updateModel)
            dismiss()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initRecyclerViewAdapter() {
        // 文件夹选择/本地封面选择
        mFolderImageItemAdapter = FolderImageItemAdapter(mutableListOf())
        mFolderImageItemAdapter.onItemClick { item, _ ->
            if (item.isFile) {
                currentCoverFile = item
                hideContentView()
                binding.ivCover.visibility = View.VISIBLE
                binding.ivCover.loadImage(currentCoverFile!!.path)
            } else {
                if (currentMediaFile == null) {
                    changeFolderItem(item)
                } else {
                    changeCoverItem(item)
                }
            }
        }
        // TMDB信息绑定
        mediaSearchItemAdapter = MediaSearchItemAdapter(mutableListOf())
        mediaSearchItemAdapter.onItemClick { item, _ ->
            currentVideoInfo = item
            hideContentView()
            binding.ivCover.visibility = View.VISIBLE
            binding.ivCover.loadImage(item.showFile)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun changeFolderItem(fileEntity: FileEntity) {
        fileEntity.path.let { path ->
            var file = File(path)
            if (fileEntity.returnParent) {
                file = file.parentFile!!
            }
            FileOperator.getSortedFolders(file).map { FileEntity(it) }.let {
                setAdapterData(file, it)
                binding.textSelectDir.text = "${file.pathNoRoot()} 媒体数${file.mediaChildSize()}"
            }
        }
    }

    private fun changeCoverItem(fileEntity: FileEntity) {
        fileEntity.path.let { path ->
            var file = File(path)
            if (fileEntity.returnParent) {
                file = file.parentFile!!
            }
            // 封面选取：实验室开关控制是否列隐藏目录/隐藏图片
            FileOperator.getSortedFiles(
                file, true, FileOperator.IMAGE_EXTENSIONS, spUtil.coverPickerShowHidden
            ).map { FileEntity(it) }.let {
                setAdapterData(file, it)
            }
        }
    }

    private fun setAdapterData(parent: File, beanList: List<FileEntity>) {
        mFolderImageItemAdapter.setData(beanList, parent)
    }

    private fun hideContentView() {
        binding.actionSelectDir.visibility = View.GONE
        binding.actionSelectCover.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.ivCover.visibility = View.GONE
    }

    private fun resetSelect() {
        hideContentView()
        hideActionView()
        binding.textName.setText("")
        viewModel.setText("")
        currentMediaFile = null
        currentVideoInfo = null
        binding.radioDefault.isSelected = true
        mFolderImageItemAdapter.setData(emptyList())
        setSelectFolderAdapter()
    }

    private fun hideActionView() {
        binding.actionSelectDir.visibility = View.VISIBLE
        binding.actionReselect.visibility = View.GONE
        binding.actionSelect.visibility = View.GONE
        binding.textName.isEnabled = false
        binding.actionSelect.isEnabled = true
    }

    private fun showSelectView() {
        binding.actionSelectDir.visibility = View.GONE
        binding.actionSelect.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.VISIBLE
        binding.textSelectDir.text = "/"
    }

    private fun setSelectFolderAdapter() {
        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.recyclerView.adapter = mFolderImageItemAdapter
    }

    private fun setQueryMediaAdapter() {
        binding.recyclerView.layoutManager = LinearLayoutManager(
            requireContext(),
            RecyclerView.HORIZONTAL,
            false
        )
        binding.recyclerView.adapter = mediaSearchItemAdapter
    }

    private fun setSelectImageAdapter() {
        binding.recyclerView.visibility = View.VISIBLE
        binding.recyclerView.layoutManager = GridLayoutManager(
            requireContext(),
            3,
            GridLayoutManager.VERTICAL,
            false
        )
        binding.recyclerView.adapter = mFolderImageItemAdapter
    }

    private fun isSelected(): Boolean {
        return currentMediaFile != null
    }

    private fun isBinding(): Boolean {
        return currentVideoInfo != null
    }

    private fun isSetCover(): Boolean {
        return currentCoverFile != null
    }

}
