package me.lingci.dy.player.ui.danmaku_binding

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityDamakuBindingBinding
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.ui.long_video.PlayInfo
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.AppMeta
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.deleteExists
import me.lingci.lib.base.util.safeGetParcelableArrayList
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.util.ZipXmlLoader
import java.io.File
import java.util.UUID

/**
 * 弹幕绑定
 */
class DanmakuBindingActivity : BaseActivity(), MenuProvider {

    companion object {
        const val KEY_MEDIA = "media"
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_TEMP = "temp"

        fun start(context: Context, data: List<VideoData>, mediaId: String = "") {
            val intent = Intent(context, DanmakuBindingActivity::class.java)
            val toJson = JsonUtil.toJsonString(data)
            AppFile(context).buildCache(".data/${UUID.randomUUID()}.json").let { file ->
                FileOperator.writeText(file, toJson)
                intent.putExtra(KEY_TEMP, file.path)
            }
            intent.putExtra(KEY_MEDIA_ID, mediaId)
            context.startActivity(intent)
        }
    }

    private var _binding: ActivityDamakuBindingBinding? = null
    private val binding get() = _binding!!

    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaItemAdapter: DanmakuBindingItemAdapter
    private lateinit var danmakuItemAdapter: DanmakuBindingItemAdapter

    private val mediaItems = mutableListOf<DanmakuBindingMediaItem>()
    private val danmakuFiles = mutableListOf<FileEntity>()
    private var query = ""
    private var selectedMediaIndex = -1
    private var infoTemplate = ""
    private var selectedFolderPath = ""
    private var mediaId = ""
    private var discardOnExit = false
    private var confirmOnExit = false

    private val spUtil by lazy { SpUtil(this) }

