package me.lingci.lib.player.widget.component

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.SpBase
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.newFile
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.entity.DmTrackConf
import me.lingci.lib.dm.view.entity.DmTrackMode
import me.lingci.lib.dm.view.util.ZipXmlLoader
import me.lingci.lib.player.adapter.FileSelectAdapter
import me.lingci.lib.player.ui.databinding.LayoutDmSelectControlViewBinding
import xyz.doikki.videoplayer.controller.ControlWrapper
import xyz.doikki.videoplayer.controller.IControlComponent
import java.io.File

/**
 * @author : happyc
 * time    : 2023/07/06
 * desc    : 弹幕选择
 * version : 1.0
 */
class DmSelectControlView : FrameLayout, IControlComponent {

    companion object {
        private const val TAG = "DmSelectControlView"
    }

    private var binding: LayoutDmSelectControlViewBinding =
        LayoutDmSelectControlViewBinding.inflate(
            LayoutInflater.from(
                context
            ), this, true
        )
    private val spBase: SpBase by lazy { SpBase(context) }
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var onSelectDm: ((trackConf: DmTrackConf) -> Unit)? = null
    private var dmTrackConf = DmTrackConf(trackMode = DmTrackMode.SINGLE_SWITCH)
    private var currentFolder: String? = null
    private var currentZip: File? = null
    private var currentPassword: String = ""
    // 当前压缩包条目：name -> 条目 key（原始名/索引），供选中时写入 DmTrack
    private var zipEntriesByName: Map<String, ZipXmlLoader.XmlEntry> = emptyMap()

    private lateinit var fileSelectAdapter: FileSelectAdapter
    private lateinit var controlWrapper: ControlWrapper

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    fun setOnDmSelectListener(onSelectDm: ((trackConf: DmTrackConf) -> Unit)) {
        this.onSelectDm = onSelectDm
    }

    init {
        visibility = GONE
        initComponents()
    }

    private fun getCurrentFolder(): String {
        if (currentFolder == null) {
            currentFolder = spBase.lastFolder
        }
        return currentFolder.toString()
    }

    private fun initComponents() {
        binding.defaultFolder.setOnClickListener {
            changeFileData(FileOperator.buildDownFile("弹目"))
        }
        binding.lastFolder.setOnClickListener {
            if (getCurrentFolder().isNotBlank()) {
                changeFileData(File(getCurrentFolder()))
            } else {
                changeFileData(FileOperator.buildDownFile("弹目"))
            }
        }

        initListener()
        initRecyclerView()
    }

    private fun initListener() {
        setOnClickListener { switchVib() }
        binding.container.setOnClickListener { }
        binding.actionClose.setOnClickListener { switchVib() }
        binding.tvDmLib.setOnClickListener { ToastUtil.showToast(context, "暂不支持更换弹幕库") }
        binding.actionCancel.setOnClickListener {
            showFilePanel()
            binding.inputPassword.setText("")
        }
        binding.actionConfirmed.setOnClickListener {
            val zipFile = currentZip ?: return@setOnClickListener
            val password = binding.inputPassword.text.toString()
            if (password.isBlank()) {
                ToastUtil.showToast(context, "请输入压缩包密码")
                return@setOnClickListener
            }
            when (ZipXmlLoader.verifyPassword(zipFile, password)) {
                ZipXmlLoader.PasswordVerifyResult.VALID -> {
                    saveArchivePassword(zipFile.path, password)
                    loadZipEntries(zipFile, password)
                }

                ZipXmlLoader.PasswordVerifyResult.WRONG_PASSWORD -> {
                    currentPassword = ""
                    removeArchivePassword(zipFile.path)
                    ToastUtil.showToast(context, "密码错误，请重新输入")
                }

                ZipXmlLoader.PasswordVerifyResult.ERROR -> {
                    currentPassword = ""
                    ToastUtil.showToast(context, "读取压缩包失败")
                }

                ZipXmlLoader.PasswordVerifyResult.UNSUPPORTED_METHOD -> {
                    currentPassword = ""
                    ToastUtil.showToast(context, "当前压缩方法不支持")
                }

                ZipXmlLoader.PasswordVerifyResult.ENTRY_NOT_FOUND -> {
                    currentPassword = ""
                    ToastUtil.showToast(context, "压缩包中未找到可读取文件")
                }
            }
        }
    }

