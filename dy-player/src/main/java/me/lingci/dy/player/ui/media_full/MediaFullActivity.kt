package me.lingci.dy.player.ui.media_full

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityMediaFullBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.ui.media_add.MediaLinkAddDialog
import me.lingci.dy.player.ui.media_add.MediaManagerDialog
import me.lingci.dy.player.ui.media_add.MediaManagerViewModel
import me.lingci.dy.player.ui.media_detail.MediaDetailActivity
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.ui.source.ItemAction
import me.lingci.dy.player.ui.source.ItemActionDialog
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.notStartDot
import java.io.File

/**
 * 媒体库
 */
class MediaFullActivity : BaseActivity(), MenuProvider {

    companion object {

        const val KEY_TYPE = "type"
        const val KEY_STORAGE_ID = "storage_id"

        fun intent(context: Context, type: Int, storageId: String?): Intent {
            return Intent(context, MediaFullActivity::class.java).apply {
                putExtra(KEY_TYPE, type)
                putExtra(KEY_STORAGE_ID, storageId)
            }
        }

        fun start(context: Context, type: Int, storageId: String?) {
            context.startActivity(intent(context, type, storageId))
        }

    }

    private var _binding: ActivityMediaFullBinding? = null
    private val binding get() = _binding!!
    private val fullViewModel: MediaFullViewModel by viewModels()
    private val managerViewModel: MediaManagerViewModel by viewModels()
    private val spUtil by lazy { SpUtil(baseContext) }
    private var type: Int? = 2
    private var keyword: String = ""
    private var storageId: String? = null

    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private lateinit var mediaItemAdapter: MediaItemAdapter
    private lateinit var mediaManagerDialog: MediaManagerDialog
    private lateinit var mediaLinkAddDialog: MediaLinkAddDialog
    private var lastCoverRatio: String? = null
    private var dataChanged = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMediaFullBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        init()
    }

    private fun init() {
        initResult()
        initViewModel()
        initView()
        initData()
    }

    override fun onResume() {
        super.onResume()
        val currentRatio = spUtil.coverRatio
        if (lastCoverRatio != null && lastCoverRatio != currentRatio) {
            mediaItemAdapter.changeCoverRatio(currentRatio!!)
        }
        // 挂起调用：协程内读取存储源列表（Room3 DAO 为 suspend）
        lifecycleScope.launch {
            mediaItemAdapter.setSourceList(DatabaseManager.sourceDataDao.list())
        }
        // 同步启动自动播放媒体库 id，确保从其它页面返回时角标正确
        mediaItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
        lastCoverRatio = currentRatio
    }

    private fun initResult() {
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            it?.let { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.let { bundle ->
                        bundle.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)
                            ?.let { paths ->
                                handleLinkMedia(FileOperator.readText(paths.first()))
                            }
                    }
                }
            }
        }
    }

    private fun initViewModel() {
        fullViewModel.text.observe(this) {
            Log.d(this, it)
        }
    }

    override fun onStart() {
        super.onStart()
        addMenuProvider(this, this)
    }

    override fun onStop() {
        removeMenuProvider(this)
        super.onStop()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.media_srot_menu, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                // 执行实时搜索
                filterMedia(newText.orEmpty())
                return true
            }
        })
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {

            R.id.menu_sort -> {
                mediaItemAdapter.sorted()
            }

        }
        return false
    }

    private fun filterMedia(keyword: String = "") {
        this.keyword = keyword
        mediaItemAdapter.updateData(fullViewModel.listMedia(keyword))
    }

    private fun initData() {
        type = intent?.getIntExtra(KEY_TYPE, MediaLibType.LOCAL.ordinal)
        storageId = intent?.getStringExtra(KEY_STORAGE_ID)
        val mediaType = MediaLibType.fromValue(type ?: MediaLibType.LOCAL.value)
        // 挂起调用：协程内读取存储源与媒体列表（Room3 DAO 为 suspend）
        lifecycleScope.launch {
            val sourceList = DatabaseManager.sourceDataDao.list()
            supportActionBar?.title = when(mediaType) {
                MediaLibType.LOCAL -> "本地媒体库"
                MediaLibType.ONLINE -> "在线媒体库"
                MediaLibType.WEBDAV -> when (storageId) {
                    null, "", LibraryCompat.LEGACY_UNBOUND_STORAGE_ID -> "WEBDAV媒体库(旧数据)"
                    else -> sourceList.find { it.id == storageId }?.title ?: "WEBDAV媒体库"
                }
                MediaLibType.SMB -> when (storageId) {
                    null, "" -> "SMB媒体库"
                    else -> sourceList.find { it.id == storageId }?.title ?: "SMB媒体库"
                }
                else -> "媒体库"
            }
            val currentStorageId = storageId ?: LibraryCompat.LEGACY_UNBOUND_STORAGE_ID
            // 全量媒体列表：分页查询取首页（替代 loadMedia）
            val data = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first().filter { item ->
                if (item.type != mediaType) {
                    return@filter false
                }
                if (!isRemoteMedia(mediaType)) {
                    return@filter true
                }
                LibraryCompat.mediaBucketStorageId(item, sourceList) == currentStorageId
            }.toMutableList()
            mediaItemAdapter.updateData(data)
            fullViewModel.setMediaList(data)
        }
    }

    private fun initView() {
        // 本地媒体库管理
        mediaManagerDialog = MediaManagerDialog.newInstance(null) { media, update ->
            Log.d(this@MediaFullActivity, "media", media, "update", update)
            if (!update && media.path == FileOperator.movieFolder.path) {
                ToastUtil.showToast(this, "媒体库已存在")
                return@newInstance
            }
            // 挂起调用：协程内读取存储源列表用于查重（Room3 DAO 为 suspend）
            lifecycleScope.launch {
                val sourceList = DatabaseManager.sourceDataDao.list()
                if (media.id.isBlank()) {
                    media.id = LibraryCompat.mediaId(media, sourceList)
                }
                val position = fullViewModel.listMedia().indexOfFirst { LibraryCompat.sameMedia(it, media, sourceList) }
                if (position > -1 && update.not()) {
                    ToastUtil.showToast(this@MediaFullActivity, "媒体库已存在")
                    return@launch
                }
                if (position > -1) {
                    fullViewModel.removeItem(position)
                }
                fullViewModel.addItem(media)
                mediaItemAdapter.updateData(fullViewModel.listMedia(keyword))
                updateMedia()
            }
        }
        // 在线媒体库新增
        mediaLinkAddDialog = MediaLinkAddDialog()
        mediaLinkAddDialog.onSave { media, updated ->
            saveOnlineMediaData(media, updated)
        }

        buildMediaAdapter()
        // 批量管理
        binding.buttonExit.setOnClickListener {
            mediaItemAdapter.exitBatchMode()
            binding.layoutBatch.visibility = View.GONE
        }
        binding.buttonSelectAll.setOnClickListener {
            mediaItemAdapter.selectAll()
        }
        binding.buttonSelectInvert.setOnClickListener {
            mediaItemAdapter.selectInvert()
        }
        binding.buttonRemove.setOnClickListener {
            fullViewModel.removeList(mediaItemAdapter.listSelect())
            mediaItemAdapter.removeSelect()
            binding.layoutBatch.visibility = View.GONE
            updateMedia()
        }
    }

    private fun buildMediaAdapter() {
        mediaItemAdapter = MediaItemAdapter(ArrayList(), coverRatio = spUtil.coverRatio!!)
        // 挂起调用：协程内读取存储源列表（Room3 DAO 为 suspend）
        lifecycleScope.launch {
            mediaItemAdapter.setSourceList(DatabaseManager.sourceDataDao.list())
        }
        // 初始化启动自动播放媒体库 id，显示角标
        mediaItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
        binding.recyclerView.layoutManager = GridLayoutManager(this, if (isOrientationPortraitOfSysMetrics()) 3 else 7)
        binding.recyclerView.adapter = mediaItemAdapter
        mediaItemAdapter.onItemClick { item, position ->
            Log.d(this, position)
            if (spUtil.videoDetailMode) {
                MediaDetailActivity.start(this, item)
                return@onItemClick
            }
            // 修复：playMode == 2 表示长视频（原误写为 3，导致强制长视频的媒体库在全局非长视频时错误进入短视频页）
            val longVideoMode = (spUtil.longVideoMode && item.playMode == 0) || item.playMode == 2
            if (item.type in MediaLibType.DEFAULT..MediaLibType.LOCAL) {
                handleMediaLocal(item, longVideoMode)
            }
            if (item.type == MediaLibType.ONLINE) {
                // items 为 @Ignore 字段（Room 读出恒为空）：剧集改从 video_data 子表加载后再起播
                lifecycleScope.launch {
                    val videos = DatabaseManager.videoDataDao.listByMedia(item.id)
                    if (videos.isEmpty()) {
                        ToastUtil.showToast(this@MediaFullActivity, "媒体库为空")
                    } else {
                        startPlayer(item, videos, longVideoMode)
                    }
                }
            }
            if (isRemoteMedia(item.type)) {
                handleMediaRemote(item, longVideoMode)
            }
        }
        mediaItemAdapter.onLongItemClick { item, position ->
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
                        ItemAction(2, "批量管理"),
                        ItemAction(3, "删除媒体库", me.lingci.lib.base.R.color.red_700)
                    )
                ) { itemId ->
                    when(itemId) {
                        1 -> {
                            when (item.type) {
                                MediaLibType.LOCAL -> {
                                    managerViewModel.updateMediaBean(item)
                                    mediaManagerDialog.show(
                                        supportFragmentManager,
                                        mediaManagerDialog.tag
                                    )
                                }

                                MediaLibType.ONLINE -> {
                                    mediaLinkAddDialog.setMedia(item)
                                    mediaLinkAddDialog.show(
                                        supportFragmentManager,
                                        mediaLinkAddDialog.tag
                                    )
                                }

                                else -> {

                                }
                            }
                        }
                        5 -> {
                            handleTogglePin(item, position)
                        }
                        6 -> {
                            // 切换启动自动播放设置：已设则取消，未设则设为当前（单选覆盖）
                            if (spUtil.autoPlayMediaId == item.id) {
                                spUtil.autoPlayMediaId = ""
                                ToastUtil.showToast(this, "已取消启动自动播放")
                            } else {
                                spUtil.autoPlayMediaId = item.id
                                // 互斥：清空播放列表自动播放设置
                                spUtil.autoPlayPlaylistId = ""
                                ToastUtil.showToast(this, "已设为启动自动播放")
                            }
                            // 刷新列表以更新角标
                            mediaItemAdapter.setAutoPlayMediaId(spUtil.autoPlayMediaId)
                        }
                        2 -> {
                            mediaItemAdapter.batchMode(position)
                            binding.layoutBatch.visibility = View.VISIBLE
                        }
                        3 -> {
                            // 挂起调用：协程内读取存储源列表用于 sameMedia 匹配（Room3 DAO 为 suspend）
                            lifecycleScope.launch {
                                val sourceList = DatabaseManager.sourceDataDao.list()
                                mediaItemAdapter.getItem(position)?.let { item ->
                                    fullViewModel.removeItem(fullViewModel.listMedia().indexOfFirst { LibraryCompat.sameMedia(it, item, sourceList) })
                                    // 若删除的是启动自动播放媒体库，清空设置避免启动时找不到
                                    if (spUtil.autoPlayMediaId == item.id) {
                                        spUtil.autoPlayMediaId = ""
                                    }
                                }
                                mediaItemAdapter.removeItem(position)
                                updateMedia()
                            }
                        }
                    }
                }
                itemActionDialog.show(supportFragmentManager, itemActionDialog.tag)
            }
        }
    }

    private fun handleLinkMedia(linkString: String) {
        MediaHelper.createMediaFromString(linkString).let { mediaData ->
            if (mediaData.items.isNotEmpty()) {
                saveOnlineMediaData(mediaData, false)
            } else {
                ToastUtil.showToast(this, "导入失败")
            }
        }
    }

    // 保存在线媒体库
    private fun saveOnlineMediaData(media: MediaData, updated: Boolean) {
        lifecycleScope.launch(Dispatchers.Main) {
            // 挂起调用：读取存储源列表用于查重（Room3 DAO 为 suspend，协程内直接调用）
            val sourceList = DatabaseManager.sourceDataDao.list()
            if (media.id.isBlank()) {
                media.id = LibraryCompat.mediaId(media, sourceList)
            }
            val position = fullViewModel.listMedia().indexOfFirst { LibraryCompat.sameMedia(it, media, sourceList) }
            Log.d(this@MediaFullActivity, "position", position, "updated", updated)
            if (position != -1 && !updated) {
                ToastUtil.showToast(this@MediaFullActivity, "媒体库已存在")
                return@launch
            } else {
                if (updated) {
                    fullViewModel.removeItem(position)
                }
                fullViewModel.addItem(media)
                mediaItemAdapter.updateData(fullViewModel.listMedia(keyword))
                updateMedia()
            }
        }
    }

    fun updateMedia() {
        // 挂起调用：协程内完成「当前类别媒体列表用 ViewModel 列表整体替换」的落库（Room3 DAO 为 suspend）
        lifecycleScope.launch {
            val all = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first()
            val sourceList = DatabaseManager.sourceDataDao.list()
            val currentType = MediaLibType.fromValue(type ?: MediaLibType.LOCAL.value)
            val currentStorageId = storageId ?: LibraryCompat.LEGACY_UNBOUND_STORAGE_ID
            // 旧逻辑：remain = 全量中不属于当前页类别的媒体，再以 remain + 当前页最新列表整体保存；
            // Room 下等价拆解为：反推出当前类别旧列表，删除其中已不在新列表的媒体（其剧集由
            // video_data 对 media_data 的 FK CASCADE 级联清理），再 upsert 新列表全部媒体
            val currentCategory = all.filter { media ->
                if (media.type != currentType) {
                    return@filter false
                }
                if (!isRemoteMedia(currentType)) {
                    return@filter true
                }
                LibraryCompat.mediaBucketStorageId(media, sourceList) == currentStorageId
            }
            val newList = fullViewModel.listMedia()
            val newIds = newList.mapTo(mutableSetOf()) { it.id }
            DatabaseManager.inTransaction {
                currentCategory.filter { it.id !in newIds }.forEach { media ->
                    DatabaseManager.mediaDataDao.delete(media)
                }
                newList.forEach { media ->
                    DatabaseManager.mediaDataDao.upsert(media)
                    // items 为 @Ignore 字段不随媒体落库：新建/编辑的在线媒体库携带剧集列表，
                    // 按「先清后插」重建 video_data 子表（本地媒体库 items 恒为空，自动跳过）
                    if (media.items.isNotEmpty()) {
                        media.items.forEachIndexed { idx, video ->
                            if (video.id.isBlank()) {
                                video.id = video.beanId()
                            }
                            if (video.mediaId.isBlank()) {
                                video.mediaId = media.id
                            }
                            // 写入排序序号，listByMedia 按 sort_index 排序
                            video.sortIndex = idx
                        }
                        DatabaseManager.videoDataDao.deleteByMedia(media.id)
                        DatabaseManager.videoDataDao.insertAll(media.items)
                    }
                }
            }
            markChanged()
        }
    }

    private fun handleTogglePin(item: MediaData, position: Int) {
        // 挂起调用：协程内切换置顶状态（Room3 业务函数为 suspend，写入用 runCatching 包裹）
        lifecycleScope.launch {
            val newPinnedState = runCatching { LibraryCompat.togglePin(item.id) }.getOrDefault(false)
            fullViewModel.listMedia().find { it.id == item.id }?.let {
                it.pinned = newPinnedState
                it.pinnedAt = if (newPinnedState) System.currentTimeMillis() else 0L
            }
            val sorted = LibraryCompat.sortMediaByDefault(fullViewModel.listMedia(keyword))
            mediaItemAdapter.updateData(sorted)
            updateMedia()
            ToastUtil.showToast(this@MediaFullActivity, if (newPinnedState) "已置顶" else "已取消置顶")
        }
    }

    private fun markChanged() {
        dataChanged = true
        setResult(RESULT_OK)
    }

    private fun handleMediaRemote(item: MediaData, longVideoMode: Boolean) {
        // 挂起调用：协程内读取存储源列表并解析存储（Room3 DAO 为 suspend）
        lifecycleScope.launch {
            val sourceList = DatabaseManager.sourceDataDao.list()
            val targetStorageId = LibraryCompat.effectiveStorageId(item, sourceList)
            if (targetStorageId == null) {
                ToastUtil.showToast(this@MediaFullActivity, "该媒体库来自旧数据，尚未绑定具体资源源")
                return@launch
            }
            val source = sourceList.find { it.id == targetStorageId }
            if (source == null) {
                ToastUtil.showToast(this@MediaFullActivity, "资源源不存在")
                return@launch
            }
            val storage = source.toStorage() ?: return@launch
            showLoading()
            lifecycleScope.launch(Dispatchers.IO) {
            val testConnect = storage.testConnect()
            if (!testConnect) {
                launch(Dispatchers.Main) {
                    hideLoading()
                    ToastUtil.showToast(this@MediaFullActivity, "资源库连接失败")
                }
                return@launch
            }
            val videos = mutableListOf<VideoData>()
            storage.listFile(item.path, false).collect { file ->
                if (file.isFile && file.name.isVideo()) {
                    videos.add(VideoData().apply {
                        name = file.name
                        videoUrl = storage.fullPath(file.path)
                        type = source.storageType()
                        parentPath = file.path.substringBeforeLast("/").substringAfterLast("/")
                        putToken(storage.getToken())
                    })
                }
            }
            launch(Dispatchers.Main) {
                hideLoading()
                if (videos.isEmpty()) {
                    ToastUtil.showToast(this@MediaFullActivity, "资源库为空")
                } else {
                    startPlayer(item, videos, longVideoMode)
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
                        this,
                        if (item.type == MediaLibType.DEFAULT) "默认资源库为空" else "资源库为空"
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
                this,
                media,
                ArrayList(videos),
                0,
                false
            )
        } else {
            ShortVideoActivity.start(
                this,
                media,
                ArrayList(videos),
                0,
                false
            )
        }
    }

    private fun isRemoteMedia(type: MediaLibType): Boolean {
        return type == MediaLibType.WEBDAV || type == MediaLibType.SMB
    }

    private fun resetLayout(orientation: Int) {
        binding.recyclerView.layoutManager = GridLayoutManager(
            this,
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) 7 else 3
        )
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        resetLayout(newConfig.orientation)
        super.onConfigurationChanged(newConfig)
    }

    override fun onDestroy() {
        if (dataChanged) {
            setResult(RESULT_OK)
        }
        super.onDestroy()
        _binding = null
    }

}
