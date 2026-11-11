package me.lingci.dy.player.ui.file_browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityFileBrowserBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.playlist.PlaylistSelectDialog
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.isVideo
import me.lingci.lib.base.util.notStartDot
import me.lingci.lib.base.util.safeGetParcelable

/**
 *   @author : happyc
 *   time    : 2024/09/21
 *   desc    :
 *   version : 1.0
 */
class FileBrowserActivity : BaseActivity(), MenuProvider {

    companion object {
        private const val TAG = "FileBrowserActivity"

        private const val KEY_SOURCE = "source"

        fun start(context: Context, sourceData: SourceData) {
            val intent = Intent(context, FileBrowserActivity::class.java)
            val bundle = Bundle()
            bundle.putParcelable(KEY_SOURCE, sourceData)
            intent.putExtras(bundle)
            context.startActivity(intent)
        }
    }

    private val spUtil by lazy { SpUtil(this) }
    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var fileDirItemAdapter: FileDirItemAdapter
    private lateinit var fileBrowserItemAdapter: FileBrowserItemAdapter
    private lateinit var browserSettingsDialog: BrowserSettingsDialog
    private val viewModel: FileBrowserViewModel by viewModels()
    private var sourceData: SourceData? = null
    private var _storage: IStorage? = null
    private val storage: IStorage by lazy { _storage!! }
    private var currentJob: Job? = null
    private var currentFile: FileEntity? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.media_srot_menu, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView


        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                // 执行实时搜索
                filterFile(newText.orEmpty())
                return true
            }
        })
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_sort -> {
                browserSettingsDialog.show(supportFragmentManager, browserSettingsDialog.tag)
                return true
            }
        }
        return false
    }

    fun filterFile(keyword: String) {
        fileBrowserItemAdapter.updateData(viewModel.listFile(keyword))
    }

    @Suppress("DEPRECATION")
    fun init() {
        browserSettingsDialog = BrowserSettingsDialog {
            if (currentFile != null) {
                nextDir(currentFile!!)
            }
        }
        intent.safeGetParcelable<SourceData>(KEY_SOURCE)?.let { sourceData ->
            initNav(sourceData.title)
            initRecycle()
            initBackPressed()
            testStorage(sourceData)
        }
    }

    private fun testStorage(sourceData: SourceData) {
        _storage = sourceData.toStorage()
        if (_storage == null) {
            ToastUtil.showToast(this@FileBrowserActivity, "资源库不受支持")
            finish()
            return
        }
        this.sourceData = sourceData
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val testConnect = storage.testConnect()
            withContext(Dispatchers.Main) {
                hideLoading()
                if (testConnect.not()) {
                    ToastUtil.showToast(this@FileBrowserActivity, "资源库连接失败")
                    finish()
                } else {
                    initStorage()
                }
            }
        }
    }

    private fun initStorage() {
        currentFile = storage.rootFile()
        fileDirItemAdapter.addItem(storage.rootFile())
        showLoading()
        currentJob = lifecycleScope.launch(Dispatchers.IO) {
            storage.listFile("/", false)
                .onEach { item ->
                    if ((item.isFile.not() && (spUtil.browserShowHide || item.name.notStartDot())) || item.name.isVideo()) {
                        withContext(Dispatchers.Main) {
                            fileBrowserItemAdapter.addItem(item)
                        }
                    }
                }.onCompletion {
                    withContext(Dispatchers.Main) {
                        viewModel.setFileList(fileBrowserItemAdapter.getData())
                        viewModel.sorted()
                        fileBrowserItemAdapter.updateData(viewModel.listFile())
                        hideLoading()
                    }
                }.collect {}
        }
    }

    private fun scanRoot() {
        lifecycleScope.launch(Dispatchers.IO) {
            storage.listFiles("/", false).collect { list ->
                list.filter {
                    item -> item.isFile.not() || item.name.isVideo()
                }.let {
                    withContext(Dispatchers.Main) { hideLoading() }
                    binding.recyclerView.post {
                        fileBrowserItemAdapter.updateData(it)
                    }
                }
            }
        }
    }

    private fun initNav(title: String) {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = title
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun initRecycle() {
        // 目录导航
        binding.titleRecyclerView.layoutManager =
            LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        fileDirItemAdapter = FileDirItemAdapter(arrayListOf())
        binding.titleRecyclerView.adapter = fileDirItemAdapter
        fileDirItemAdapter.onItemClick { item, position ->
            fileDirItemAdapter.removeEnd(position)
            nextDir(item)
        }
        // 文件列表
        binding.recyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        fileBrowserItemAdapter = FileBrowserItemAdapter(arrayListOf())
        binding.recyclerView.adapter = fileBrowserItemAdapter
        fileBrowserItemAdapter.onItemClick { item, _ ->
            if (item.isFile) {
                var position = 0
                val list = ArrayList<VideoData>()
                fileBrowserItemAdapter.getData().forEach { fileBean ->
                    if (fileBean.isFile) {
                        val videoData = VideoData()
                        videoData.name = fileBean.name
                        videoData.videoUrl = storage.fullPath(fileBean.path)
                        videoData.type = sourceData?.type ?: StorageType.WEBDAV
                        videoData.parentPath = fileBean.path.substringBeforeLast("/").substringAfterLast("/")
                        videoData.putToken(storage.getToken())
                        list.add(videoData)
                        if (fileBean.name == item.name) {
                            position = list.size - 1
                        }
                    }
                }
                if (spUtil.longVideoMode) {
                    LongVideoActivity.start(this, list, position, false)
                } else {
                    ShortVideoActivity.start(this, list, position, false)
                }
            } else {
                fileDirItemAdapter.addItem(item)
                nextDir(item)
            }
        }
        fileBrowserItemAdapter.onItemMoreClick {view, item, position ->
            val popupMenu = PopupMenu(this, view)
            popupMenu.menu.apply {
                add(1, 1, 1, "添加到媒体库")
                add(1, 2, 2, getString(R.string.hint_add_to_playlist))
            }
            popupMenu.setOnMenuItemClickListener { pop ->
                if (pop.itemId == 1) {
                    addToMedia(item)
                } else if (pop.itemId == 2) {
                    addToPlaylist(item)
                }
                return@setOnMenuItemClickListener true
            }
            popupMenu.show()
        }
    }

    private fun addToMedia(item: FileEntity) {
        sourceData?.let { source ->
            lifecycleScope.launch {
                val mediaData = MediaData()
                mediaData.title = item.name.trim()
                mediaData.path = item.path
                mediaData.type = MediaLibType.fromStorage(source.type!!)
                mediaData.storageType = source.type!!
                mediaData.storageId = if (source.type == StorageType.LOCAL_STORAGE) null else source.id
                // Room3：sources/media 改为 DAO 异步读取，查重后单条落库
                val sourceList = DatabaseManager.sourceDataDao.list()
                mediaData.id = LibraryCompat.mediaId(mediaData, sourceList)
                val list = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first()
                if (list.any { LibraryCompat.sameMedia(it, mediaData, sourceList) }) {
                    ToastUtil.showToast(this@FileBrowserActivity, "媒体库已存在")
                } else {
                    DatabaseManager.mediaDataDao.insert(mediaData)
                    ToastUtil.showToast(this@FileBrowserActivity, "媒体库添加成功")
                }
            }
        }
    }

    private fun addToPlaylist(item: FileEntity) {
        if (item.isFile) {
            val videoData = VideoData()
            videoData.name = item.name
            videoData.videoUrl = storage.fullPath(item.path)
            videoData.type = sourceData?.type ?: StorageType.WEBDAV
            videoData.parentPath = item.path.substringBeforeLast("/").substringAfterLast("/")
            videoData.putToken(storage.getToken())
            PlaylistSelectDialog.show(this, listOf(videoData))
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val videos = mutableListOf<VideoData>()
                storage.listFile(item.path, false).collect { fileBean ->
                    if (fileBean.isFile && fileBean.name.isVideo()) {
                        val videoData = VideoData()
                        videoData.name = fileBean.name
                        videoData.videoUrl = storage.fullPath(fileBean.path)
                        videoData.type = sourceData?.type ?: StorageType.WEBDAV
                        videoData.parentPath = item.name
                        videoData.putToken(storage.getToken())
                        videos.add(videoData)
                    }
                }
                withContext(Dispatchers.Main) {
                    if (videos.isEmpty()) {
                        ToastUtil.showToast(this@FileBrowserActivity, "该目录没有视频文件")
                    } else {
                        PlaylistSelectDialog.show(this@FileBrowserActivity, videos)
                    }
                }
            }
        }
    }

    private fun initBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fileDirItemAdapter.itemCount > 1) {
                    fileDirItemAdapter.removeEnd(fileDirItemAdapter.itemCount - 2)
                    nextDir(fileDirItemAdapter.getData().last())
                } else {
                    finish()
                }
            }
        })
    }

    private fun nextDir(target: FileEntity) {
        showLoading()
        if (currentJob != null && !currentJob!!.isCompleted) {
            ToastUtil.showToast(this, "等待加载完成")
            return
        }
        currentFile = target

        fileBrowserItemAdapter.clearData()
        viewModel.setFileList(fileBrowserItemAdapter.getData())
        currentJob = lifecycleScope.launch(Dispatchers.IO) {
            storage.listFile(target.path, false)
                .onEach { item ->
                    if ((item.isFile.not() && (spUtil.browserShowHide || item.name.notStartDot())) || item.name.isVideo()) {
                        withContext(Dispatchers.Main) {
                            fileBrowserItemAdapter.addItem(item)
                        }
                    }
                }.onCompletion {
                    withContext(Dispatchers.Main) {
                        viewModel.setFileList(fileBrowserItemAdapter.getData())
                        viewModel.sorted()
                        fileBrowserItemAdapter.updateData(viewModel.listFile())
                        hideLoading()
                    }
                }.collect {}
        }
    }

    private fun nextDirOld(item: FileEntity) {
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            storage.listFiles(item.path).collect { list ->
                list.filter { item -> item.isFile.not() || item.name.isVideo() }.let {
                    withContext(Dispatchers.Main) {
                        hideLoading()
                        viewModel.setFileList(it)
                        fileBrowserItemAdapter.updateData(it)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        storage.release()
        super.onDestroy()
    }

}
