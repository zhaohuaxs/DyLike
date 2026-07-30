package me.lingci.dy.player.ui.tool

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import me.lingci.dy.player.databinding.FragmentLabSettingBinding
import me.lingci.dy.player.ui.long_video.LongVideoActivity
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
        binding.swLabMpvSequentialRead.isChecked = spUtil.labMpvSequentialRead
        binding.swLabMpvSequentialRead.setOnClickListener {
            spUtil.labMpvSequentialRead = binding.swLabMpvSequentialRead.isChecked
        }
        binding.swLabMpvSuperResolution.isChecked = spUtil.labMpvSuperResolution
        binding.swLabMpvSuperResolution.setOnClickListener {
            val on = binding.swLabMpvSuperResolution.isChecked
            spUtil.labMpvSuperResolution = on
            // 立即对当前 ExoPlayer 实例生效（运行时切换 render view 会触发 player 重播）
            val action = if (on) LongVideoActivity.ACTION_SUPER_RESOLUTION_ON
                         else LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF
            requireContext().sendBroadcast(Intent(action))
            Toast.makeText(
                requireContext(),
                if (on) "已开启画质增强" else "已关闭画质增强",
                Toast.LENGTH_SHORT
            ).show()
        }
        // 锐化强度输入框：0.0~3.0，默认 1.0；失焦时写 SP
        binding.etSuperResolutionStrength.setText(spUtil.labSuperResolutionStrength.toString())
        binding.etSuperResolutionStrength.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val raw = binding.etSuperResolutionStrength.text.toString().trim().toFloatOrNull()
                val clamped = (raw ?: 1.0f).coerceIn(0.0f, 3.0f)
                binding.etSuperResolutionStrength.setText(clamped.toString())
                if (spUtil.labSuperResolutionStrength != clamped) {
                    spUtil.labSuperResolutionStrength = clamped
                    Toast.makeText(
                        requireContext(),
                        "锐化强度已更新为 $clamped（重播以生效）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
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
