package me.lingci.dy.player.ui.tool

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import me.lingci.dy.player.databinding.FragmentSettingBinding
import me.lingci.dy.player.ui.about.AboutActivity
import me.lingci.dy.player.ui.icon.IconSettingActivity
import me.lingci.dy.player.ui.main.BaseTransitionFragment
import me.lingci.dy.player.util.SpUtil

/**
 * 播放器设置
 */
class SettingFragment : BaseTransitionFragment() {

    private val spUtil by lazy { SpUtil(this.requireContext()) }
    private lateinit var binding: FragmentSettingBinding
    // 封面比例选项
    private val coverRatios = listOf("3:4", "1:1", "4:3")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onResume() {
        super.onResume()
        initSettings()
    }

    fun init() {
        initView()
    }

    private fun initView() {
        initSettings()
        initGlobalOption()
        initPlayerOption()
        initDanmakuOption()
        initBackupOption()
        initLabOption()
        initOtherOption()
    }

    private fun initSettings() {
        binding.swPlayMode.isChecked = spUtil.longVideoMode
        binding.swVideoDetail.isChecked = spUtil.videoDetailMode
        binding.swNewHome.isChecked = spUtil.launchComposeHome
    }

    private fun initGlobalOption() {
        binding.swPlayMode.setOnClickListener {
            spUtil.longVideoMode = binding.swPlayMode.isChecked
        }
        binding.swVideoDetail.setOnClickListener {
            spUtil.videoDetailMode = binding.swVideoDetail.isChecked
        }
        // 只控制启动去向：开 = 冷启动直接进 Compose 首页；平铺/分类在媒体库首页 toolbar 的「切换布局」里
        binding.swNewHome.setOnClickListener {
            spUtil.launchComposeHome = binding.swNewHome.isChecked
        }

        // 封面比例 Spinner
        val coverRatioAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, coverRatios)
        coverRatioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCoverRatio.adapter = coverRatioAdapter
        binding.spinnerCoverRatio.setSelection(coverRatios.indexOf(spUtil.coverRatio).coerceAtLeast(0))
        binding.spinnerCoverRatio.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                spUtil.coverRatio = coverRatios[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun initPlayerOption() {
        binding.playerSettings.setOnClickListener {
            ToolActivity.start(requireContext(), ToolActivity.PAGE_PLAYER)
        }
    }

    private fun initDanmakuOption() {
        binding.danmakuSettings.setOnClickListener {
            ToolActivity.start(requireContext(), ToolActivity.PAGE_DANMAKU)
        }
    }

    private fun initBackupOption() {
        binding.dataBackup.setOnClickListener {
            ToolActivity.start(requireContext(), ToolActivity.PAGE_BACKUP)
        }
    }

    private fun initLabOption() {
        binding.labSettings.setOnClickListener {
            ToolActivity.start(requireContext(), ToolActivity.PAGE_LAB)
        }
    }

    private fun initOtherOption() {
        // 其他设置
        binding.changeIcon.setOnClickListener{
            requireActivity().startActivity(Intent(requireActivity(), IconSettingActivity::class.java))
        }
        binding.aboutDy.setOnClickListener{
            requireActivity().startActivity(Intent(requireActivity(), AboutActivity::class.java))
        }
    }

}
