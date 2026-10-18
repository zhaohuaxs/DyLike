package me.lingci.dy.player.ui.source

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.FragmentSourceBinding
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.file_browser.FileBrowserActivity
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.main.BaseTransitionFragment
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.crash.CrashToFile
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.Log

class SourceFragment : BaseTransitionFragment() {

    private var _binding: FragmentSourceBinding? = null

    private val spUtil by lazy { SpUtil(requireContext()) }

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private val sourceViewModel: SourceViewModel by activityViewModels()
    private lateinit var mSourceItemAdapter: SourceItemAdapter
    private lateinit var streamLinkDialog: StreamLinkDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSourceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViewModel()
        // Room3：源列表改为 source_data DAO 异步加载，加载完成后再初始化列表 UI
        viewLifecycleOwner.lifecycleScope.launch {
            val sourceList = withContext(Dispatchers.IO) {
                DatabaseManager.sourceDataDao.list().toMutableList()
            }
            initView(sourceList)
        }
    }

    private fun initViewModel() {
        sourceViewModel.text.observe(viewLifecycleOwner) {
            Log.d(this@SourceFragment, it)
        }
    }

    private fun getHeaders(string: String): MutableMap<String, String> {
        return string.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line ->
                val count = line.count { it == ':' }
                if (line.startsWith(":") && count == 2) {
                    line.substringBeforeLast(":").trim() to line.substringAfterLast(":").trim()
                } else {
                    line.substringBefore(":").trim() to line.substringAfter(":").trim()
                }
            }.toMap().toMutableMap()
    }

    private fun oldToMap(line: String) {
        line.split(":", limit = 2).takeIf { parts ->
            parts.size == 2 && parts[0].isNotBlank()
        }?.let { parts ->
            parts[0].trim() to parts[1].trim()
        }
    }

    private fun initView(sourceList: MutableList<SourceData>) {
        streamLinkDialog = StreamLinkDialog()
        streamLinkDialog.onValueListener { value, header ->
            LongVideoActivity.start(
                requireContext(),
                arrayListOf(
                    VideoData(
                        name = value.substringBefore("?"),
                        videoUrl = value,
                        type = StorageType.STREAM_LINK,
                        headers = getHeaders(header)
                    )
                ),
                0,
                false
            )
        }
        sourceList.add(0, SourceData().apply {
            id = LibraryCompat.builtinStreamId()
            type = StorageType.STREAM_LINK
            title = "串流播放"
            siteUrl = "https?://media.mp4|.m3u8"
            schema = "https?://media.mp4|.m3u8"
        })
        requireContext().getExternalFilesDirs(null)?.let { dirs ->
            if (dirs.size > 1) {
                try {
                    val path = dirs[1].path.split("Android/data")[0]
                    Log.d(this@SourceFragment, path)
                    sourceList.add(0, SourceData().apply {
                        id = LibraryCompat.builtinExternalId()
                        type = StorageType.LOCAL_STORAGE
                        title = "外部存储"
                        siteUrl = path
                        schema = path
                    })
                } catch (e: Exception) {
                    CrashToFile.saveExceptionToFile(requireActivity(), e)
                }
            }
        }
        sourceList.add(0, SourceData().apply {
            id = LibraryCompat.builtinLocalId()
            type = StorageType.LOCAL_STORAGE
            title = "本地存储"
            siteUrl = "/storage/emulated/0/"
            schema = "/storage/emulated/0/"
        })

        mSourceItemAdapter = SourceItemAdapter(sourceList.toMutableList())
        binding.recyclerView.layoutManager =
            LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false)
        binding.recyclerView.adapter = mSourceItemAdapter
        mSourceItemAdapter.onItemClick { item, _ ->
            if (item.type == StorageType.STREAM_LINK) {
                try {
                    streamLinkDialog.show(childFragmentManager, "stream_link")
                } catch (e: Exception) {
                    Log.d(this@SourceFragment, e.message, e)
                }
            } else {
                FileBrowserActivity.start(requireContext(), item)
            }
        }
        mSourceItemAdapter.onItemLongClick { item, position ->
            if (item.type == StorageType.LOCAL_STORAGE || item.type == StorageType.STREAM_LINK) {
                return@onItemLongClick
            }
            val itemActionDialog = ItemActionDialog(
                mutableListOf(
                    ItemAction(1, "编辑资源库"),
                    ItemAction(2, "删除资源库", me.lingci.lib.base.R.color.red_700)
                )
            ) { itemId ->
                when(itemId) {
                    1 -> {
                        showSourceManager(item, position)
                    }
                    2 -> {
                        mSourceItemAdapter.removeItem(position)
                        // Room3：删除资源库改为删 source_data 单行；
                        // 关联媒体由 FK SET_NULL 保留（storage_id 置空），不做整表写回
                        viewLifecycleOwner.lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                DatabaseManager.sourceDataDao.delete(item)
                            }
                        }
                        removeHistory(item)
                    }
                }
            }
            itemActionDialog.show(childFragmentManager, itemActionDialog.tag)
        }
        binding.floatingButton.setOnClickListener {
            val itemActionDialog = ItemActionDialog(
                mutableListOf(
                    ItemAction(1, "添加WebDav资源库"),
                    ItemAction(2, "添加SMB资源库")
                )
            ) { itemId ->
                when (itemId) {
                    1 -> showSourceManager(null, -1, StorageType.WEBDAV)
                    2 -> showSourceManager(null, -1, StorageType.SMB)
                }
            }
            itemActionDialog.show(childFragmentManager, itemActionDialog.tag)
        }
    }

    private fun showSourceManager(
        source: SourceData?,
        position: Int,
        addType: StorageType? = null
    ) {
        val type = source?.storageType() ?: addType ?: StorageType.WEBDAV
        val onSave: (SourceData) -> Unit = {
            if (position >= 0) {
                mSourceItemAdapter.updateItem(it, position)
            } else {
                mSourceItemAdapter.addItem(it)
            }
            // Room3：新增/编辑改为单条落库（id 已由对话框生成：不存在则 insert，存在则 update）
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val dao = DatabaseManager.sourceDataDao
                    if (dao.getById(it.id) == null) {
                        dao.insert(it)
                    } else {
                        dao.update(it)
                    }
                }
            }
        }
        if (type == StorageType.SMB) {
            val dialog = SmbManagerDialog.newInstance(source, onSave)
            dialog.show(childFragmentManager, dialog.tag)
        } else {
            val dialog = WebdavManagerDialog.newInstance(source, onSave)
            dialog.show(childFragmentManager, dialog.tag)
        }
    }

    private fun removeHistory(item: SourceData) {
        // 删除资源库时清理其站点 URL 前缀下的播放历史（play_history 联查 video_data 前缀匹配，
        // 替代 SP historyJson 过滤回写）
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { DatabaseManager.playHistoryDao.deleteByVideoUrlPrefix("${item.siteUrl}%") }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
