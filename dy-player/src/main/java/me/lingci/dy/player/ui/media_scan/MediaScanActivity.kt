package me.lingci.dy.player.ui.media_scan

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.database.getStringOrNull
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityMediaScanBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.isVideo
import java.io.File
import java.util.ArrayDeque

/**
 * 媒体扫描
 */
class MediaScanActivity : BaseActivity() {

    companion object {

        const val KEY_AUTO_START = "auto_start"
        const val KEY_FILTER_BY_SELECTED_STORAGE = "filter_by_selected_storage"

        fun intent(
            context: Context,
            autoStart: Boolean = false,
            filterBySelectedStorage: Boolean = true
        ): Intent {
            return Intent(context, MediaScanActivity::class.java).apply {
                putExtra(KEY_AUTO_START, autoStart)
                putExtra(KEY_FILTER_BY_SELECTED_STORAGE, filterBySelectedStorage)
            }
        }

        fun start(
            context: Context,
            autoStart: Boolean = false,
            filterBySelectedStorage: Boolean = true
        ) {
            context.startActivity(intent(context, autoStart, filterBySelectedStorage))
        }
    }

    private data class StorageRootOption(
        val title: String,
        val rootPath: String
    ) {
        override fun toString(): String = title
    }

    private data class ScanSummary(
        val totalVideoCount: Int,
        val folders: List<ScanFolderResult>
    )

    private var _binding: ActivityMediaScanBinding? = null
    private val binding get() = _binding!!
    private val spUtil by lazy { SpUtil(baseContext) }
    private val storageOptions = mutableListOf<StorageRootOption>()

