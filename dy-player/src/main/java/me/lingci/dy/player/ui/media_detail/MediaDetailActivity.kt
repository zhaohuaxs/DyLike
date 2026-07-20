package me.lingci.dy.player.ui.media_detail

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityMediaDetailBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.danmaku_binding.DanmakuBindingActivity
import me.lingci.dy.player.ui.danmaku_binding.isUsableBindingTrack
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.ui.playlist.PlaylistSelectDialog
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.ui.source.ItemAction
import me.lingci.dy.player.ui.source.ItemActionDialog
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.MediaManger
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.setCover
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.ui.setEmptyView
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.notStartDot
import me.lingci.lib.base.util.safeGetParcelable
import java.io.File

/**
 * 媒体库详情页面
 * 显示媒体库中的视频列表，支持长视频和短视频两种模式
 * 支持不同类型的媒体库：历史记录、收藏、本地、在线、WebDAV、SMB
 */
class MediaDetailActivity : BaseActivity(), MenuProvider {

    companion object {
        const val KEY_MEDIA = "media"

        /**
         * 启动媒体库详情页面
         * @param context 上下文
         * @param data 媒体库数据
         */
        fun start(context: Context, data: MediaData) {
            val intent = Intent(context, MediaDetailActivity::class.java)
            intent.putExtras(Bundle().apply {
                putParcelable(KEY_MEDIA, data)
            })
            context.startActivity(intent)
        }

    }

