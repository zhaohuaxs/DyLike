package me.lingci.dy.player.ui.tool

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import me.lingci.dy.player.databinding.FragmentLabSettingBinding
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.ui.BaseFragment

/**
 * 实验室设置
 */
class LabSettingsFragment : BaseFragment() {

    private var _binding: FragmentLabSettingBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLabSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    @SuppressLint("SetTextI18n")
    private fun init() {
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbar.title = "实验室"

        binding.swLabSurfaceRgba.isChecked = spUtil.labSurfaceRgba
        binding.swLabSurfaceRgba.setOnClickListener {
            spUtil.labSurfaceRgba = binding.swLabSurfaceRgba.isChecked
        }

        binding.swLabSurfaceZOrder.isChecked = spUtil.labSurfaceZOrder
        binding.swLabSurfaceZOrder.setOnClickListener {
            spUtil.labSurfaceZOrder = binding.swLabSurfaceZOrder.isChecked
        }
        binding.swLabMpvSpecialRender.isChecked = spUtil.labMpvSpecialRender
        binding.swLabMpvSpecialRender.setOnClickListener {
            spUtil.labMpvSpecialRender = binding.swLabMpvSpecialRender.isChecked
        }
        binding.swDebugMode.isChecked = spUtil.debugMode
        binding.swDebugMode.setOnClickListener {
            spUtil.debugMode = binding.swDebugMode.isChecked
        }
        binding.swLabLongVideoPortrait.isChecked = spUtil.labLongVideoPortrait
        binding.swLabLongVideoPortrait.setOnClickListener {
            spUtil.labLongVideoPortrait = binding.swLabLongVideoPortrait.isChecked
        }
        // App 启动自动播放：开启后可在媒体库长按菜单中指定一个媒体库
        binding.swLabAutoPlayOnLaunch.isChecked = spUtil.autoPlayOnLaunch
        binding.swLabAutoPlayOnLaunch.setOnClickListener {
            spUtil.autoPlayOnLaunch = binding.swLabAutoPlayOnLaunch.isChecked
        }
        // 短视频自动加载进度：开启时恢复上次进度，关闭时每次从头播放
        binding.swLabShortAutoLoadProgress.isChecked = spUtil.shortAutoLoadProgress
        binding.swLabShortAutoLoadProgress.setOnClickListener {
            spUtil.shortAutoLoadProgress = binding.swLabShortAutoLoadProgress.isChecked
        }
    }

    override fun resetView() {

    }

}