    private fun archivePasswordMap(): MutableMap<String, String> {
        val json = spBase.archivePasswordMapJson.orEmpty().ifBlank { "{}" }
        val map = try {
            JsonUtil.toEntity<Map<String, String>>(json).toMutableMap()
        } catch (e: Exception) {
            Log.d(this, "read archive password map failed", e)
            mutableMapOf()
        }
        // 包已不存在（移动/删除/权限失效）时清理残留密码，避免无限累积
        val staleKeys = map.keys.filterNot { File(it).exists() }
        if (staleKeys.isNotEmpty()) {
            staleKeys.forEach { map.remove(it) }
            spBase.archivePasswordMapJson = JsonUtil.toJsonString(map)
            Log.d(this, "clean stale archive passwords", staleKeys.size)
        }
        return map
    }

    private fun saveArchivePassword(path: String, password: String) {
        val passwordMap = archivePasswordMap()
        passwordMap[path] = password
        spBase.archivePasswordMapJson = JsonUtil.toJsonString(passwordMap)
    }

    private fun removeArchivePassword(path: String) {
        val passwordMap = archivePasswordMap()
        if (passwordMap.remove(path) != null) {
            spBase.archivePasswordMapJson = JsonUtil.toJsonString(passwordMap)
        }
    }

    private fun getArchivePassword(path: String): String {
        return archivePasswordMap()[path].orEmpty()
    }

    /** 供播放侧在密码错误时回查该压缩包最近一次有效密码（SP 为唯一数据源） */
    fun archivePassword(path: String): String {
        return getArchivePassword(path)
    }

    /** 播放侧用缓存密码重试成功后回写 SP，保证下次恢复直接使用正确密码 */
    fun updateArchivePassword(path: String, password: String) {
        saveArchivePassword(path, password)
    }

    /** 清除失效密码缓存（密码错误且重试失败后调用） */
    fun clearArchivePassword(path: String) {
        removeArchivePassword(path)
    }

    /**
     * “重新选择”入口：打开弹幕选择面板并预填指定压缩包路径。
     * 若有有效缓存密码则直接展开条目，否则进入密码输入页。
     */
    fun selectArchive(path: String) {
        val file = File(path)
        if (!file.exists()) {
            ToastUtil.showToast(context, "压缩包不存在，请重新选择")
            return
        }
        if (!this.isVisible) {
            this.visibility = VISIBLE
        }
        binding.inputPassword.setText("")
        showFilePanel()
        changeZipData(file)
    }

    private fun showFilePanel() {
        binding.container.visibility = VISIBLE
        binding.clPassword.visibility = GONE
    }

    private fun showPasswordPanel(message: String? = null) {
        binding.container.visibility = GONE
        binding.clPassword.visibility = VISIBLE
        binding.inputPassword.setText("")
        if (message != null) {
            ToastUtil.showToast(context, message)
        }
    }