    private var _binding: ActivityMediaDetailBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(this) }
    private lateinit var offsetDialog: OpEdOffsetDialog // 片头片尾偏移设置对话框
    private lateinit var mediaDetailAdapter: MediaDetailAdapter // 长视频模式适配器
    private lateinit var videoShortItemAdapter: VideoShortItemAdapter // 短视频模式适配器
    private lateinit var mediaData: MediaData // 当前媒体库数据
    private var shortMode = false // 是否为短视频模式
    private var staggeredLayoutManager: StaggeredGridLayoutManager? = null // 短视频模式的瀑布流布局管理器
    private var emptyTips = "没有媒体，下载再用吧" // 空数据提示
    private var originalVideoData: MutableList<VideoData> = mutableListOf() // 原始完整数据，用于搜索过滤时恢复
    private var currentKeyword = "" // 当前搜索关键词

    /**
     * 瀑布流布局滚动监听器，防止滚动停止时重新排列
     */
    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            super.onScrollStateChanged(recyclerView, newState)
            // 防止瀑布流在滚动停止时重新排列
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                staggeredLayoutManager?.invalidateSpanAssignments()
            }
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (shortMode && videoShortItemAdapter.getBatchMode()) {
                exitDeleteMode()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMediaDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
        init()
    }

    override fun onStart() {
        super.onStart()
        addMenuProvider(this, this)
    }

    override fun onStop() {
        removeMenuProvider(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 非历史和收藏类型的媒体库，刷新最后播放记录和弹幕数量
        if (::mediaData.isInitialized && mediaData.type != MediaLibType.HISTORY && mediaData.type != MediaLibType.LIKE && mediaData.type != MediaLibType.PLAYLIST) {
            refreshLastPlay()
            refreshDanmakuCount()
        }
    }

    /**
     * 刷新最后播放记录
     * 使用精确刷新，只更新 lastPlay 标记变化的 item，避免全量 notifyDataSetChanged 导致瀑布流位置跳动
     */
    private fun refreshLastPlay() {
        val sourceList = LibraryCompat.loadSources(spUtil)
        LibraryCompat.loadMedia(spUtil).firstOrNull { LibraryCompat.sameMedia(it, mediaData, sourceList) }?.let { updatedMedia ->
            mediaData.playLast = updatedMedia.playLast
            if (shortMode) {
                val (oldPos, newPos) = videoShortItemAdapter.updateLastPlayPrecise(mediaData.playLast)
                if (oldPos >= 0) videoShortItemAdapter.notifyItemChanged(oldPos)
                if (newPos >= 0 && newPos != oldPos) videoShortItemAdapter.notifyItemChanged(newPos)
            } else {
                val (oldPos, newPos) = mediaDetailAdapter.updateLastPlayPrecise(mediaData.playLast)
                if (oldPos >= 0) mediaDetailAdapter.notifyItemChanged(oldPos)
                if (newPos >= 0 && newPos != oldPos) mediaDetailAdapter.notifyItemChanged(newPos)
            }
        }
    }

    /**
     * 刷新弹幕数量
     */
    private fun refreshDanmakuCount() {
        if (shortMode || !::mediaDetailAdapter.isInitialized) {
            return
        }
        val currentData = mediaDetailAdapter.getData()
        if (currentData.isEmpty()) {
            mediaDetailAdapter.updateDanmakuCount(emptyMap())
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val countMap = currentData.associate { video ->
                val count = PlayHelper.loadInfoSync(this@MediaDetailActivity, video)
                    ?.dmTrack.orEmpty()
                    .count { it.isUsableBindingTrack() }
                video.videoUrl to count
            }
            withContext(Dispatchers.Main) {
                mediaDetailAdapter.updateDanmakuCount(countMap)
                updateDanmakuSummary(countMap)
            }
        }
    }

    /**
     * 更新弹幕统计信息
     * @param countMap 视频URL到弹幕轨道数量的映射
     */
    @SuppressLint("SetTextI18n")
    private fun updateDanmakuSummary(countMap: Map<String, Int>) {
        if (shortMode || countMap.isEmpty()) {
            return
        }
        val totalMedia = countMap.size
        val boundMedia = countMap.count { it.value > 0 }
        val trackTotal = countMap.values.sum()
        val baseInfo = when (mediaData.type) {
            MediaLibType.DEFAULT, MediaLibType.LOCAL, MediaLibType.WEBDAV, MediaLibType.SMB -> "${mediaData.path} \n包含 $totalMedia 条媒体"
            MediaLibType.ONLINE -> "${mediaData.playType} \n包含 $totalMedia 条媒体"
            else -> binding.tvInfo.text.toString().lineSequence().firstOrNull().orEmpty()
        }
        binding.tvInfo.text = "$baseInfo\n已绑定 $boundMedia 条，共 $trackTotal 个弹幕轨道"
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.media_srot_menu, menu)
        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                filterVideoData(query.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                filterVideoData(newText.orEmpty())
                return true
            }
        })
        val addToPlaylistItem = menu.findItem(R.id.menu_add_to_playlist)
        addToPlaylistItem.isVisible = mediaData.type != MediaLibType.HISTORY
                && mediaData.type != MediaLibType.LIKE
                && mediaData.type != MediaLibType.PLAYLIST
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_sort -> {
                showSortPopupMenu(menuItem)
                return true
            }
            R.id.menu_add_to_playlist -> {
                // 根据当前模式选择对应适配器获取视频数据
                val videos = if (shortMode) videoShortItemAdapter.getData() else mediaDetailAdapter.getData()
                if (videos.isEmpty()) {
                    ToastUtil.showToast(this, "没有可添加的视频")
                } else {
                    PlaylistSelectDialog.show(this, videos)
                }
                return true
            }
        }
        return false
    }

    /**
     * 显示排序PopupMenu
     */
    private fun showSortPopupMenu(menuItem: MenuItem) {
        val view = binding.toolbar.findViewById<View>(menuItem.itemId) ?: binding.toolbar
        val popupMenu = PopupMenu(this, view)
        popupMenu.menu.apply {
            add(1, 101, 1, getString(R.string.sort_name_asc))
            add(2, 102, 2, getString(R.string.sort_name_desc))
            add(3, 103, 3, getString(R.string.sort_modified_asc))
            add(4, 104, 4, getString(R.string.sort_modified_desc))
        }
        popupMenu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                101 -> sortVideoData(SortType.NAME_ASC)
                102 -> sortVideoData(SortType.NAME_DESC)
                103 -> sortVideoData(SortType.MODIFIED_ASC)
                104 -> sortVideoData(SortType.MODIFIED_DESC)
            }
            true
        }
        popupMenu.show()
    }

    /**
     * 排序类型
     */
    private enum class SortType {
        NAME_ASC, NAME_DESC, MODIFIED_ASC, MODIFIED_DESC
    }

    /**
     * 排序视频数据
     * 排序作用于原始数据，然后重新应用搜索过滤
     */
    private fun sortVideoData(sortType: SortType) {
        when (sortType) {
            SortType.NAME_ASC -> {
                originalVideoData.sortBy { it.name.lowercase() }
                if (shortMode) videoShortItemAdapter.sortByName(true) else mediaDetailAdapter.sortByName(true)
            }
            SortType.NAME_DESC -> {
                originalVideoData.sortByDescending { it.name.lowercase() }
                if (shortMode) videoShortItemAdapter.sortByName(false) else mediaDetailAdapter.sortByName(false)
            }
            SortType.MODIFIED_ASC -> {
                originalVideoData.sortBy { getModifiedTime(it) }
                if (shortMode) videoShortItemAdapter.sortByModifiedTime(true) else mediaDetailAdapter.sortByModifiedTime(true)
            }
            SortType.MODIFIED_DESC -> {
                originalVideoData.sortByDescending { getModifiedTime(it) }
                if (shortMode) videoShortItemAdapter.sortByModifiedTime(false) else mediaDetailAdapter.sortByModifiedTime(false)
            }
        }
        filterVideoData(currentKeyword)
    }

    /**
     * 获取文件修改时间
     * 本地文件从文件系统获取，其他类型返回0
     */
    private fun getModifiedTime(videoData: VideoData): Long {
        return if (videoData.type == StorageType.LOCAL_STORAGE) {
            File(videoData.videoUrl).lastModified()
        } else {
            0L
        }
    }

    /**
     * 搜索过滤视频数据
     * @param keyword 搜索关键词
     */
    private fun filterVideoData(keyword: String) {
        currentKeyword = keyword
        val filtered = if (keyword.isBlank()) {
            originalVideoData.toList()
        } else {
            originalVideoData.filter { it.name.contains(keyword, ignoreCase = true) }
        }
        if (shortMode) {
            // 短视频模式使用 DiffUtil 增量更新，避免瀑布流位置跳动
            videoShortItemAdapter.updateDataWithDiff(filtered)
        } else {
            mediaDetailAdapter.updateData(filtered)
        }
    }

    /**
     * 初始化方法
     */
    private fun init() {
        initView()
        intent?.extras?.let { bundle ->
            bundle.safeGetParcelable<MediaData>(KEY_MEDIA)?.let { mediaData ->
                initData(mediaData)
            }
        }
    }

    /**
     * 初始化视图
     */
    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.toolbar.title = "媒体库详情"
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationIcon(me.lingci.lib.base.R.drawable.ic_arrow_back)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // 播放按钮点击事件
        binding.playButton.setOnClickListener {
            doPlay(mediaDetailAdapter.getData(), mediaDetailAdapter.playIndex(), mediaData.isHistory())
        }

        // 初始化片头片尾偏移对话框
        offsetDialog = OpEdOffsetDialog()
        offsetDialog.onValueListener { op, ed ->
            mediaData.opOffset = op
            mediaData.edOffset = ed
            binding.tvOffset.text = getString(R.string.hint_media_op_ed_offset, mediaData.opValue(), mediaData.edValue())
            MediaHelper.updateMedia(spUtil, mediaData)
        }

        binding.tvOffset.setOnClickListener {
            offsetDialog.arguments = OpEdOffsetDialog.buildData("", mediaData.opOffset, mediaData.edOffset)
            offsetDialog.show(supportFragmentManager, offsetDialog.tag)
        }

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.setItemViewCacheSize(20)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        mediaDetailAdapter = MediaDetailAdapter(mutableListOf())
        mediaDetailAdapter.showDanmaku(::mediaData.isInitialized && mediaData.type != MediaLibType.HISTORY && mediaData.type != MediaLibType.LIKE && mediaData.type != MediaLibType.PLAYLIST)
        binding.recyclerView.adapter = mediaDetailAdapter
        binding.fastScroller.attachRecyclerView(binding.recyclerView)
        binding.fastScroller.scrollNow()
        binding.fastScroller.changeColor("#A359F6")
        binding.fastScroller.minVisibleItemCount(10)

        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
            }
        })

        mediaDetailAdapter.onItemClick { _, position ->
            doPlay(mediaDetailAdapter.getData(), position, mediaData.isHistory())
        }

        mediaDetailAdapter.onLongItemClick { v, item, position ->
            if (mediaData.isHistory()) {
                val popupMenu = PopupMenu(this, v)
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
                    binding.tvInfo.text = "包含 ${mediaDetailAdapter.itemCount} 条历史记录"
                    binding.playButton.visibility = if (shortMode || mediaDetailAdapter.itemCount  == 0) View.GONE else View.VISIBLE
                    val historyArray = mutableListOf<VideoData>()
                    historyArray.addAll(mediaDetailAdapter.getData())
                    historyArray.reverse()
                    spUtil.historyJson = JsonUtil.toJsonString(historyArray)
                    return@setOnMenuItemClickListener true
                }
                popupMenu.show()
            } else if (mediaData.type == MediaLibType.PLAYLIST) {
                val popupMenu = PopupMenu(this, v)
                popupMenu.menu.apply {
                    add(1, 20, 1, getString(R.string.hint_remove_from_playlist))
                }
                popupMenu.setOnMenuItemClickListener {
                    if (it.itemId == 20) {
                        removeFromPlaylist(item, position)
                    }
                    return@setOnMenuItemClickListener true
                }
                popupMenu.show()
            } else if (mediaData.type == MediaLibType.LIKE) {
                val popupMenu = PopupMenu(this, v)
                popupMenu.menu.apply {
                    add(1, 21, 1, getString(R.string.hint_remove_from_like))
                }
                popupMenu.setOnMenuItemClickListener {
                    if (it.itemId == 21) {
                        removeFromLike(item, position)
                    }
                    return@setOnMenuItemClickListener true
                }
                popupMenu.show()
            } else {
                val popupMenu = PopupMenu(this, v)
                popupMenu.menu.apply {
                    add(1, 10, 1, getString(R.string.hint_add_to_playlist))
                }
                popupMenu.setOnMenuItemClickListener {
                    if (it.itemId == 10) {
                        PlaylistSelectDialog.show(this@MediaDetailActivity, listOf(item))
                    }
                    return@setOnMenuItemClickListener true
                }
                popupMenu.show()
            }
        }

        videoShortItemAdapter = VideoShortItemAdapter(mutableListOf())

        videoShortItemAdapter.onItemClick { _, position ->
            doPlay(videoShortItemAdapter.getData(), position, mediaData.isHistory())
        }

        videoShortItemAdapter.onLongItemClick { v, item, position ->
            if (mediaData.type == MediaLibType.PLAYLIST) {
                val itemActionDialog = ItemActionDialog(
                    mutableListOf(
                        ItemAction(20, getString(R.string.hint_remove_from_playlist), me.lingci.lib.base.R.color.red_700)
                    )
                ) { itemId ->
                    when (itemId) {
                        20 -> removeFromPlaylist(item, position)
                    }
                }
                itemActionDialog.show(supportFragmentManager, itemActionDialog.tag)
            } else if (mediaData.type == MediaLibType.LIKE) {
                val itemActionDialog = ItemActionDialog(
                    mutableListOf(
                        ItemAction(21, getString(R.string.hint_remove_from_like), me.lingci.lib.base.R.color.red_700)
                    )
                ) { itemId ->
                    when (itemId) {
                        21 -> removeFromLike(item, position)
                    }
                }
                itemActionDialog.show(supportFragmentManager, itemActionDialog.tag)
            } else if (item.type == StorageType.LOCAL_STORAGE || item.type == StorageType.WEBDAV || item.type == StorageType.SMB) {
                val itemActionDialog = ItemActionDialog(
                    mutableListOf(
                        ItemAction(1, "重命名"),
                        ItemAction(2, "删除模式", me.lingci.lib.base.R.color.red_700)
                    )
                ) { itemId ->
                    when (itemId) {
                        1 -> showRenameDialog(item, position)
                        2 -> enterDeleteMode(position)
                    }
                }
                itemActionDialog.show(supportFragmentManager, itemActionDialog.tag)
            }
        }

        binding.buttonSelectAll.setOnClickListener { videoShortItemAdapter.selectAll() }
        binding.buttonSelectInvert.setOnClickListener { videoShortItemAdapter.selectInvert() }
        binding.buttonExit.setOnClickListener { exitDeleteMode() }
        binding.buttonRemove.setOnClickListener { showDeleteConfirmDialog() }
    }

    private fun showRenameDialog(item: VideoData, position: Int) {
        val renameDialog = RenameDialog()
        renameDialog.arguments = RenameDialog.buildData(item.name)
        renameDialog.onRenameListener { newName ->
            renameFile(item, newName, position)
        }
        renameDialog.show(supportFragmentManager, renameDialog.tag)
    }

    private fun renameFile(item: VideoData, newName: String, position: Int) {
        lifecycleScope.launch(Dispatchers.IO) {
            val extension = item.videoUrl.substringAfterLast(".", "")
            val fullNewName = if (extension.isNotBlank()) "$newName.$extension" else newName
            val success = when (item.type) {
                StorageType.WEBDAV, StorageType.SMB -> {
                    getStorageForItem(item)?.rename(item.videoUrl, fullNewName) ?: false
                }
                else -> {
                    val oldFile = File(item.videoUrl)
                    val newFile = File(oldFile.parentFile, fullNewName)
                    oldFile.renameTo(newFile)
                }
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    val ext = item.videoUrl.substringAfterLast(".", "")
                    item.name = if (ext.isNotBlank()) "$newName.$ext" else newName
                    item.videoUrl = when (item.type) {
                        StorageType.WEBDAV, StorageType.SMB -> {
                            val basePath = item.videoUrl.substringBeforeLast("/")
                            "$basePath/$fullNewName"
                        }
                        else -> {
                            val oldFile = File(item.videoUrl)
                            File(oldFile.parentFile, fullNewName).path
                        }
                    }
                    videoShortItemAdapter.updateItem(item, position)
                    ToastUtil.showToast(this@MediaDetailActivity, "重命名成功")
                } else {
                    ToastUtil.showToast(this@MediaDetailActivity, "重命名失败")
                }
            }
        }
    }

    private fun enterDeleteMode(position: Int) {
        videoShortItemAdapter.batchMode(position)
        binding.layoutBatch.visibility = View.VISIBLE
    }

    private fun exitDeleteMode() {
        videoShortItemAdapter.exitBatchMode()
        binding.layoutBatch.visibility = View.GONE
    }

    @SuppressLint("SetTextI18n")
    private fun removeFromPlaylist(item: VideoData, position: Int) {
        LibraryCompat.removeFromPlaylist(spUtil, mediaData.id, listOf(item.beanId()))
        if (shortMode) {
            videoShortItemAdapter.removeItem(position)
        } else {
            mediaDetailAdapter.removeItem(position)
        }
        val count = if (shortMode) videoShortItemAdapter.itemCount else mediaDetailAdapter.itemCount
        binding.tvInfo.text = "包含 $count 条媒体"
        binding.playButton.visibility = if (shortMode || count == 0) View.GONE else View.VISIBLE
        ToastUtil.showToast(this, getString(R.string.hint_removed_from_playlist))
    }

    @SuppressLint("SetTextI18n")
    private fun removeFromLike(item: VideoData, position: Int) {
        val list = JsonUtil.toList<VideoData>(spUtil.likeJson!!).toMutableList()
        val index = list.indexOfFirst { it.md5() == item.md5() || it.beanId() == item.beanId() }
        if (index > -1) {
            list.removeAt(index)
            spUtil.likeJson = JsonUtil.toJsonString(list)
        }
        if (shortMode) {
            videoShortItemAdapter.removeItem(position)
        } else {
            mediaDetailAdapter.removeItem(position)
        }
        val count = if (shortMode) videoShortItemAdapter.itemCount else mediaDetailAdapter.itemCount
        binding.tvInfo.text = "包含 $count 条喜欢"
        binding.playButton.visibility = if (shortMode || count == 0) View.GONE else View.VISIBLE
        ToastUtil.showToast(this, getString(R.string.hint_removed_from_like))
    }

    private fun showDeleteConfirmDialog() {
        val selectedItems = videoShortItemAdapter.listSelect()
        if (selectedItems.isEmpty()) {
            ToastUtil.showToast(this, "请先选择要删除的项目")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除选中的 ${selectedItems.size} 个文件吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                deleteSelectedFiles(selectedItems)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteSelectedFiles(items: List<VideoData>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            items.forEach { item ->
                val success = when (item.type) {
                    StorageType.WEBDAV, StorageType.SMB -> {
                        getStorageForItem(item)?.delete(item.videoUrl) ?: false
                    }
                    else -> {
                        val file = File(item.videoUrl)
                        file.delete()
                    }
                }
                if (success) successCount++
            }
            withContext(Dispatchers.Main) {
                videoShortItemAdapter.removeSelect()
                binding.layoutBatch.visibility = View.GONE
                updateMediaInfo()
                ToastUtil.showToast(this@MediaDetailActivity, "已删除 $successCount/${items.size} 个文件")
            }
        }
    }

    private fun getStorageForItem(item: VideoData): IStorage? {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = if (item.mediaId.isNotBlank()) {
            LibraryCompat.loadMedia(spUtil).firstOrNull { it.id == item.mediaId }?.storageId
        } else {
            LibraryCompat.effectiveStorageId(mediaData, sourceList)
        }
        if (storageId.isNullOrBlank()) return null
        return sourceList.find { it.id == storageId }?.toStorage()
    }

    @SuppressLint("SetTextI18n")
    private fun updateMediaInfo() {
        val count = videoShortItemAdapter.itemCount
        val baseInfo = when (mediaData.type) {
            MediaLibType.DEFAULT, MediaLibType.LOCAL, MediaLibType.WEBDAV, MediaLibType.SMB -> "${mediaData.path} \n包含 $count 条媒体"
            MediaLibType.ONLINE -> "${mediaData.playType} \n包含 $count 条媒体"
            else -> return
        }
        binding.tvInfo.text = baseInfo
    }

    /**
     * 执行播放操作
     * @param videoData 视频列表
     * @param int 播放起始索引
     * @param history 是否为历史记录
     */
    private fun doPlay(videoData: List<VideoData>, int: Int, history: Boolean) {
        if (videoData.isEmpty()) {
            return
        }
        if (mediaData.type == MediaLibType.PLAYLIST) {
            val validIndex = findNextValidVideo(videoData, int)
            if (validIndex == -1) {
                ToastUtil.showToast(this, "没有可播放的视频")
                return
            }
            if (shortMode.not()) {
                LongVideoActivity.start(this, mediaData, ArrayList(videoData), validIndex, history)
            } else {
                ShortVideoActivity.start(this, mediaData, ArrayList(videoData), validIndex, history)
            }
        } else {
            if (shortMode.not()) {
                LongVideoActivity.start(this, mediaData, ArrayList(videoData), int, history)
            } else {
                ShortVideoActivity.start(this, mediaData, ArrayList(videoData), int, history)
            }
        }
    }

    private fun findNextValidVideo(videoData: List<VideoData>, startIndex: Int): Int {
        for (i in startIndex until videoData.size) {
            val video = videoData[i]
            if (video.type != StorageType.LOCAL_STORAGE || File(video.videoUrl).exists()) {
                return i
            }
        }
        return -1
    }

    /**
     * 初始化数据
     * @param mediaData 媒体库数据
     */
    @SuppressLint("SetTextI18n")
    private fun initData(mediaData: MediaData) {
        this.mediaData = mediaData
        // 确定是否为短视频模式
        this.shortMode = (mediaData.playMode == 0 && !spUtil.longVideoMode) || mediaData.playMode == 1 || mediaData.type == MediaLibType.LIKE
        binding.cardView.visibility = if (shortMode) View.GONE else View.VISIBLE
        binding.playButton.visibility = if (shortMode) View.GONE else View.VISIBLE
        binding.tvTitle.text = mediaData.title
        binding.tvOffset.visibility = if (mediaData.type == MediaLibType.HISTORY || mediaData.type == MediaLibType.PLAYLIST) View.GONE else View.VISIBLE
        binding.tvOffset.text = getString(R.string.hint_media_op_ed_offset, mediaData.opValue(), mediaData.edValue())
        
        when(mediaData.type) {
            MediaLibType.HISTORY -> {
                // 历史记录类型
                emptyTips = "没有记录，先去播放吧"
                binding.toolbar.title = "播放记录"
                spUtil.historyJson?.let { historyJson ->
                    logD("historyJson $historyJson")
                    JsonUtil.toList<VideoData>(historyJson).toMutableList().let { videoData ->
                        binding.tvInfo.text = "包含 ${videoData.size} 条历史记录"
                        binding.playButton.visibility = if (shortMode || videoData.isEmpty()) View.GONE else View.VISIBLE
                        videoData.reverse()
                        changeRecycler(videoData)
                    }
                }
            }
            MediaLibType.LIKE -> {
                // 收藏类型
                emptyTips = "先去短视频模式添加喜欢吧"
                binding.toolbar.title = "我的喜欢"
                spUtil.likeJson?.let { likeJson ->
                    logD("likeJson $likeJson")
                    JsonUtil.toList<VideoData>(likeJson).toMutableList().let { videoData ->
                        binding.tvInfo.text = "包含 ${videoData.size} 条喜欢"
                        binding.playButton.visibility = if (shortMode || videoData.isEmpty()) View.GONE else View.VISIBLE
                        changeRecycler(videoData)
                    }
                }
            }
            MediaLibType.DEFAULT, MediaLibType.LOCAL -> {
                // 本地类型
                binding.toolbar.title = "${mediaData.title}详情"
                binding.btnDanmakuBinding.visibility = if (shortMode) View.GONE else View.VISIBLE
                var path = mediaData.path
                if (path.isBlank()) {
                    path = FileOperator.movieFolder.path
                }
                FileOperator.getSortedFiles(File(path), FileOperator.VIDEO_EXTENSIONS).map { VideoData(it) }.toMutableList().let { videoData ->
                    binding.tvInfo.text = "${mediaData.path} \n包含 ${videoData.size} 条媒体"
                    binding.playButton.visibility = if (shortMode || videoData.isEmpty()) View.GONE else View.VISIBLE
                    logD("lastPlay", mediaData.playLast)
                    if (mediaData.playLast.isNotBlank()) {
                        videoData.forEach {
                            it.lastPlay = mediaData.playLast == it.videoUrl
                        }
                    }
                    changeRecycler(videoData)
                }
            }
            MediaLibType.ONLINE -> {
                // 在线类型
                binding.toolbar.title = "${mediaData.title}详情"
                binding.btnDanmakuBinding.visibility = if (shortMode) View.GONE else View.VISIBLE
                binding.tvInfo.text = "${mediaData.playType} \n包含 ${mediaData.items.size} 条媒体"
                val videoData = mediaData.items
                logD("lastPlay", mediaData)
                if (mediaData.playLast.isNotBlank()) {
                    videoData.forEach {
                        it.lastPlay = mediaData.playLast == it.videoUrl
                    }
                }
                changeRecycler(videoData)
                Log.d(this@MediaDetailActivity, videoData.size)
            }
            MediaLibType.WEBDAV, MediaLibType.SMB -> {
                binding.toolbar.title = "${mediaData.title}详情"
                binding.btnDanmakuBinding.visibility = if (shortMode) View.GONE else View.VISIBLE
                handleRemoteStorage()
            }
            MediaLibType.PLAYLIST -> {
                binding.toolbar.title = mediaData.title
                val videoData = LibraryCompat.sortByParentPath(mediaData.items.toMutableList())
                binding.tvInfo.text = "包含 ${videoData.size} 条媒体"
                binding.playButton.visibility = if (shortMode || videoData.isEmpty()) View.GONE else View.VISIBLE
                if (mediaData.playLast.isNotBlank()) {
                    videoData.forEach {
                        it.lastPlay = mediaData.playLast == it.videoUrl
                    }
                }
                changeRecycler(videoData)
            }
        }
        binding.ivThumb.setCover(mediaData)
        binding.btnDanmakuBinding.visibility = if (shortMode || mediaData.type == MediaLibType.HISTORY || mediaData.type == MediaLibType.LIKE || mediaData.type == MediaLibType.PLAYLIST) View.GONE else View.VISIBLE
        binding.btnDanmakuBinding.setOnClickListener {
            DanmakuBindingActivity.start(this, mediaDetailAdapter.getData(), mediaData.id)
        }
    }

    /**
     * 切换RecyclerView的布局和适配器
     * @param videoData 视频数据列表
     */
    private fun changeRecycler(videoData: MutableList<VideoData>) {
        originalVideoData = videoData.toMutableList()
        currentKeyword = ""
        // 异步扫描视频缩略图
        lifecycleScope.launch(Dispatchers.IO) {
            logD("scanVideoThumb")
            MediaManger.scanVideoThumb(baseContext, videoData.toList())
        }
        if (shortMode) {
            // 瀑布流高度不固定，关闭 hasFixedSize 优化，避免布局优化失效导致位置跳动
            binding.recyclerView.setHasFixedSize(false)
            // 短视频模式 - 瀑布流布局
            staggeredLayoutManager = StaggeredGridLayoutManager(3, StaggeredGridLayoutManager.VERTICAL).apply {
                gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
            }
            binding.recyclerView.layoutManager = staggeredLayoutManager
            binding.recyclerView.removeOnScrollListener(onScrollListener)
            binding.recyclerView.addOnScrollListener(onScrollListener)
            videoShortItemAdapter.updateData(videoData)
            binding.recyclerView.adapter = videoShortItemAdapter
            binding.fastScroller.attachRecyclerView(binding.recyclerView)
            if (mediaData.type != MediaLibType.HISTORY && mediaData.type != MediaLibType.LIKE && mediaData.type != MediaLibType.PLAYLIST) {
                val lastPlayPosition = videoShortItemAdapter.getLastPlayPosition()
                if (lastPlayPosition >= 0) {
                    binding.recyclerView.scrollToPosition(lastPlayPosition)
                }
            }
        } else {
            // 线性布局 item 高度一致，保持 hasFixedSize 优化
            binding.recyclerView.setHasFixedSize(true)
            // 长视频模式 - 线性布局
            binding.recyclerView.layoutManager = LinearLayoutManager(this)
            mediaDetailAdapter.updateData(videoData)
            binding.recyclerView.adapter = mediaDetailAdapter
            binding.fastScroller.attachRecyclerView(binding.recyclerView)
            refreshDanmakuCount()
        }
        binding.recyclerView.setEmptyView(0, emptyTips)
    }

    /**
     * 处理远程存储类型媒体库
     */
    private fun handleRemoteStorage() {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = LibraryCompat.effectiveStorageId(mediaData, sourceList)
        if (storageId == null) {
            ToastUtil.showToast(this, "该媒体库来自旧数据，尚未绑定具体资源源")
            return
        }
        val source = sourceList.find { it.id == storageId }
        if (source == null) {
            ToastUtil.showToast(this, "资源源不存在")
            return
        }
        val storage = source.toStorage() ?: return
        changeRecycler(mutableListOf())
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val testConnect = storage.testConnect()
            withContext(Dispatchers.Main) {
                hideLoading()
                if (testConnect.not()) {
                    ToastUtil.showToast(this@MediaDetailActivity, "资源库连接失败")
                    finish()
                } else {
                    // 异步加载远程文件列表
                    storage.listFile(mediaData.path, false)
                        .onEach { item ->
                            if (item.isFile && item.name.isVideo()) {
                                withContext(Dispatchers.Main) {
                                    val videoData = VideoData()
                                    videoData.name = item.name
                                    videoData.videoUrl = storage.fullPath(item.path)
                                    videoData.putToken(storage.getToken())
                                    videoData.type = source.storageType()
                                    videoData.parentPath = item.path.substringBeforeLast("/").substringAfterLast("/")
                                    videoData.mediaId = mediaData.id
                                    videoData.id = videoData.md5()
                                    if (shortMode) {
                                        videoShortItemAdapter.addItem(videoData)
                                    } else {
                                        mediaDetailAdapter.addItem(videoData)
                                    }
                                }
                            }
                        }.onCompletion {
                            withContext(Dispatchers.Main) {
                                mediaDetailAdapter.sorted()
                                videoShortItemAdapter.sorted()
                                originalVideoData = if (shortMode) videoShortItemAdapter.getData().toMutableList() else mediaDetailAdapter.getData().toMutableList()
                                currentKeyword = ""
                                if (mediaData.playLast.isNotBlank()) {
                                    if (shortMode) {
                                        videoShortItemAdapter.getData().forEach {
                                            it.lastPlay = mediaData.playLast == it.videoUrl
                                        }
                                        videoShortItemAdapter.notifyAllChanged()
                                    } else {
                                        mediaDetailAdapter.updateLastPlay(mediaData.playLast)
                                    }
                                }
                                if (shortMode && mediaData.type != MediaLibType.HISTORY && mediaData.type != MediaLibType.LIKE) {
                                    val lastPlayPosition = videoShortItemAdapter.getLastPlayPosition()
                                    if (lastPlayPosition >= 0) {
                                        binding.recyclerView.scrollToPosition(lastPlayPosition)
                                    }
                                }
                                refreshDanmakuCount()
                                hideLoading()
                            }
                        }.collect {}
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

}
