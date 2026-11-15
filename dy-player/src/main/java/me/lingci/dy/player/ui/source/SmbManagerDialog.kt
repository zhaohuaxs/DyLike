package me.lingci.dy.player.ui.source

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.DialogSourceSmbBinding
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.StorageConfig
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.storage.impl.SmbStorage
import me.lingci.lib.base.storage.smb.SmbErrorType
import me.lingci.lib.base.storage.smb.SmbErrors
import me.lingci.lib.base.storage.smb.SmbTestResult
import me.lingci.lib.base.util.ToastUtil

class SmbManagerDialog(
    private val onSave: (source: SourceData) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var binding: DialogSourceSmbBinding
    private var mSourceData: SourceData? = null
    private var storage: IStorage? = null
    private var testJob: Job? = null

    companion object {
        const val KEY_SOURCE = "source"

        fun newInstance(
            bean: SourceData?,
            onClick: (source: SourceData) -> Unit
        ): SmbManagerDialog {
            val f = SmbManagerDialog(onClick)
            val args = Bundle()
            args.putParcelable(KEY_SOURCE, bean)
            f.arguments = args
            return f
        }
    }

    constructor() : this(onSave = {})

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
            mSourceData = it.getParcelable(KEY_SOURCE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DialogSourceSmbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        mSourceData?.let { source ->
            val (domain, username) = splitUsername(source.username)
            binding.toolbar.title = getString(R.string.hint_source_smb_edit)
            binding.inputName.editText?.setText(source.title)
            binding.inputServer.editText?.setText(source.siteUrl)
            binding.inputShare.editText?.setText(source.schema)
            binding.inputPort.editText?.setText(source.port.ifBlank { "445" })
            binding.inputDomain.editText?.setText(domain)
            binding.inputUsername.editText?.setText(username)
            binding.inputPassword.editText?.setText(source.password)
        }

        binding.actionTest.setOnClickListener {
            binding.textStatus.text = ""
            val config = readConfig(id = "", name = "") ?: return@setOnClickListener
            storage?.release()
            storage = SmbStorage(config, "")
            if (testJob != null && testJob!!.isCompleted.not()) {
                return@setOnClickListener
            }
            testJob = lifecycleScope.launch(Dispatchers.IO) {
                // 带错误分类的连接测试（阶段 A3），失败时区分认证/网络/共享不存在
                val result = (storage as? SmbStorage)?.testConnectDetailed()
                    ?: SmbTestResult(false)
                withContext(Dispatchers.Main) {
                    if (result.success) {
                        binding.textStatus.text = "连接成功"
                        binding.textStatus.setTextColor(
                            resources.getColor(
                                me.lingci.lib.base.R.color.green_500,
                                requireContext().theme
                            )
                        )
                    } else {
                        binding.textStatus.text = SmbErrors.userMessage(
                            result.errorType ?: SmbErrorType.UNKNOWN
                        )
                        binding.textStatus.setTextColor(
                            resources.getColor(
                                me.lingci.lib.base.R.color.red_600,
                                requireContext().theme
                            )
                        )
                    }
                }
            }
        }
        binding.actionCancel.setOnClickListener { dismiss() }
        binding.actionConfirmed.setOnClickListener {
            val title = binding.inputName.editText?.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: getString(R.string.default_source_smb_name)
            val config = readConfig(id = mSourceData?.id.orEmpty(), name = title) ?: return@setOnClickListener
            val source = SourceData().apply {
                type = StorageType.SMB
                id = mSourceData?.id.orEmpty()
                this.title = title
                siteUrl = config.server
                schema = config.share
                port = config.port.toString()
                username = persistUsername(config.domain, config.username)
                password = config.password.orEmpty()
            }
            if (source.id.isBlank()) {
                source.id = LibraryCompat.sourceId(source)
            }
            onSave(source)
            dismiss()
        }
    }

    private fun readConfig(id: String, name: String): StorageConfig.SmbStorageConfig? {
        val rawServer = binding.inputServer.editText?.text?.toString()?.trim().orEmpty()
            .removePrefix("smb://")
        if (rawServer.isBlank()) {
            ToastUtil.showToast(requireContext(), "请填写服务器地址")
            return null
        }
        val hostAndMaybePort = rawServer.substringBefore('/')
        val server = hostAndMaybePort.substringBefore(':').trim()
        if (server.isBlank()) {
            ToastUtil.showToast(requireContext(), "请填写服务器地址")
            return null
        }
        val share = binding.inputShare.editText?.text?.toString()?.trim().orEmpty()
            .trim('/')
            .ifBlank { rawServer.substringAfter('/', "").trim('/') }
        if (share.isBlank()) {
            ToastUtil.showToast(requireContext(), "请填写共享名称")
            return null
        }
        val port = binding.inputPort.editText?.text?.toString()?.trim()?.toIntOrNull()
            ?: hostAndMaybePort.substringAfter(':', "").toIntOrNull()
            ?: 445
        return StorageConfig.SmbStorageConfig(
            id = id,
            name = name,
            server = server,
            port = port,
            share = share,
            domain = binding.inputDomain.editText?.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() },
            username = binding.inputUsername.editText?.text?.toString()?.trim()
                ?.takeIf { it.isNotBlank() },
            password = binding.inputPassword.editText?.text?.toString()
                ?.takeIf { it.isNotEmpty() }
        )
    }

    private fun splitUsername(value: String): Pair<String, String> {
        val backslashIndex = value.indexOf('\\')
        if (backslashIndex > 0 && backslashIndex < value.lastIndex) {
            return value.substring(0, backslashIndex) to value.substring(backslashIndex + 1)
        }
        val semicolonIndex = value.indexOf(';')
        if (semicolonIndex > 0 && semicolonIndex < value.lastIndex) {
            return value.substring(0, semicolonIndex) to value.substring(semicolonIndex + 1)
        }
        return "" to value
    }

    private fun persistUsername(domain: String?, username: String?): String {
        if (username.isNullOrBlank()) return ""
        return domain?.takeIf { it.isNotBlank() }?.let { "$it\\$username" } ?: username
    }

    override fun dismiss() {
        storage?.release()
        testJob?.cancel()
        super.dismiss()
    }
}
