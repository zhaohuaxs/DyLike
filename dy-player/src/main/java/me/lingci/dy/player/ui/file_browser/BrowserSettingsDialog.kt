package me.lingci.dy.player.ui.file_browser

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.DialogBrowserSettingsBinding
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.Log

/**
 * @author : happyc
 * time    : 2024/09/21
 * desc    :
 * version : 1.0
 */
open class BrowserSettingsDialog(
    private var onChange: () -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var binding: DialogBrowserSettingsBinding
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }

    constructor() : this(onChange = {})

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

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DialogBrowserSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        Log.d(this@BrowserSettingsDialog, "onViewStateRestored")
        super.onViewStateRestored(savedInstanceState)
    }

    override fun onDestroyView() {
        Log.d(this@BrowserSettingsDialog, "onDestroyView")
        super.onDestroyView()
        onChange.invoke()
    }

    private fun init() {
        // 只剩「显示隐藏文件夹」：旧版的「设置应用于所有页面」与排序单选组写了 SP 却没人读，
        // 排序现在由浏览页自己的排序控件负责，这里不再提供重复且无效的入口。
        binding.swShowHide.isChecked = spUtil.browserShowHide
        binding.swShowHide.setOnCheckedChangeListener { _, checked ->
            spUtil.browserShowHide = checked
        }
    }

}
