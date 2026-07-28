package me.lingci.dy.player.ui.media

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.FragmentMediaBinding
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.MediaTypeEntity
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.main.BaseTransitionFragment
import me.lingci.dy.player.ui.media_add.MediaLinkAddDialog
import me.lingci.dy.player.ui.media_add.MediaManagerDialog
import me.lingci.dy.player.ui.media_add.MediaManagerViewModel
import me.lingci.dy.player.ui.media_detail.MediaDetailActivity
import me.lingci.dy.player.ui.media_detail.MediaDetailDialog
import me.lingci.dy.player.ui.media_full.MediaFullActivity
import me.lingci.dy.player.ui.media_full.MediaItemAdapter
import me.lingci.dy.player.ui.media_scan.MediaScanActivity
import me.lingci.dy.player.ui.playlist.PlaylistActivity
import me.lingci.dy.player.ui.playlist.PlaylistSelectDialog
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.ui.source.ItemAction
import me.lingci.dy.player.ui.source.ItemActionDialog
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.MediaManger
import me.lingci.dy.player.util.AppUtil
import java.io.FileOutputStream
import me.lingci.lib.base.dailog.ValueSetDialog
import me.lingci.lib.base.okhttp.httpGet
import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.notStartDot
import java.io.File

/**
 * 媒体库
 */
class MediaFragment : BaseTransitionFragment(), MenuProvider {

    private var _binding: FragmentMediaBinding? = null
    private val binding get() = _binding!!
    private val mediaViewModel: MediaViewModel by activityViewModels()
    private val managerViewModel: MediaManagerViewModel by activityViewModels()
    private val spUtil by lazy { SpUtil(requireContext()) }

    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaFullLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaScanLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaTypeItemAdapter: MediaTypeItemAdapter
    private lateinit var mediaItemAdapter: MediaItemAdapter
    private lateinit var mediaManagerDialog: MediaManagerDialog
    private lateinit var mediaDetailDialog: MediaDetailDialog
    private lateinit var mediaLinkAddDialog: MediaLinkAddDialog
    private lateinit var valueSetDialog: ValueSetDialog
    private var processCoversJob: Job? = null
    private var shuffleStates: MutableMap<String, MediaShuffleState> = mutableMapOf()

    private var searchItem: MenuItem? = null
    private var searchView: SearchView? = null
    private var lastCoverRatio: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onResume() {
        super.onResume()
        val currentRatio = spUtil.coverRatio
        if (lastCoverRatio != null && lastCoverRatio != currentRatio) {
            mediaItemAdapter.changeCoverRatio(currentRatio!!)
            mediaTypeItemAdapter.changeCoverRatio(currentRatio)
        }
        val sourceList = LibraryCompat.loadSources(spUtil)
        mediaItemAdapter.setSourceList(sourceList)
        mediaTypeItemAdapter.setSourceList(sourceList)
        // 同步启动自动播放媒体库 id，确保从 MediaFullActivity 返回时角标正确
        mediaItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
        mediaTypeItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
        lastCoverRatio = currentRatio
        // 强制从 SharedPreferences 刷新数据，确保与 MediaFullActivity 的修改同步
        refreshDataFromSp()
    }

    private fun init() {
        clearCache()
        initResult()
        initViewModel()
        initView()
    }