    private fun loadZipEntries(file: File, password: String = "") {
        currentPassword = password
        binding.inputPassword.setText("")
        ZipXmlLoader.listXmlEntriesDetailed(file, password).let { entries ->
            zipEntriesByName = entries.associateBy { it.file.name }
            val list = entries.map { it.file }
            binding.recyclerView.post { fileSelectAdapter.setData(file, list) }
        }
        showFilePanel()
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        // 文件选择
        fileSelectAdapter = FileSelectAdapter(ArrayList())
        fileSelectAdapter.onItemClick{ item: FileEntity, _: Int ->
            if (item.isFile) {
                if (item.returnParent) {
                    changeFileData(File(item.path))
                } else {
                    if (item.name.endsWith(ZipXmlLoader.ZIP_EXTENSION)
                        || item.name.endsWith(ZipXmlLoader.SEVEN_Z_EXTENSION)
                        || item.name.endsWith(ZipXmlLoader.RAR_EXTENSION)) {
                        Log.d(this, item.name, "1", item.path, "2", item.fullPath)
                        changeZipData(File(item.path))
                    } else {
                        // 压缩包条目：写入条目级 key；普通 XML 文件三个字段保持空/-1
                        val zipEntry = currentZip?.let { zipEntriesByName[item.name] }
                        dmTrackConf.dmTrack = DmTrack(
                            title = item.title,
                            path = item.path,
                            mineType = item.mimeType,
                            password = currentPassword,
                            archiveEntryName = zipEntry?.file?.name ?: "",
                            archiveEntryRaw = zipEntry?.rawName ?: "",
                            archiveEntryIndex = zipEntry?.index ?: -1
                        )
                        onSelectDm?.invoke(dmTrackConf)
                        switchVib()
                    }
                }
            } else {
                changeFileData(File(item.path))
            }
        }
        binding.recyclerView.adapter = fileSelectAdapter
        if (getCurrentFolder().isBlank()) {
            changeFileData(FileOperator.rootFolder)
        } else {
            changeFileData(getCurrentFolder().newFile())
        }
        binding.fastScroller.scrollNow()
        binding.fastScroller.attachRecyclerView(binding.recyclerView)
    }

    private fun changeZipData(file: File) {
        currentZip = file
        currentPassword = ""
        val usePassword = ZipXmlLoader.checkPassword(file)
        Log.d(this, "password", usePassword)
        if (usePassword) {
            val cachePassword = getArchivePassword(file.path)
            if (cachePassword.isBlank()) {
                showPasswordPanel()
                return
            }
            when (ZipXmlLoader.verifyPassword(file, cachePassword)) {
                ZipXmlLoader.PasswordVerifyResult.VALID -> loadZipEntries(file, cachePassword)
                ZipXmlLoader.PasswordVerifyResult.WRONG_PASSWORD -> {
                    removeArchivePassword(file.path)
                    showPasswordPanel("缓存密码错误，请重新输入")
                }

                ZipXmlLoader.PasswordVerifyResult.UNSUPPORTED_METHOD -> {
                    showFilePanel()
                    ToastUtil.showToast(context, "当前压缩方法不支持")
                }

                ZipXmlLoader.PasswordVerifyResult.ENTRY_NOT_FOUND -> {
                    showFilePanel()
                    ToastUtil.showToast(context, "压缩包中未找到可读取文件")
                }

                ZipXmlLoader.PasswordVerifyResult.ERROR -> {
                    showFilePanel()
                    ToastUtil.showToast(context, "校验压缩包失败")
                }
            }
        } else {
            loadZipEntries(file)
        }
    }

    private fun changeFileData(file: File) {
        currentZip = null
        currentPassword = ""
        zipEntriesByName = emptyMap()
        if (file.path != FileOperator.buildDownFile("弹目").path && file.path != FileOperator.rootFolder.path) {
            spBase.lastFolder = file.path
            currentFolder = file.path
        }
        FileOperator.getSortedFiles(file, true, FileOperator.DM_EXTENSIONS)
            .map { item -> FileEntity(item) }
            .let { list ->
                binding.recyclerView.post { fileSelectAdapter.setData(file, list) }
            }
    }

    fun switchVib() {
        if (this.isVisible) {
            this.visibility = GONE
            showFilePanel()
            currentZip = null
            currentPassword = ""
            binding.inputPassword.setText("")
        } else {
            this.visibility = VISIBLE
        }
    }

    fun resetView() {

    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewScope.cancel()
    }

    override fun attach(controlWrapper: ControlWrapper) {
        this.controlWrapper = controlWrapper
    }

    override fun getView(): View {
        return this
    }

    override fun onVisibilityChanged(isVisible: Boolean, anim: Animation) {}
    override fun onPlayStateChanged(playState: Int) {}
    override fun onPlayerStateChanged(playerState: Int) {}
    override fun setProgress(duration: Int, position: Int) {}
    override fun onLockStateChanged(isLocked: Boolean) {}

}