    private lateinit var scanResultAdapter: ScanFolderResultAdapter
    private var scanNoMedia = false
    private var scanHide = false
    private var minFileSizeKb = 10
    private var autoScanPending = false
    private var filterBySelectedStorage = true
    private var scanJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { (_, granted) -> granted }) {
            startScan(force = true)
        } else {
            ToastUtil.showToast(this, "未授予存储权限")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityMediaScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.hint_media_scan_local)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        autoScanPending = intent.getBooleanExtra(KEY_AUTO_START, false)
        filterBySelectedStorage = intent.getBooleanExtra(KEY_FILTER_BY_SELECTED_STORAGE, true)
        initView()
    }

    override fun onStart() {
        super.onStart()
        if (autoScanPending) {
            autoScanPending = false
            binding.buttonScan.post {
                startScan()
            }
        }
    }

    private fun initView() {
        scanResultAdapter = ScanFolderResultAdapter(mutableListOf())
        scanResultAdapter.onSelectionChanged {
            updateActionSummary()
        }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = scanResultAdapter
        binding.fastScroller.attachRecyclerView(binding.recyclerView)
        binding.fastScroller.scrollNow()

        initStorageOptions()
        updateResultSummary(emptyList(), 0)
        setScanning(false)

        binding.swScanNoMedia.setOnCheckedChangeListener { _, checked ->
            scanNoMedia = checked
        }
        binding.swScanHide.setOnCheckedChangeListener { _, checked ->
            scanHide = checked
        }
        binding.etMinFileSize.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val input = binding.etMinFileSize.text.toString().toIntOrNull() ?: 10
                minFileSizeKb = input.coerceAtLeast(0)
                binding.etMinFileSize.setText(minFileSizeKb.toString())
            }
        }
        binding.buttonScan.setOnClickListener {
            startScan()
        }
        binding.buttonSelectAll.setOnClickListener {
            scanResultAdapter.selectAllNew()
            updateActionSummary()
        }
        binding.buttonSelectInvert.setOnClickListener {
            scanResultAdapter.selectInvertNew()
            updateActionSummary()
        }
        binding.buttonAdd.setOnClickListener {
            saveSelectedFolders()
        }
    }

    private fun initStorageOptions() {
        storageOptions.clear()
        val distinctRoots = linkedSetOf<String>()
        var externalIndex = 0
        getExternalFilesDirs(null).forEachIndexed { index, dir ->
            val rootPath = dir?.path?.substringBefore("/Android/data")?.normalizedPath().orEmpty()
            if (rootPath.isBlank() || !distinctRoots.add(rootPath)) {
                return@forEachIndexed
            }
            val title = if (index == 0) {
                "本地存储"
            } else {
                externalIndex += 1
                if (externalIndex == 1) "外部存储" else "外部存储 $externalIndex"
            }
            storageOptions.add(StorageRootOption(title, rootPath))
        }
        if (storageOptions.isEmpty()) {
            storageOptions.add(StorageRootOption("本地存储", "/storage/emulated/0"))
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, storageOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerStorage.adapter = adapter
        binding.spinnerStorage.setSelection(0)
    }

    private fun startScan(force: Boolean = false) {
        if (!force && scanJob?.isActive == true) {
            ToastUtil.showToast(this, "扫描进行中")
            return
        }
        if (!PermissionUtil.checkPermissions(this, PermissionUtil.storagePermissions)) {
            permissionLauncher.launch(PermissionUtil.storagePermissions)
            return
        }
        val storage = binding.spinnerStorage.selectedItem as? StorageRootOption
        if (storage == null) {
            ToastUtil.showToast(this, "未找到可扫描的存储目录")
            return
        }
        scanJob?.cancel()
        minFileSizeKb = (binding.etMinFileSize.text.toString().toIntOrNull() ?: 10).coerceAtLeast(0)
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    setScanning(true)
                    scanResultAdapter.updateData(emptyList())
                    updateResultSummary(emptyList(), 0)
                }
                val selectedRootPath = if (filterBySelectedStorage) storage.rootPath else null
                val minFileSizeBytes = minFileSizeKb.toLong() * 1024
                val summary = if (!scanNoMedia && !scanHide) {
                    scanWithMediaStore(selectedRootPath, skipNoMedia = true, minFileSizeBytes = minFileSizeBytes)
                } else {
                    scanWithFileTree(storage.rootPath, skipNoMedia = !scanNoMedia, minFileSizeBytes = minFileSizeBytes)
                }
                val result = diffWithLibrary(summary.folders)
                withContext(Dispatchers.Main) {
                    scanResultAdapter.updateData(result)
                    updateResultSummary(result, summary.totalVideoCount)
                    setScanning(false)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                Log.d(this@MediaScanActivity, "scan failed", e)
                withContext(Dispatchers.Main) {
                    setScanning(false)
                    ToastUtil.showToast(this@MediaScanActivity, "扫描失败")
                }
            }
        }
    }

    private suspend fun scanWithMediaStore(rootPath: String?, skipNoMedia: Boolean, minFileSizeBytes: Long): ScanSummary {
        val normalizedRoot = rootPath?.normalizedPath()
        val rootPrefix = normalizedRoot?.let { "$it/" }
        val folderCounter = linkedMapOf<String, Int>()
        var totalVideoCount = 0
        val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE)
        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val fileSize = cursor.getLong(sizeIndex)
                if (fileSize < minFileSizeBytes) {
                    continue
                }
                val filePath = cursor.getStringOrNull(dataIndex).orEmpty().normalizedPath()
                if (normalizedRoot != null && rootPrefix != null && !filePath.startsWith(rootPrefix) && filePath != normalizedRoot) {
                    continue
                }
                val file = File(filePath)
                val parent = file.parentFile ?: continue
                if (shouldSkipFolder(parent, normalizedRoot, includeHidden = false, skipNoMedia = skipNoMedia)) {
                    continue
                }
                val key = parent.path.normalizedPath()
                folderCounter[key] = (folderCounter[key] ?: 0) + 1
                totalVideoCount++
            }
        }
        return ScanSummary(totalVideoCount, folderCounter.toFolderResults())
    }

    private suspend fun scanWithFileTree(rootPath: String, skipNoMedia: Boolean, minFileSizeBytes: Long): ScanSummary {
        val normalizedRoot = rootPath.normalizedPath()
        val root = File(normalizedRoot)
        if (!root.exists() || !root.canRead()) {
            return ScanSummary(0, emptyList())
        }
        val stack = ArrayDeque<File>()
        val folderCounter = linkedMapOf<String, Int>()
        var totalVideoCount = 0
        stack.add(root)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = stack.removeLast()
            if (!current.exists() || !current.isDirectory || !current.canRead()) {
                continue
            }
            val children = current.listFiles() ?: continue
            var currentVideoCount = 0
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (child.isDirectory) {
                    if (shouldSkipFolder(child, normalizedRoot, includeHidden = true, skipNoMedia = skipNoMedia)) {
                        continue
                    }
                    stack.add(child)
                } else if (child.isVideo() && child.length() >= minFileSizeBytes) {
                    currentVideoCount++
                }
            }
            if (currentVideoCount > 0 && current.path.normalizedPath() != normalizedRoot) {
                folderCounter[current.path.normalizedPath()] = currentVideoCount
                totalVideoCount += currentVideoCount
            }
        }
        return ScanSummary(totalVideoCount, folderCounter.toFolderResults())
    }

    private suspend fun diffWithLibrary(scannedFolders: List<ScanFolderResult>): List<ScanFolderResult> {
        // Room3：sources/media 改为 DAO 异步读取
        val sourceList = DatabaseManager.sourceDataDao.list()
        val mediaList = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first()
        return scannedFolders.map { folder ->
            val media = MediaData().apply {
                title = folder.name
                path = folder.path
                type = MediaLibType.LOCAL
                storageType = StorageType.LOCAL_STORAGE
            }
            val exists = mediaList.any { LibraryCompat.sameMedia(it, media, sourceList) }
            folder.copy(
                existsInLibrary = exists,
                selected = !exists
            )
        }.sortedWith(
            compareBy<ScanFolderResult> { it.existsInLibrary }
                .thenByDescending { it.videoCount }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun saveSelectedFolders() {
        val selected = scanResultAdapter.selectedNewFolders()
        if (selected.isEmpty()) {
            ToastUtil.showToast(this, "未选择新增目录")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // Room3：sources/media 改为 DAO 异步读取，逐条查重后单条落库
            val sourceList = DatabaseManager.sourceDataDao.list()
            val mediaList = DatabaseManager.mediaDataDao.observeLibraryPage(200, 0).first()
            var addedCount = 0
            selected.forEach { folder ->
                val media = MediaData().apply {
                    title = folder.name
                    path = folder.path
                    type = MediaLibType.LOCAL
                    storageType = StorageType.LOCAL_STORAGE
                }
                media.id = LibraryCompat.mediaId(media, sourceList)
                if (mediaList.none { LibraryCompat.sameMedia(it, media, sourceList) }) {
                    // 单条新增（本地媒体库无 items，播放时按目录实时枚举）
                    DatabaseManager.mediaDataDao.insert(media)
                    addedCount++
                }
            }
            withContext(Dispatchers.Main) {
                if (addedCount == 0) {
                    ToastUtil.showToast(this@MediaScanActivity, "没有新增媒体库")
                    return@withContext
                }
                setResult(RESULT_OK)
                ToastUtil.showToast(this@MediaScanActivity, "已加入 $addedCount 个媒体库")
                finish()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateResultSummary(list: List<ScanFolderResult>, totalVideoCount: Int) {
        val existingCount = list.count { it.existsInLibrary }
        val newCount = list.size - existingCount
        binding.tvScanInfo.text = "扫描结果：目录 ${list.size}，已存在 ${existingCount}，新增 ${newCount}"
        binding.tvScanCount.text = "总视频文件数：${totalVideoCount}"
        updateActionSummary()
    }

    @SuppressLint("SetTextI18n")
    private fun updateActionSummary() {
        binding.buttonAdd.text = "加入媒体库(${scanResultAdapter.selectedNewFolders().size})"
    }

    private fun setScanning(scanning: Boolean) {
        binding.buttonScan.isEnabled = !scanning
        binding.buttonSelectAll.isEnabled = !scanning
        binding.buttonSelectInvert.isEnabled = !scanning
        binding.buttonAdd.isEnabled = !scanning
        if (scanning) {
            showLoading()
        } else {
            hideLoading()
        }
    }

    private fun shouldSkipFolder(folder: File, rootPath: String?, includeHidden: Boolean, skipNoMedia: Boolean): Boolean {
        val path = folder.path.normalizedPath()
        if (path.contains("/Android/") || path.endsWith("/Android")) {
            return true
        }
        if (!includeHidden && isHiddenFolder(folder, rootPath)) {
            return true
        }
        if (skipNoMedia && hasNoMediaFile(folder, rootPath)) {
            return true
        }
        return false
    }

    private fun isHiddenFolder(folder: File, rootPath: String?): Boolean {
        val normalizedRoot = rootPath?.normalizedPath()
        var current: File? = folder
        while (current != null) {
            val currentPath = current.path.normalizedPath()
            if (normalizedRoot != null && !currentPath.startsWith(normalizedRoot)) {
                break
            }
            if (current.name.startsWith(".") || current.isHidden) {
                return true
            }
            if (normalizedRoot != null && currentPath == normalizedRoot) {
                break
            }
            current = current.parentFile
        }
        return false
    }

    private fun hasNoMediaFile(folder: File, rootPath: String?): Boolean {
        val normalizedRoot = rootPath?.normalizedPath()
        var current: File? = folder
        while (current != null) {
            val currentPath = current.path.normalizedPath()
            if (normalizedRoot != null && !currentPath.startsWith(normalizedRoot)) {
                break
            }
            if (File(current, ".nomedia").exists()) {
                return true
            }
            if (normalizedRoot != null && currentPath == normalizedRoot) {
                break
            }
            current = current.parentFile
        }
        return false
    }

    private fun Map<String, Int>.toFolderResults(): List<ScanFolderResult> {
        return entries.map { entry ->
            val dir = File(entry.key)
            ScanFolderResult(
                path = dir.path.normalizedPath(),
                name = dir.name.ifBlank { dir.path.normalizedPath() },
                videoCount = entry.value
            )
        }
    }

    private fun String.normalizedPath(): String {
        return replace('\\', '/').trimEnd('/')
    }

    override fun onDestroy() {
        scanJob?.cancel()
        super.onDestroy()
        _binding = null
    }

}