    private var isScrolling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityDamakuBindingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaId = intent?.getStringExtra(KEY_MEDIA_ID).orEmpty()

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { handleExit() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExit()
            }
        })

        init()
    }

    private fun init() {
        initResult()
        initView()
        initData()
    }

    private fun initResult() {
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { data ->
            if (data.resultCode != RESULT_OK) {
                return@registerForActivityResult
            }
            val paths = data.data?.getStringArrayListExtra(FileSelectorActivity.KEY_PATH).orEmpty()
            val folderPath = paths.firstOrNull().orEmpty()
            if (folderPath.isBlank()) {
                return@registerForActivityResult
            }
            selectedFolderPath = folderPath
            saveDmFolderToMedia(folderPath)
            loadDanmakuFolder(File(folderPath))
        }
    }

    override fun onStart() {
        super.onStart()
        addMenuProvider(this, this)
    }

    override fun onStop() {
        removeMenuProvider(this)
        if (!discardOnExit && hasUnsavedChanges()) {
            autoSaveBindings()
        }
        super.onStop()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.media_srot_menu, menu)
        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = "搜索视频或弹幕文件"
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                updateQuery(text.orEmpty())
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                updateQuery(newText.orEmpty())
                return true
            }
        })
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        return when (menuItem.itemId) {
            R.id.menu_search -> true
            R.id.menu_sort -> {
                resetMatches()
                true
            }
            else -> false
        }
    }

    private fun updateQuery(value: String) {
        query = value.trim()
        refreshAll()
    }

    private fun initData() {
        val tempPath = intent?.getStringExtra(KEY_TEMP)
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = if (tempPath != null) {
                val file = File(tempPath)
                val json = FileOperator.readText(file)
                file.deleteExists()
                JsonUtil.toList<VideoData>(json)
            } else {
                intent?.safeGetParcelableArrayList<VideoData>(KEY_MEDIA).orEmpty()
            }
            val items = videos.map { video ->
                val info = PlayHelper.loadInfoSync(this@DanmakuBindingActivity, video)
                val tracks = info?.dmTrack.orEmpty()
                    .filter { it.isUsableBindingTrack() }
                    .map { it.bindingCopy() }
                    .toMutableList()
                val lastTrackPath = info?.lastDmTrack?.path.orEmpty()
                DanmakuBindingMediaItem(
                    videoData = video,
                    originalInfo = info,
                    originalTracks = tracks.map { it.bindingCopy() }.toMutableList(),
                    originalLastTrackPath = lastTrackPath,
                    workingTracks = tracks,
                    lastTrackPath = lastTrackPath,
                    matchHint = if (tracks.isEmpty()) "未绑定" else "已绑定 ${tracks.size} 个弹幕"
                )
            }.toMutableList()
            val cachedFolder = loadDmFolderFromMedia()
            withContext(Dispatchers.Main) {
                mediaItems.clear()
                mediaItems.addAll(items)
                if (mediaItems.isNotEmpty()) {
                    selectedMediaIndex = 0
                    mediaItems[0].selected = true
                }
                infoTemplate = buildInfoTemplate(mediaItems.size)
                if (cachedFolder.isNotBlank() && File(cachedFolder).isDirectory) {
                    selectedFolderPath = cachedFolder
                    loadDanmakuFolder(File(cachedFolder))
                } else {
                    refreshAll()
                }
            }
        }
    }

    private fun initView() {
        supportActionBar?.title = "本地弹幕绑定"

        binding.leftRecyclerView.layoutManager = LinearLayoutManager(this)
        mediaItemAdapter = DanmakuBindingItemAdapter(mutableListOf())
        binding.leftRecyclerView.adapter = mediaItemAdapter
        mediaItemAdapter.onItemClick { item, _ ->
            selectMedia(item.sourceIndex)
        }
        mediaItemAdapter.onActionClick { item, _ ->
            showBoundTracks(item.sourceIndex)
        }

        binding.leftRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!isScrolling) {
                    isScrolling = true
                    binding.rightRecyclerView.scrollBy(dx, dy)
                    isScrolling = false
                }
            }
        })

        binding.rightRecyclerView.layoutManager = LinearLayoutManager(this)
        danmakuItemAdapter = DanmakuBindingItemAdapter(mutableListOf())
        binding.rightRecyclerView.adapter = danmakuItemAdapter
        danmakuItemAdapter.onItemClick { item, _ ->
            bindDanmakuToSelection(item.sourceIndex)
        }
        danmakuItemAdapter.onItemLongClick { item, _ ->
            showDanmakuLongAction(item.sourceIndex)
        }

        binding.rightRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (!isScrolling) {
                    isScrolling = true
                    binding.leftRecyclerView.scrollBy(dx, dy)
                    isScrolling = false
                }
            }
        })

        binding.buttonSelectFolder.setOnClickListener {
            FileSelectorActivity.startFolder(this, resultLauncher)
        }

        binding.buttonExit.text = getString(me.lingci.lib.base.R.string.action_save)
        binding.buttonExit.visibility = View.VISIBLE
        binding.buttonExit.setOnClickListener {
            saveBindings()
        }
        binding.buttonPositive.text = "清空所有"
        binding.buttonPositive.visibility = View.VISIBLE
        binding.buttonPositive.setOnClickListener {
            confirmClearAllBindings()
        }
        binding.buttonSelectAll.visibility = View.GONE
        binding.buttonSelectInvert.visibility = View.VISIBLE
        binding.buttonSelectInvert.text = "清空当前"
        binding.buttonSelectInvert.setOnClickListener {
            clearCurrentBinding()
        }
        binding.layoutBatch.visibility = View.VISIBLE
        binding.tvDmTrack.text = "请选择右侧弹幕文件夹"
    }

    private fun resetMatches() {
        query = ""
        danmakuFiles.clear()
        selectedFolderPath = ""
        mediaItems.forEach {
            it.workingTracks = it.originalTracks.map { track -> track.bindingCopy() }.toMutableList()
            it.lastTrackPath = it.originalLastTrackPath
            it.matchHint = if (it.workingTracks.isEmpty()) "未绑定" else "已绑定 ${it.workingTracks.size} 个弹幕"
        }
        refreshAll()
        ToastUtil.showToast(this, "已还原选择")
    }

    private fun loadDanmakuFolder(folder: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            val files = FileOperator.getSortedFiles(folder, FileOperator.DM_EXTENSIONS)
                .map {
                    FileEntity(
                        title = it.name,
                        name = it.name,
                        path = it.path,
                        mimeType = if (it.extension.equals("zip", true)) ZipXmlLoader.ZIP else ""
                    )
                }
            withContext(Dispatchers.Main) {
                danmakuFiles.clear()
                danmakuFiles.addAll(files)
                refreshAll()
                ToastUtil.showToast(this@DanmakuBindingActivity, "已加载 ${files.size} 个弹幕文件")
            }
        }
    }

    private fun confirmClearAllBindings() {
        if (mediaItems.none { it.workingTracks.isNotEmpty() }) {
            ToastUtil.showToast(this, "当前没有可清空的绑定")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("清空全部绑定")
            .setMessage("将清空当前页面所有媒体的弹幕绑定，保存后生效")
            .setPositiveButton(me.lingci.lib.base.R.string.action_positive) { _, _ ->
                clearAllBindings()
            }
            .setNegativeButton(me.lingci.lib.base.R.string.action_negative, null)
            .show()
    }

    private fun clearAllBindings() {
        mediaItems.forEach { media ->
            media.workingTracks.clear()
            media.lastTrackPath = ""
            media.matchHint = "未绑定"
        }
        refreshAll()
        ToastUtil.showToast(this, "已清空全部绑定")
    }

    private fun autoBindAll() {
        if (danmakuFiles.isEmpty()) {
            ToastUtil.showToast(this, "请先选择弹幕文件夹")
            return
        }
        val usedPaths = mutableSetOf<String>()
        mediaItems.forEach { media ->
            media.workingTracks.filter { it.path.isNotBlank() }.forEach { usedPaths += it.path.normalizeBindingPath() }
        }
        mediaItems.forEach { media ->
            if (media.workingTracks.isNotEmpty()) {
                media.matchHint = "已绑定 ${media.workingTracks.size} 个弹幕"
                return@forEach
            }
            val candidates = DanmakuBindingMatcher.match(
                media.videoData,
                danmakuFiles.filterNot { usedPaths.contains(it.path.normalizeBindingPath()) }
            )
            val first = candidates.firstOrNull()
            if (first == null) {
                media.matchHint = "未匹配"
                return@forEach
            }
            if (DanmakuBindingMatcher.shouldAutoBind(candidates)) {
                val track = first.file.toDmTrack(selected = true)
                media.workingTracks = mutableListOf(track)
                media.lastTrackPath = track.path
                media.matchHint = "自动匹配 ${first.file.name}"
                usedPaths += track.path.normalizeBindingPath()
            } else {
                media.matchHint = first.detail
            }
        }
        refreshAll()
        ToastUtil.showToast(this, "预绑定完成")
    }

    private fun selectMedia(position: Int) {
        if (position !in mediaItems.indices) {
            return
        }
        selectedMediaIndex = position
        mediaItems.forEachIndexed { index, item ->
            item.selected = index == position
        }
        refreshAll()
    }

    private fun clearCurrentBinding() {
        val media = mediaItems.getOrNull(selectedMediaIndex) ?: return
        media.workingTracks.clear()
        media.lastTrackPath = ""
        media.matchHint = "未绑定"
        refreshAll()
    }

    private fun bindDanmakuToSelection(position: Int) {
        val media = mediaItems.getOrNull(selectedMediaIndex)
        val file = danmakuFiles.getOrNull(position)
        if (media == null || file == null) {
            return
        }
        if (media.hasTrack(file.path)) {
            media.lastTrackPath = file.path
            media.workingTracks = media.workingTracks.map { track ->
                track.bindingCopy().copy(selected = track.path.samePath(file.path))
            }.toMutableList()
            media.matchHint = "主弹幕切换为 ${file.displayNameWithoutExtension()}"
            refreshAll()
            return
        }
        val owner = mediaItems.firstOrNull { it !== media && it.hasTrack(file.path) }
        if (owner != null) {
            showRebindConfirm(media, owner, file)
            return
        }
        attachTrack(media, file)
    }

    private fun showDanmakuLongAction(position: Int) {
        val selectedMedia = mediaItems.getOrNull(selectedMediaIndex)
        val file = danmakuFiles.getOrNull(position)
        if (selectedMedia == null || file == null) {
            return
        }
        val owner = mediaItems.firstOrNull { it.hasTrack(file.path) }
        when {
            owner == null -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle(file.displayNameWithoutExtension())
                    .setItems(arrayOf("绑定到当前媒体")) { _, _ ->
                        attachTrack(selectedMedia, file)
                    }
                    .setNegativeButton(me.lingci.lib.base.R.string.action_negative, null)
                    .show()
            }
            owner == selectedMedia -> {
                val actions = mutableListOf<String>()
                actions += "设为主弹幕"
                actions += "解绑当前媒体"
                MaterialAlertDialogBuilder(this)
                    .setTitle(file.displayNameWithoutExtension())
                    .setItems(actions.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> setPrimaryTrack(selectedMedia, file.path)
                            1 -> removeTrackFromMedia(selectedMedia, file.path)
                        }
                    }
                    .setNegativeButton(me.lingci.lib.base.R.string.action_negative, null)
                    .show()
            }
            else -> {
                val actions = arrayOf("换绑到当前媒体", "查看当前归属")
                MaterialAlertDialogBuilder(this)
                    .setTitle(file.displayNameWithoutExtension())
                    .setItems(actions) { _, which ->
                        when (which) {
                            0 -> showRebindConfirm(selectedMedia, owner, file)
                            1 -> ToastUtil.showToast(this, "当前绑定: ${owner.videoData.name.removeExtensionForDisplay()}")
                        }
                    }
                    .setNegativeButton(me.lingci.lib.base.R.string.action_negative, null)
                    .show()
            }
        }
    }

    private fun showRebindConfirm(
        selectedMedia: DanmakuBindingMediaItem,
        owner: DanmakuBindingMediaItem,
        file: FileEntity
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle("重新绑定弹幕")
            .setMessage("${file.displayNameWithoutExtension()} 已绑定到 ${owner.videoData.name.removeExtensionForDisplay()}，是否改绑到 ${selectedMedia.videoData.name.removeExtensionForDisplay()}？")
            .setPositiveButton(me.lingci.lib.base.R.string.action_positive) { _, _ ->
                removeTrackFromMedia(owner, file.path)
                attachTrack(selectedMedia, file)
            }
            .setNegativeButton(me.lingci.lib.base.R.string.action_negative, null)
            .show()
    }

    private fun setPrimaryTrack(media: DanmakuBindingMediaItem, trackPath: String) {
        media.lastTrackPath = trackPath
        media.workingTracks = media.workingTracks.map { current ->
            current.bindingCopy().copy(selected = current.path.samePath(trackPath))
        }.toMutableList()
        val currentName = media.workingTracks.firstOrNull { it.path.samePath(trackPath) }
            ?.displayNameWithoutExtension()
            .orEmpty()
        media.matchHint = if (currentName.isBlank()) "主弹幕已更新" else "主弹幕切换为 $currentName"
        refreshAll()
    }

    private fun removeTrackFromMedia(media: DanmakuBindingMediaItem, trackPath: String) {
        media.workingTracks.removeAll { it.path.samePath(trackPath) }
        if (media.lastTrackPath.samePath(trackPath)) {
            media.lastTrackPath = media.primaryTrack()?.path.orEmpty()
        }
        media.matchHint = if (media.workingTracks.isEmpty()) {
            "未绑定"
        } else {
            "已绑定 ${media.workingTracks.size} 个弹幕"
        }
        refreshAll()
    }

    private fun attachTrack(media: DanmakuBindingMediaItem, file: FileEntity) {
        val track = file.toDmTrack(selected = true)
        media.workingTracks.forEach { it.selected = false }
        media.workingTracks.add(track)
        media.lastTrackPath = track.path
        media.matchHint = "手动绑定 ${file.displayNameWithoutExtension()}"
        refreshAll()
    }

    private fun showBoundTracks(position: Int) {
        val media = mediaItems.getOrNull(position) ?: return
        if (media.workingTracks.isEmpty()) {
            ToastUtil.showToast(this, "当前媒体还没有绑定弹幕")
            return
        }
        val tracks = media.workingTracks.toList()
        val options = tracks.map { track ->
            val prefix = if (track.path.samePath(media.lastTrackPath)) "[主弹幕] " else ""
            "$prefix${track.displayNameWithoutExtension()}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(media.videoData.name)
            .setItems(options) { _, which ->
                showTrackAction(media, tracks[which])
            }
            .setNegativeButton(me.lingci.lib.base.R.string.action_negative, null)
            .show()
    }

    private fun showTrackAction(media: DanmakuBindingMediaItem, track: DmTrack) {
        val actions = arrayOf("设为主弹幕", "移除绑定")
        MaterialAlertDialogBuilder(this)
            .setTitle(track.displayNameWithoutExtension())
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        setPrimaryTrack(media, track.path)
                    }
                    1 -> {
                        removeTrackFromMedia(media, track.path)
                    }
                }
            }
            .show()
    }

    private fun saveBindings() {
        lifecycleScope.launch(Dispatchers.IO) {
            var changedCount = 0
            var failedCount = 0
            mediaItems.forEach { media ->
                if (!media.hasChanges()) {
                    return@forEach
                }
                val sanitized = media.workingTracks.map { track ->
                    track.bindingCopy().copy(selected = track.path.samePath(media.lastTrackPath))
                }.toMutableList()
                val primaryTrack = sanitized.firstOrNull { it.path.samePath(media.lastTrackPath) }
                    ?: sanitized.firstOrNull()
                val info = (media.originalInfo ?: PlayInfo()).copy(
                    dmTrack = sanitized,
                    lastDmTrack = primaryTrack
                )
                val save = PlayHelper.saveInfoSync(this@DanmakuBindingActivity, media.videoData, info)
                if (save) {
                    changedCount++
                    media.originalInfo = info
                    media.originalTracks = sanitized.map { it.bindingCopy() }.toMutableList()
                    media.originalLastTrackPath = media.lastTrackPath
                } else {
                    failedCount++
                }
            }
            withContext(Dispatchers.Main) {
                refreshAll()
                when {
                    failedCount > 0 -> ToastUtil.showToast(this@DanmakuBindingActivity, "保存完成，失败 $failedCount 条")
                    changedCount > 0 -> ToastUtil.showToast(this@DanmakuBindingActivity, "已保存 $changedCount 条绑定")
                    else -> ToastUtil.showToast(this@DanmakuBindingActivity, "没有需要保存的变化")
                }
            }
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        return mediaItems.any { it.hasChanges() }
    }

    private fun autoSaveBindings() {
        lifecycleScope.launch(Dispatchers.IO) {
            mediaItems.forEach { media ->
                if (!media.hasChanges()) {
                    return@forEach
                }
                val sanitized = media.workingTracks.map { track ->
                    track.bindingCopy().copy(selected = track.path.samePath(media.lastTrackPath))
                }.toMutableList()
                val primaryTrack = sanitized.firstOrNull { it.path.samePath(media.lastTrackPath) }
                    ?: sanitized.firstOrNull()
                val info = (media.originalInfo ?: PlayInfo()).copy(
                    dmTrack = sanitized,
                    lastDmTrack = primaryTrack
                )
                val save = PlayHelper.saveInfoSync(this@DanmakuBindingActivity, media.videoData, info)
                if (save) {
                    media.originalInfo = info
                    media.originalTracks = sanitized.map { it.bindingCopy() }.toMutableList()
                    media.originalLastTrackPath = media.lastTrackPath
                }
            }
        }
    }

    private fun handleExit() {
        if (!hasUnsavedChanges()) {
            finish()
            return
        }
        if (!confirmOnExit) {
            saveBindings()
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("未保存的变更")
            .setMessage("当前有未保存的弹幕绑定变更，是否保存？")
            .setPositiveButton("保存并退出") { _, _ ->
                saveBindings()
                finish()
            }
            .setNegativeButton("不保存退出") { _, _ ->
                discardOnExit = true
                finish()
            }
            .setNeutralButton(me.lingci.lib.base.R.string.action_negative, null)
            .show()
    }

    private fun saveDmFolderToMedia(folderPath: String) {
        if (mediaId.isBlank()) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                // MediaData.dmFolder 为 @Ignore 字段不落库，改用 app_meta 键值对按媒体 id 持久化，
                // 保持「记住上次弹幕目录」的原有行为（fire-and-forget 写入用 runCatching 包裹）
                DatabaseManager.appMetaDao.put(
                    AppMeta(key = "dm_folder|$mediaId", value = folderPath)
                )
            }
        }
    }

    // 挂起函数：调用方（initData）已处于协程内（Room3 DAO 为 suspend）
    private suspend fun loadDmFolderFromMedia(): String {
        if (mediaId.isBlank()) return ""
        // 读取 saveDmFolderToMedia 持久化的弹幕目录（app_meta 键值对）
        return DatabaseManager.appMetaDao.get("dm_folder|$mediaId").orEmpty()
    }

    private fun refreshAll() {
        refreshMediaList()
        refreshDanmakuList()
        updateSummary()
    }

    private fun refreshMediaList() {
        val items = mediaItems.filter { media ->
            query.isBlank() || media.videoData.name.contains(query, ignoreCase = true) || media.matchHint.contains(query, ignoreCase = true)
        }.map { media ->
            val primary = media.primaryTrack()?.displayNameWithoutExtension()?.let { "主弹幕: $it" } ?: "未绑定"
            val summary = buildString {
                append("已绑定 ${media.boundCount()} 个")
                if (media.matchHint.isNotBlank()) {
                    append(" · ")
                    append(media.matchHint)
                }
                append("\n")
                append(primary)
                if (media.hasChanges()) {
                    append(" · 待保存")
                }
            }
            DanmakuBindingRowItem(
                sourceIndex = mediaItems.indexOf(media),
                title = media.videoData.name,
                summary = summary,
                actionIconRes = R.drawable.ic_dm_track,
                actionEnabled = media.workingTracks.isNotEmpty(),
                selected = media.selected
            )
        }
        mediaItemAdapter.updateData(items)
    }

    private fun refreshDanmakuList() {
        val selectedMedia = mediaItems.getOrNull(selectedMediaIndex)
        val items = filteredDanmakuFiles().map { fileItem ->
            val summaryParts = mutableListOf<String>()
            if (fileItem.score > 0) {
                summaryParts += "匹配度 ${fileItem.score}"
            }
            if (fileItem.ownerTitle.isNotBlank()) {
                summaryParts += if (fileItem.boundToSelected) {
                    if (fileItem.primaryForSelected) "已作为主弹幕" else "已绑定到当前媒体"
                } else {
                    "已绑定到 ${fileItem.ownerTitle}"
                }
            }
            DanmakuBindingRowItem(
                sourceIndex = danmakuFiles.indexOfFirst { it.path.samePath(fileItem.fileEntity.path) },
                title = fileItem.fileEntity.displayNameWithoutExtension(),
                summary = summaryParts.joinToString(" · "),
                actionIconRes = R.drawable.ic_keyboard_arrow_right_24,
                actionEnabled = selectedMedia != null,
                dimmed = fileItem.ownerTitle.isNotBlank() && fileItem.boundToSelected.not()
            )
        }
        danmakuItemAdapter.updateData(items)
    }

    private fun filteredDanmakuFiles(): List<DanmakuBindingFileItem> {
        val selectedMedia = mediaItems.getOrNull(selectedMediaIndex)
        val candidates = selectedMedia?.let { DanmakuBindingMatcher.match(it.videoData, danmakuFiles) }.orEmpty()
        val candidateMap = candidates.associateBy { it.file.path.normalizeBindingPath() }
        return danmakuFiles.filter { file ->
            query.isBlank() || file.name.contains(query, ignoreCase = true) ||
                    selectedMedia?.videoData?.name?.contains(query, ignoreCase = true) == true
        }.map { file ->
            val owner = mediaItems.firstOrNull { it.hasTrack(file.path) }
            val candidate = candidateMap[file.path.normalizeBindingPath()]
            DanmakuBindingFileItem(
                fileEntity = file,
                score = candidate?.score ?: 0,
                ownerTitle = owner?.videoData?.name.orEmpty(),
                boundToSelected = owner != null && owner == selectedMedia,
                primaryForSelected = owner != null && owner == selectedMedia && owner.isPrimary(file.path)
            )
        }.sortedWith(compareByDescending<DanmakuBindingFileItem> { it.boundToSelected }.thenByDescending { it.score }.thenBy { it.fileEntity.name })
    }

    @SuppressLint("SetTextI18n")
    private fun updateSummary() {
        val boundMediaCount = mediaItems.count { it.workingTracks.isNotEmpty() }
        val trackCount = mediaItems.sumOf { it.workingTracks.size }
        val changedCount = mediaItems.count { it.hasChanges() }
        val folder = if (selectedFolderPath.isNotBlank()) "\n弹幕目录: $selectedFolderPath" else ""
        binding.tvDmTrack.text = infoTemplate.format(boundMediaCount, trackCount, changedCount) + folder
    }

    private fun buildInfoTemplate(totalMediaCount: Int): String {
        return "共 $totalMediaCount 条媒体，已绑定 %d 条，共 %d 个轨道，待保存 %d 条"
    }

    private fun FileEntity.toDmTrack(selected: Boolean): DmTrack {
        return DmTrack(
            title = name.ifBlank { title },
            path = path,
            mineType = mimeType,
            selected = selected,
            checked = false,
            showAction = false
        )
    }

    private fun resetLayout(orientation: Int) {
        val layoutManager = binding.leftRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val position = layoutManager.findFirstVisibleItemPosition()
        binding.leftRecyclerView.post {
            binding.leftRecyclerView.scrollToPosition(position)
            binding.rightRecyclerView.scrollToPosition(position)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        resetLayout(newConfig.orientation)
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