    private fun initResult() {
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            it?.let { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.let { bundle ->
                        bundle.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)
                            ?.let { paths ->
                                handleLinkMedia(FileOperator.readText(paths.first()))
                            }
                    }
                }
            }
        }
        mediaFullLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                refreshDataFromSp()
            }
        }
        mediaScanLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                refreshDataFromSp()
            }
        }
    }

    private fun initViewModel() {
        mediaViewModel.text.observe(viewLifecycleOwner) {
            Log.d(this@MediaFragment, it)
        }
    }

    override fun onStart() {
        super.onStart()
        requireActivity().addMenuProvider(this, viewLifecycleOwner)
        initData(null)
    }

    override fun onStop() {
        processCoversJob?.cancel()
        processCoversJob = null
        requireActivity().removeMenuProvider(this)
        super.onStop()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.media_add_menu, menu)

        searchItem = menu.findItem(R.id.menu_search)
        searchItem?.isVisible = spUtil.newHome.not()
        searchView = searchItem?.actionView as SearchView

        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                // 执行实时搜索
                searchData(newText.orEmpty())
                return true
            }
        })
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (searchView?.isIconified!!.not() && menuItem.itemId != R.id.menu_search) {
            searchItem?.collapseActionView()
        }
        when (menuItem.itemId) {
            R.id.menu_add_link -> {
                if (!mediaLinkAddDialog.isAdded) {
                    mediaLinkAddDialog.setMedia(null)
                    mediaLinkAddDialog.show(childFragmentManager, mediaLinkAddDialog.tag)
                }
            }

            R.id.menu_input_link -> {
                if (!valueSetDialog.isAdded) {
                    valueSetDialog.arguments = ValueSetDialog.buildBundle(
                        "在线媒体库导入", "", "输入符合格式的在线链接", 1
                    )
                    valueSetDialog.show(childFragmentManager, valueSetDialog.tag)
                }
            }

            R.id.menu_local_link -> {
                FileSelectorActivity.startSingle(
                    requireActivity(), arrayListOf(".txt"), resultLauncher
                )
            }

            R.id.menu_add -> {
                if (!mediaManagerDialog.isAdded) {
                    managerViewModel.updateMediaBean(MediaData())
                    mediaManagerDialog.show(childFragmentManager, mediaManagerDialog.tag)
                }
                return true
            }

            R.id.menu_scan -> {
                mediaScanLauncher.launch(
                    MediaScanActivity.intent(
                        requireContext(),
                        autoStart = false,
                        filterBySelectedStorage = true
                    )
                )
            }
        }
        return false
    }

    private fun searchData(keyword: String) {
        processMediaData(mediaViewModel.listMedia(keyword))
    }

    private fun initData(dataSet: MutableList<MediaData>?) {
        var list = dataSet
        if (list == null) {
            list = LibraryCompat.loadMedia(spUtil)
            val beforeNum = list.size
            list = list.filter { it.type > MediaLibType.DEFAULT }.toMutableList()
            if (list.size != beforeNum) {
                LibraryCompat.saveMedia(spUtil, list)
            }
        }
        // 修复：无论列表是否为空，都更新 ViewModel 和 UI，确保数据同步
        mediaViewModel.setMediaList(list)
        if (list.isNotEmpty()) {
            processMediaData(list)
            processCovers(list)
        } else {
            // 清空 UI
            if (spUtil.newHome) {
                mediaTypeItemAdapter.updateData(mutableListOf())
            } else {
                mediaItemAdapter.updateData(mutableListOf())
            }
        }

        scanMedia()
    }

    private fun scanMedia() {
        if (spUtil.firstScanMovie) {
            spUtil.firstScanMovie = false
            mediaScanLauncher.launch(
                MediaScanActivity.intent(
                    requireContext(),
                    autoStart = true,
                    filterBySelectedStorage = false
                )
            )
        }
    }

    private fun processMediaData(list: MutableList<MediaData>) {
        val sourceList = LibraryCompat.loadSources(spUtil)
        mediaItemAdapter.setSourceList(sourceList)
        mediaTypeItemAdapter.setSourceList(sourceList)
        // 同步启动自动播放媒体库 id，刷新卡片角标
        mediaItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
        mediaTypeItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
        shuffleStates = LibraryCompat.loadShuffleStates(spUtil)
        if (spUtil.newHome) {
            list.groupBy { item ->
                if (isRemoteMedia(item.type)) {
                    "${item.type.value}:${LibraryCompat.mediaBucketStorageId(item, sourceList)}"
                } else {
                    "${item.type.value}:"
                }
            }.let { map ->
                val typeList: MutableList<MediaTypeEntity> = mutableListOf()
                map.forEach { (_, data) ->
                    val first = data.first()
                    val storageId = LibraryCompat.mediaBucketStorageId(first, sourceList).orEmpty()
                    val title = when (first.type) {
                        MediaLibType.LOCAL -> "本地媒体库"
                        MediaLibType.ONLINE -> "在线媒体库"
                        MediaLibType.WEBDAV -> {
                            if (storageId == LibraryCompat.LEGACY_UNBOUND_STORAGE_ID) {
                                "WEBDAV媒体库(旧)"
                            } else {
                                sourceList.find { it.id == storageId }?.title ?: "WEBDAV媒体库"
                            }
                        }
                        MediaLibType.SMB -> sourceList.find { it.id == storageId }?.title ?: "SMB媒体库"
                        else -> "默认"
                    }
                    val allMedia = LibraryCompat.sortMediaByDefault(data).toMutableList()
                    val categoryKey = "${first.type.value}:${storageId}"
                    val shuffleState = shuffleStates[categoryKey]
                    val displayList = if (shuffleState != null && shuffleState.currentDisplayIds.isNotEmpty()) {
                        val idSet = shuffleState.currentDisplayIds.toSet()
                        allMedia.filter { it.id in idSet }.toMutableList()
                    } else {
                        allMedia.take(6).toMutableList()
                    }
                    typeList.add(
                        MediaTypeEntity(
                            type = first.type,
                            storageId = storageId,
                            title = title,
                            size = data.size,
                            mediaList = displayList,
                            allMediaList = allMedia
                        )
                    )
                }
                // 清理失效的 shuffle 状态
                val validKeys = typeList.map { "${it.type.value}:${it.storageId}" }.toSet()
                shuffleStates.keys.toList().forEach { key ->
                    if (key !in validKeys) shuffleStates.remove(key)
                }
                shuffleStates.forEach { (key, state) ->
                    val validIds = typeList.find { "${it.type.value}:${it.storageId}" == key }
                        ?.allMediaList?.map { it.id }?.toSet() ?: emptySet()
                    shuffleStates[key] = state.copy(
                        selectedIds = state.selectedIds.intersect(validIds),
                        currentDisplayIds = state.currentDisplayIds.filter { it in validIds }
                    )
                }
                mediaTypeItemAdapter.setShuffleStates(shuffleStates)
                // 对分类列表排序，本地媒体库优先
                typeList.sortWith(compareBy<MediaTypeEntity> {
                    it.type != MediaLibType.LOCAL
                }.thenBy {
                    it.type.value
                })
                mediaTypeItemAdapter.updateData(typeList)
            }
        } else {
            mediaItemAdapter.updateData(LibraryCompat.sortMediaByDefault(list))
        }
    }

    // 为 LOCAL 类型的媒体库自动生成首个视频的缩略图并绑定 showFile
    // coverType == DEFAULT 时用户选择默认封面，不自动匹配，有cover显示cover，没有占位图
    // coverType == CUSTOM 时用户已自选封面，不自动匹配
    // coverType == AUTO 时自动匹配（用户选择自动模式，showFile 为空时自动获取）
    private fun processCovers(list: MutableList<MediaData>) {
        processCoversJob?.cancel()
        val appContext = context?.applicationContext ?: return
        processCoversJob = lifecycleScope.launch(Dispatchers.IO) {
            val coverUpdates = mutableMapOf<String, String>()
            for (media in list) {
                if (!isActive) return@launch
                // 只处理 LOCAL 类型且 coverType 为 AUTO 的媒体库
                if (media.type != MediaLibType.LOCAL) continue
                // 跳过 DEFAULT（用户选择默认，不自动匹配）
                if (media.coverType == CoverType.DEFAULT) continue
                // 跳过 CUSTOM（用户自选封面）
                if (media.coverType == CoverType.CUSTOM) continue
                // 跳过已有 showFile 的 AUTO 类型（已匹配过）
                if (media.coverType == CoverType.AUTO && media.showFile.isNotBlank()) continue

                // 检查 cover.jpg 是否存在
                val coverFile = File(media.coverPath())
                if (coverFile.exists()) continue

                // 获取第一个视频文件
                val videos = FileOperator.getSortedFiles(File(media.path), FileOperator.VIDEO_EXTENSIONS)
                if (videos.isEmpty()) continue

                val firstVideo = videos[0]
                val videoData = VideoData(firstVideo)
                val thumbFile = AppUtil.buildThumbFile(appContext, videoData.md5())

                if (thumbFile.exists()) {
                    if (isUsableCoverThumb(thumbFile)) {
                        media.showFile = thumbFile.absolutePath
                        media.coverType = CoverType.AUTO
                        coverUpdates[media.id] = thumbFile.absolutePath
                        continue
                    }
                    thumbFile.delete()
                }

                // 缩略图不存在或缓存是黑图时，尝试首帧并回退到 1s/3s
                try {
                    val bitmap = selectCoverFrame(firstVideo.absolutePath)
                    if (bitmap != null) {
                        thumbFile.parentFile?.mkdirs()
                        FileOutputStream(thumbFile).use { fos ->
                            bitmap.compress(AppUtil.COMPRESS_FORMAT, 70, fos)
                        }
                        bitmap.recycle()
                        media.showFile = thumbFile.absolutePath
                        media.coverType = CoverType.AUTO
                        coverUpdates[media.id] = thumbFile.absolutePath
                    }
                } catch (e: Exception) {
                    thumbFile.delete()
                }
            }

            // 如果有更新，基于最新 SP 数据按 id 合并，避免旧快照回写已删除媒体库
            if (coverUpdates.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (!isAdded || !isActive) return@withContext
                    val latest = LibraryCompat.loadMedia(spUtil)
                    var changed = false
                    latest.forEach { media ->
                        val showFile = coverUpdates[media.id] ?: return@forEach
                        if (media.showFile != showFile || media.coverType != CoverType.AUTO) {
                            media.showFile = showFile
                            media.coverType = CoverType.AUTO
                            changed = true
                        }
                    }
                    if (changed) {
                        LibraryCompat.saveMedia(spUtil, latest)
                    }
                    refreshDataFromSp()
                }
            }
        }
    }

    private fun selectCoverFrame(videoPath: String): Bitmap? {
        val fallbackTimes = longArrayOf(0L, 1_000_000L, 3_000_000L)
        for (timeUs in fallbackTimes) {
            val bitmap = if (timeUs == 0L) {
                MediaManger.getVideoFirstFrame(videoPath)
            } else {
                MediaManger.getVideoFrame(videoPath, timeUs)
            } ?: continue
            if (!isMostlyBlack(bitmap)) {
                return bitmap
            }
            bitmap.recycle()
        }
        return null
    }

    private fun isUsableCoverThumb(thumbFile: File): Boolean {
        val bitmap = BitmapFactory.decodeFile(thumbFile.absolutePath) ?: return false
        return try {
            !isMostlyBlack(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    @SuppressLint("UseKtx")
    @Suppress("KotlinConstantConditions")
    private fun isMostlyBlack(bitmap: Bitmap): Boolean {
        if (bitmap.width <= 0 || bitmap.height <= 0) return true
        val sampleColumns = 12
        val sampleRows = 12
        var blackPixels = 0
        var totalPixels = 0
        for (row in 0 until sampleRows) {
            val y = if (sampleRows == 1) 0 else row * (bitmap.height - 1) / (sampleRows - 1)
            for (column in 0 until sampleColumns) {
                val x = if (sampleColumns == 1) 0 else column * (bitmap.width - 1) / (sampleColumns - 1)
                val pixel = bitmap.getPixel(x, y)
                val luminance = (Color.red(pixel) * 54 + Color.green(pixel) * 183 + Color.blue(pixel) * 19) / 256
                if (luminance < 24) {
                    blackPixels++
                }
                totalPixels++
            }
        }
        return blackPixels * 100 >= totalPixels * 85
    }

    private fun initView() {
        binding.cardMediaHistory.setOnClickListener {
            if (spUtil.videoDetailMode) {
                //mediaDetailDialog.setMedia(MediaData(title = "播放历史", type = MediaLibType.HISTORY))
                //mediaDetailDialog.show(childFragmentManager, mediaManagerDialog.tag)
                MediaDetailActivity.start(requireContext(), MediaData(title = "播放历史", type = MediaLibType.HISTORY))
            } else {
                handleHistory(spUtil.longVideoMode)
            }
        }
        binding.cardMediaLike.setOnClickListener {
            if (spUtil.videoDetailMode) {
                MediaDetailActivity.start(requireContext(), MediaData(title = "我的喜欢", type = MediaLibType.LIKE))
            } else {
                handleLike(false)
            }
        }
        binding.cardMediaPlaylist.setOnClickListener {
            PlaylistActivity.start(requireContext())
        }

        // 本地媒体库管理
        mediaManagerDialog = MediaManagerDialog.newInstance(null) { media, update ->
            Log.d(this@MediaFragment, "media", media, "update", update)
            /*if (!update && media.path == FileOperator.movieFolder.path) {
                ToastUtil.showToast(requireContext(), "媒体库已存在")
                return@newInstance
            }*/
            val sourceList = LibraryCompat.loadSources(spUtil)
            LibraryCompat.loadMedia(spUtil).let { list ->
                if (media.id.isBlank()) {
                    media.id = LibraryCompat.mediaId(media, sourceList)
                }
                val index = list.indexOfFirst { LibraryCompat.sameMedia(it, media, sourceList) }
                if (index != -1 && update.not()) {
                    ToastUtil.showToast(requireContext(), "媒体库已存在")
                    return@newInstance
                }
                if (index != -1 && update) {
                    list.removeAt(index)
                }
                list.add(media)
                LibraryCompat.saveMedia(spUtil, list)
                initData(list)
            }
        }
        // 媒体库详情
        mediaDetailDialog = MediaDetailDialog()
        // 在线媒体库新增
        mediaLinkAddDialog = MediaLinkAddDialog()
        mediaLinkAddDialog.onSave { media, updated ->
            saveOnlineMediaData(media, updated)
        }
        // 在线媒体库导入
        valueSetDialog = ValueSetDialog()
        valueSetDialog.onValueListener{ _, value ->
            if (value.isBlank() || !value.startsWith("http")) {
                ToastUtil.showToast(requireContext(), "非有效的媒体库导入链接")
                return@onValueListener
            }
            showLoading()
            lifecycleScope.launch(Dispatchers.IO) {
                httpGet(value)
                    .unsafe()
                    .execute()
                    .onSuccess { response ->
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                handleLinkMedia(response)
                                hideLoading()
                            }
                        }
                    }
                    .onFailure { e ->
                        Log.d(this@MediaFragment, "import media failed", e)
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                ToastUtil.showToast(requireContext(), "导入失败")
                                hideLoading()
                            }
                        }
                    }
            }
        }
        // 旧UI
        mediaItemAdapter = MediaItemAdapter(mutableListOf(), true, spUtil.coverRatio!!)
        mediaItemAdapter.setSourceList(LibraryCompat.loadSources(spUtil))
        mediaItemAdapter.onItemClick { item, position ->
            onMediaItemClick(item, position, "")
        }
        mediaItemAdapter.onLongItemClick { item, position ->
            onMediaLongItemClick(item, position, "")
        }
        // 新分类UI
        mediaTypeItemAdapter = MediaTypeItemAdapter(mutableListOf(), spUtil.coverRatio!!)
        mediaTypeItemAdapter.setSourceList(LibraryCompat.loadSources(spUtil))
        mediaTypeItemAdapter.resetLayout(
            if (isOrientationPortraitOfSysMetrics())
                Configuration.ORIENTATION_UNDEFINED else Configuration.ORIENTATION_LANDSCAPE
        )
        mediaTypeItemAdapter.onMediaItemClick { item, storageId, position ->
            Log.d(this@MediaFragment, position)
            onMediaItemClick(item, position, storageId)
        }
        mediaTypeItemAdapter.onMoreItemClick { item, position ->
            mediaFullLauncher.launch(MediaFullActivity.intent(requireContext(), item.type.ordinal, item.storageId))
        }
        mediaTypeItemAdapter.onMediaLongItemClick { item, storageId, position ->
            onMediaLongItemClick(item, position, storageId)
        }
        mediaTypeItemAdapter.onRefreshClick { item, position ->
            handleRefreshMedia(item, position)
        }
        mediaTypeItemAdapter.onResetClick { item, position ->
            handleResetMedia(item, position)
        }
        if (spUtil.newHome) {
            binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
            binding.recyclerView.adapter = mediaTypeItemAdapter
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), if (isOrientationPortraitOfSysMetrics()) 3 else 7)
            binding.recyclerView.adapter = mediaItemAdapter
        }
    }

    private fun onMediaItemClick(item: MediaData, position: Int, storageId: String) {
        if (spUtil.videoDetailMode) {
            //mediaDetailDialog.setMedia(item)
            //mediaDetailDialog.show(childFragmentManager, mediaManagerDialog.tag)
            MediaDetailActivity.start(requireContext(), item)
            return
        }
        val longVideoMode = (spUtil.longVideoMode && item.playMode == 0) || item.playMode == 2
        if (item.type == MediaLibType.HISTORY) {
            handleHistory(longVideoMode)
        }
        if (item.type in MediaLibType.DEFAULT..MediaLibType.LOCAL) {
            handleMediaLocal(item, longVideoMode)
        }
        if (item.type == MediaLibType.ONLINE) {
            startPlayer(item, item.items, longVideoMode)
        }
        if (isRemoteMedia(item.type)) {
            handleMediaRemote(item, longVideoMode)
        }
    }

    private fun onMediaLongItemClick(item: MediaData, position: Int, storageId: String) {
        if (item.type > MediaLibType.DEFAULT) {
            val pinAction = if (item.pinned) {
                ItemAction(5, "取消置顶")
            } else {
                ItemAction(5, "置顶")
            }
            // 启动自动播放：根据当前媒体库是否已设为自动播放，显示"设为"或"取消"
            val autoPlayAction = if (spUtil.autoPlayMediaId == item.id) {
                ItemAction(6, "取消启动自动播放")
            } else {
                ItemAction(6, "设为启动自动播放")
            }
            val itemActionDialog = ItemActionDialog(
                mutableListOf(
                    ItemAction(1, "编辑媒体库"),
                    pinAction,
                    autoPlayAction,
                    ItemAction(4, getString(R.string.hint_add_to_playlist)),
                    ItemAction(3, "删除媒体库", me.lingci.lib.base.R.color.red_700)
                )
            ) { itemId ->
                when (itemId) {
                    1 -> {
                        when (item.type) {
                            MediaLibType.LOCAL -> {
                                managerViewModel.updateMediaBean(item)
                                mediaManagerDialog.show(
                                    childFragmentManager, mediaManagerDialog.tag
                                )
                            }
                            MediaLibType.ONLINE -> {
                                mediaLinkAddDialog.setMedia(item)
                                mediaLinkAddDialog.show(
                                    childFragmentManager, mediaLinkAddDialog.tag
                                )
                            }
                            else -> {

                            }
                        }
                    }

                    5 -> {
                        handleTogglePin(item)
                    }

                    6 -> {
                        // 切换启动自动播放设置：已设则取消，未设则设为当前（单选覆盖）
                        if (spUtil.autoPlayMediaId == item.id) {
                            spUtil.autoPlayMediaId = ""
                            ToastUtil.showToast(requireContext(), "已取消启动自动播放")
                        } else {
                            spUtil.autoPlayMediaId = item.id
                            // 互斥：清空播放列表自动播放设置
                            spUtil.autoPlayPlaylistId = ""
                            ToastUtil.showToast(requireContext(), "已设为启动自动播放")
                        }
                        // 刷新列表以更新角标
                        mediaItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
                        mediaTypeItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
                    }

                    4 -> {
                        addMediaToPlaylist(item)
                    }

                    3 -> {
                        deleteMediaData(item)
                    }
                }
            }
            itemActionDialog.show(childFragmentManager, itemActionDialog.tag)
        }
    }

    private fun handleLinkMedia(linkString: String) {
        MediaHelper.createMediaFromString(linkString).let { mediaData ->
            if (mediaData.items.isNotEmpty()) {
                saveOnlineMediaData(mediaData, false)
            } else {
                ToastUtil.showToast(requireContext(), "导入失败")
            }
        }
    }

    private fun deleteMediaData(media: MediaData) {
        lifecycleScope.launch(Dispatchers.Main) {
            val sourceList = LibraryCompat.loadSources(spUtil)
            val list = LibraryCompat.loadMedia(spUtil)
            val position = list.indexOfFirst { LibraryCompat.sameMedia(it, media, sourceList) }
            Log.d(this@MediaFragment, "deleteMediaData", "position", position)
            if (position != -1) {
                list.removeAt(position)
                LibraryCompat.saveMedia(spUtil, list)
                // 若删除的是启动自动播放媒体库，清空设置避免启动时找不到
                if (spUtil.autoPlayMediaId == media.id) {
                    spUtil.autoPlayMediaId = ""
                }
                initData(list)
            }
        }
    }

    private fun addMediaToPlaylist(media: MediaData) {
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = when (media.type) {
                MediaLibType.DEFAULT, MediaLibType.LOCAL -> {
                    var path = media.path
                    if (path.isBlank()) path = FileOperator.movieFolder.path
                    FileOperator.getSortedFiles(File(path), FileOperator.VIDEO_EXTENSIONS).map { VideoData(it) }
                }
                MediaLibType.ONLINE -> {
                    media.items
                }
                MediaLibType.WEBDAV, MediaLibType.SMB -> loadRemoteVideos(media)
                else -> emptyList()
            }
            withContext(Dispatchers.Main) {
                if (videos.isEmpty()) {
                    ToastUtil.showToast(requireContext(), "该媒体库没有视频")
                } else {
                    PlaylistSelectDialog.show(requireContext(), videos)
                }
            }
        }
    }

    // 保存在线媒体库
    private fun saveOnlineMediaData(media: MediaData, updated: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            val sourceList = LibraryCompat.loadSources(spUtil)
            val list = LibraryCompat.loadMedia(spUtil)
            if (media.id.isBlank()) {
                media.id = LibraryCompat.mediaId(media, sourceList)
            }
            val position = list.indexOfFirst { LibraryCompat.sameMedia(it, media, sourceList) }
            Log.d(
                this@MediaFragment, "saveOnlineMediaData", "position", position, "updated", updated
            )
            if (position != -1 && !updated) {
                ToastUtil.showToast(requireContext(), "媒体库已存在")
            } else {
                if (updated) {
                    list.removeAt(position)
                }
                list.add(media)
                LibraryCompat.saveMedia(spUtil, list)
                initData(list)
            }
        }
    }

    // 处理历史记录
    private fun handleHistory(longVideoMode: Boolean) {
        spUtil.historyJson?.let { historyJson ->
            JsonUtil.toList<VideoData>(historyJson).toMutableList().let {
                if (it.isEmpty()) {
                    ToastUtil.showToast(requireContext(), "暂无历史记录")
                } else {
                    it.reverse()
                    if (longVideoMode) {
                        LongVideoActivity.start(requireContext(), ArrayList(it), 0, true)
                    } else {
                        ShortVideoActivity.start(requireContext(), ArrayList(it), 0, true)
                    }
                }
            }
        }
    }

    // 处理喜欢
    private fun handleLike(longVideoMode: Boolean) {
        spUtil.likeJson?.let { historyJson ->
            JsonUtil.toList<VideoData>(historyJson).toMutableList().let {
                if (it.isEmpty()) {
                    ToastUtil.showToast(requireContext(), "暂无喜欢")
                } else {
                    if (longVideoMode) {
                        LongVideoActivity.start(requireContext(), ArrayList(it), 0, false)
                    } else {
                        ShortVideoActivity.start(requireContext(), ArrayList(it), 0, false)
                    }
                }
            }
        }
    }

    // 处理本地媒体库
    private fun handleMediaLocal(item: MediaData, longVideoMode: Boolean) {
        if (item.path.isBlank()) {
            item.path = FileOperator.movieFolder.path
        }
        item.path.let { path ->
            FileOperator.getSortedFiles(File(path), FileOperator.VIDEO_EXTENSIONS).let {
                if (it.isEmpty()) {
                    ToastUtil.showToast(
                        requireContext(), if (item.type == MediaLibType.DEFAULT) "默认资源库为空" else "资源库为空"
                    )
                } else {
                    it.map { file ->
                        VideoData(file)
                    }.let { videos ->
                        startPlayer(item, videos, longVideoMode)
                    }
                }
            }
        }
    }

    private fun startPlayer(media: MediaData, videos: List<VideoData>, longVideoMode: Boolean) {
        if (longVideoMode) {
            LongVideoActivity.start(
                requireContext(), media, ArrayList(videos), 0, false
            )
        } else {
            ShortVideoActivity.start(
                requireContext(), media, ArrayList(videos), 0, false
            )
        }
    }

    // 处理远程存储媒体库
    private fun handleMediaRemote(item: MediaData, longVideoMode: Boolean) {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = LibraryCompat.effectiveStorageId(item, sourceList)
        if (storageId == null) {
            ToastUtil.showToast(requireContext(), "该媒体库来自旧数据，尚未绑定具体资源源")
            return
        }
        val source = sourceList.find { it.id == storageId }
        if (source == null) {
            ToastUtil.showToast(requireContext(), "资源源不存在")
            return
        }
        val storage = source.toStorage() ?: return
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val testConnect = storage.testConnect()
            val videos = if (testConnect) loadRemoteVideos(item) else emptyList()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                hideLoading()
                if (testConnect.not()) {
                    ToastUtil.showToast(requireContext(), "资源库连接失败")
                } else if (videos.isEmpty()) {
                    ToastUtil.showToast(requireContext(), "资源库为空")
                } else {
                    startPlayer(item, videos, longVideoMode)
                }
            }
        }
    }

    private suspend fun loadRemoteVideos(item: MediaData): List<VideoData> {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = LibraryCompat.effectiveStorageId(item, sourceList) ?: return emptyList()
        val source = sourceList.find { it.id == storageId } ?: return emptyList()
        val storage = source.toStorage() ?: return emptyList()
        return storage.listFile(item.path, false)
            .filter { file -> file.isFile && file.name.isVideo() }
            .map { file ->
                VideoData().apply {
                    name = file.name
                    videoUrl = storage.fullPath(file.path)
                    type = source.storageType()
                    parentPath = file.path.substringBeforeLast("/").substringAfterLast("/")
                    putToken(storage.getToken())
                }
            }.toList()
    }

    private fun isRemoteMedia(type: MediaLibType): Boolean {
        return type == MediaLibType.WEBDAV || type == MediaLibType.SMB
    }

    private fun resetLayout(orientation: Int) {
        binding.recyclerView.layoutManager = GridLayoutManager(
            context, if (orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 3
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (spUtil.newHome) {
            mediaTypeItemAdapter.resetLayout(newConfig.orientation)
        } else {
            resetLayout(newConfig.orientation)
        }
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroyView() {
        processCoversJob?.cancel()
        processCoversJob = null
        super.onDestroyView()
        _binding = null
    }

    fun refresh() {
        refreshDataFromSp()
        mediaViewModel.setText("")
    }

    /**
     * 从 SharedPreferences 强制刷新数据
     * 用于与 MediaFullActivity 的数据修改保持同步
     */
    private fun refreshDataFromSp() {
        val list = LibraryCompat.loadMedia(spUtil)
        val filteredList = list.filter { it.type > MediaLibType.DEFAULT }.toMutableList()
        mediaViewModel.setMediaList(filteredList)
        processMediaData(filteredList)
    }

    private fun handleRefreshMedia(item: MediaTypeEntity, position: Int) {
        val categoryKey = "${item.type.value}:${item.storageId}"
        val currentState = shuffleStates[categoryKey] ?: MediaShuffleState()
        val (selected, newState) = LibraryCompat.selectRandomMedia(
            item.allMediaList, categoryKey, currentState
        )
        shuffleStates[categoryKey] = newState
        LibraryCompat.saveShuffleStates(spUtil, shuffleStates)
        item.mediaList.clear()
        item.mediaList.addAll(selected)
        mediaTypeItemAdapter.setShuffleStates(shuffleStates)
        mediaTypeItemAdapter.updateItem(item, position)
    }

    private fun handleResetMedia(item: MediaTypeEntity, position: Int) {
        val categoryKey = "${item.type.value}:${item.storageId}"
        shuffleStates.remove(categoryKey)
        LibraryCompat.saveShuffleStates(spUtil, shuffleStates)
        val defaultList = LibraryCompat.sortMediaByDefault(item.allMediaList).take(6).toMutableList()
        item.mediaList.clear()
        item.mediaList.addAll(defaultList)
        mediaTypeItemAdapter.setShuffleStates(shuffleStates)
        mediaTypeItemAdapter.updateItem(item, position)
    }

    private fun handleTogglePin(item: MediaData) {
        val newPinnedState = LibraryCompat.togglePin(spUtil, item.id)
        ToastUtil.showToast(requireContext(), if (newPinnedState) "已置顶" else "已取消置顶")
        refreshDataFromSp()
    }

    fun clearCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (!isAdded) return@launch
            val file = File(requireContext().externalCacheDir, ".thumb")
            if (file.exists() && file.canRead()) {
                file.listFiles { it.name.endsWith(".jpg") }?.forEach { it.delete() }
            }
        }
    }

}
