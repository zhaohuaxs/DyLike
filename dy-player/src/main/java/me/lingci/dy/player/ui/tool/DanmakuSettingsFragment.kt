package me.lingci.dy.player.ui.tool

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.FragmentDanmakuSettingBinding
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.ui.BaseFragment
import me.lingci.lib.compose.fileselect.FileSelectorActivity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.notExists
import java.io.File

/**
 * 弹幕设置
 */
class DanmakuSettingsFragment : BaseFragment() {

    private var _binding: FragmentDanmakuSettingBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>
    private lateinit var mDmMergeOptionDialog: DmMergeOptionDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDanmakuSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        initResult()
        initView()
        initDmOption()
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbar.title = "弹幕设置"

    }

    @SuppressLint("DefaultLocale")
    private fun initDmOption() {
        // 弹幕设置
        binding.swDmGradient.isChecked = spUtil.dmGradientMode
        binding.tvDmGradientDesc.text = resources.getString(R.string.hint_dm_gradient_desc, "${spUtil.dmGradientRatio}")
        binding.swDmGradient.setOnClickListener {
            spUtil.dmGradientMode = binding.swDmGradient.isChecked
        }
        val seekBarDialog = SeekBarDialog()
        seekBarDialog.onValueListener {
            if (it is Int) {
                binding.tvDmGradientDesc.text =
                    resources.getString(R.string.hint_dm_gradient_desc, "$it")
                spUtil.dmGradientRatio = it
            } else {
                binding.tvDmStrokeMultipleDesc.text =
                    resources.getString(R.string.hint_dm_stroke_multiple_desc, String.format("%.2f", it.toFloat()))
                spUtil.dmStrokeMultiple = it.toFloat()
            }
        }
        binding.dmGradient.setOnLongClickListener {
            seekBarDialog.arguments = SeekBarDialog.buildData("设置渐变描边比例", spUtil.dmGradientRatio)
            seekBarDialog.show(childFragmentManager, seekBarDialog.tag)
            true
        }
        binding.swDmGradientTextColor.isChecked = spUtil.dmGradientWithTextColor
        binding.swDmGradientTextColor.setOnClickListener {
            spUtil.dmGradientWithTextColor = binding.swDmGradientTextColor.isChecked
        }
        binding.tvDmStrokeMultipleDesc.text =
            resources.getString(R.string.hint_dm_stroke_multiple_desc, String.format("%.2f", spUtil.dmStrokeMultiple))
        binding.dmStrokeMultiple.setOnLongClickListener {
            seekBarDialog.arguments = SeekBarDialog.buildData("设置渐变描边宽度倍率", (spUtil.dmStrokeMultiple * 50).toInt(), 50)
            seekBarDialog.show(childFragmentManager, seekBarDialog.tag)
            true
        }
        binding.swDmStrokeMultiple.isChecked = spUtil.dmStrokeMultipleMode
        binding.swDmStrokeMultiple.setOnClickListener {
            spUtil.dmStrokeMultipleMode = binding.swDmStrokeMultiple.isChecked
        }

        var fontName = "默认"
        spUtil.dmCurrentFont?.let {
            if (it.isNotBlank()) {
                val file = File(it)
                if (file.exists()) {
                    fontName = file.nameWithoutExtension
                }
            }
        }
        binding.tvDmFontDesc.text = resources.getString(R.string.hint_dm_font_desc, fontName)
        binding.swDmFont.isChecked = spUtil.dmFontMode
        binding.swDmFont.setOnClickListener {
            spUtil.dmFontMode = binding.swDmFont.isChecked
        }
        binding.dmFont.setOnLongClickListener {
            FileSelectorActivity.startSingle(requireActivity(), FileOperator.FONT_EXTENSIONS, resultLauncher)
            true
        }

        binding.swDmMerge.isChecked = spUtil.dmMergeMode
        binding.swDmMerge.setOnClickListener {
            spUtil.dmMergeMode = binding.swDmMerge.isChecked
        }
        mDmMergeOptionDialog = DmMergeOptionDialog()
        mDmMergeOptionDialog.onValueListener { show, top ->
            spUtil.dmMergeShow = show
            spUtil.dmMergeTop = top
        }
        binding.dmMerge.setOnLongClickListener {
            mDmMergeOptionDialog.arguments = DmMergeOptionDialog.buildData("弹幕合并显示设置", spUtil.dmMergeShow, spUtil.dmMergeTop)
            mDmMergeOptionDialog.show(childFragmentManager, mDmMergeOptionDialog.tag)
            true
        }

        binding.swDmShowTime.isChecked = spUtil.dmShowTime
        binding.swDmShowTime.setOnClickListener {
            spUtil.dmShowTime = binding.swDmShowTime.isChecked
        }

        binding.swDmShowFps.isChecked = spUtil.showDmFps
        binding.swDmShowFps.setOnClickListener {
            spUtil.showDmFps = binding.swDmShowFps.isChecked
        }

        binding.swDmFilter.isChecked = spUtil.dmFilterMode
        binding.swDmFilter.setOnClickListener {
            spUtil.dmFilterMode = binding.swDmFilter.isChecked
        }
        val dmFilterDialog = DmFilterDialog()
        binding.dmFilter.setOnLongClickListener {
            dmFilterDialog.show(childFragmentManager, dmFilterDialog.tag)
            true
        }
    }

    private fun initResult() {
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == AppCompatActivity.RESULT_OK) {
                result.data?.let { intent ->
                    intent.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)?.let { list ->
                        loadFont(list.first())
                    }
                }
            }
        }
    }

    private fun loadFont(path: String) {
        Log.d(this@DanmakuSettingsFragment, "load font", path)
        if (path.isNotBlank()) {
            File(path).let {source ->
                if (source.exists()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val file = AppFile(requireContext()).buildCustom("font", source.name)
                        if (file.notExists()) {
                            source.copyTo(file)
                        }
                        withContext(Dispatchers.Main) {
                            spUtil.dmCurrentFont = file.path
                            binding.tvDmFontDesc.text = resources.getString(R.string.hint_dm_font_desc,
                                source.nameWithoutExtension
                            )
                        }
                    }
                }
            }
        }
    }


    override fun resetView() {

    }

}