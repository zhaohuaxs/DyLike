package me.lingci.dy.player.ui.tool

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.core.ShortTitleStrategy
import me.lingci.dy.player.databinding.FragmentPlayerSettingBinding
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.ui.BaseFragment
import me.lingci.lib.player.danmaku.PlayerInitializer

/**
 * 播放设置
 */
class PlayerSettingsFragment : BaseFragment() {

    private var _binding: FragmentPlayerSettingBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }

    // 渐入渐出时长选项（ms）
    private val fadeDurationOptions = listOf(500, 1000, 1500, 2000)
    private val fadeDurationLabels = listOf("0.5s", "1s", "1.5s", "2s")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlayerSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        initView()
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbar.title = "播放器设置"

        binding.swVideoPlayer.isChecked = spUtil.dyPlayerCore == DyPlayerCore.MPV
        binding.swVideoPlayer.setOnClickListener {
            spUtil.dyPlayerCore = if (binding.swVideoPlayer.isChecked) DyPlayerCore.MPV else DyPlayerCore.EXO
            updateCoreDependentOptions()
        }

        binding.swShortVideoPlayer.isChecked = spUtil.shortDyPlayerCore == DyPlayerCore.MPV
        binding.swShortVideoPlayer.setOnClickListener {
            spUtil.shortDyPlayerCore = if (binding.swShortVideoPlayer.isChecked) DyPlayerCore.MPV else DyPlayerCore.EXO
        }

        binding.swVideoRender.isChecked = spUtil.surfaceRender
        binding.swVideoRender.setOnClickListener {
            spUtil.surfaceRender = binding.swVideoRender.isChecked
        }
        binding.swShortRandom.isChecked = spUtil.shortRandom
        binding.swShortRandom.setOnClickListener {
            spUtil.shortRandom = binding.swShortRandom.isChecked
        }
        binding.swShortRender.isChecked = spUtil.sortRender
        binding.swShortRender.setOnClickListener {
            spUtil.sortRender = binding.swShortRender.isChecked
        }

        // 短视频标题策略入口
        updateShortTitleStrategySummary()
        binding.shortTitleStrategy.setOnClickListener {
            val dialog = ShortTitleStrategyDialog()
            dialog.onValueListener { strategy, delimiter, regex, maxLines ->
                spUtil.shortTitleStrategy = strategy
                spUtil.shortTitleDelimiter = delimiter
                spUtil.shortTitleRegex = regex
                spUtil.shortTitleMaxLines = maxLines
                // 同步运行时缓存
                PlayerInitializer.Player.shortTitleStrategy = strategy
                PlayerInitializer.Player.shortTitleDelimiter = delimiter
                PlayerInitializer.Player.shortTitleRegex = regex
                PlayerInitializer.Player.shortTitleMaxLines = maxLines
                updateShortTitleStrategySummary()
            }
            dialog.arguments = ShortTitleStrategyDialog.buildArgs(
                spUtil.shortTitleStrategy,
                spUtil.shortTitleDelimiter!!,
                spUtil.shortTitleRegex!!,
                spUtil.shortTitleMaxLines
            )
            dialog.show(childFragmentManager, "short_title_strategy")
        }

        binding.swExoHttp.isChecked = spUtil.useOkhttp
        binding.swExoHttp.setOnClickListener {
            spUtil.useOkhttp = binding.swExoHttp.isChecked
        }
        updateCoreDependentOptions()
        binding.swAutoNext.isChecked = spUtil.autoNext
        binding.swAutoNext.setOnClickListener {
            spUtil.autoNext = binding.swAutoNext.isChecked
        }
        binding.swLoopList.isChecked = spUtil.loopList
        binding.swLoopList.setOnClickListener {
            spUtil.loopList = binding.swLoopList.isChecked
        }
        binding.swLongVideoPip.isChecked = spUtil.longVideoPip
        binding.swLongVideoPip.setOnClickListener {
            spUtil.longVideoPip = binding.swLongVideoPip.isChecked
        }
        binding.swLongVideoBgPlay.isChecked = spUtil.longVideoBackgroundPlay
        binding.swLongVideoBgPlay.setOnClickListener {
            val on = binding.swLongVideoBgPlay.isChecked
            spUtil.longVideoBackgroundPlay = on
            // 打开时请求通知权限(API 33+)
            if (on && android.os.Build.VERSION.SDK_INT >= 33) {
                val perm = android.Manifest.permission.POST_NOTIFICATIONS
                if (requireContext().checkSelfPermission(perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(perm), 1001)
                }
            }
        }

        // 音频渐入渐出设置
        binding.swAudioFade.isChecked = spUtil.audioFadeEnabled
        binding.swAudioFade.setOnClickListener {
            spUtil.audioFadeEnabled = binding.swAudioFade.isChecked
            updateFadeDependentOptions()
        }

        val fadeAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fadeDurationLabels)
        fadeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        binding.spinnerFadeInDuration.adapter = fadeAdapter
        binding.spinnerFadeInDuration.setSelection(fadeDurationOptions.indexOf(spUtil.audioFadeInDuration).coerceAtLeast(0))
        binding.spinnerFadeInDuration.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                spUtil.audioFadeInDuration = fadeDurationOptions[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.spinnerFadeOutDuration.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, fadeDurationLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerFadeOutDuration.setSelection(fadeDurationOptions.indexOf(spUtil.audioFadeOutDuration).coerceAtLeast(0))
        binding.spinnerFadeOutDuration.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                spUtil.audioFadeOutDuration = fadeDurationOptions[position]
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        updateFadeDependentOptions()
    }

    private fun updateShortTitleStrategySummary() {
        val strategy = ShortTitleStrategy.fromValue(spUtil.shortTitleStrategy)
        binding.tvShortTitleStrategyCurrent.text =
            if (strategy == ShortTitleStrategy.RAW) "原始显示" else strategy.displayName
    }

    private fun updateCoreDependentOptions() {
        // useOkhttp is consumed only by Exo. If AUTO is restored it still behaves as Exo today
        // because DyPlayerCoreRegistry.resolveCore(AUTO) falls back to Exo.
        val exoEnabled = spUtil.dyPlayerCore != DyPlayerCore.MPV
        binding.swExoHttp.isEnabled = exoEnabled
        binding.swExoHttp.alpha = if (exoEnabled) 1f else 0.5f
    }

    private fun updateFadeDependentOptions() {
        val enabled = spUtil.audioFadeEnabled
        binding.spinnerFadeInDuration.isEnabled = enabled
        binding.spinnerFadeOutDuration.isEnabled = enabled
        binding.audioFadeInDuration.alpha = if (enabled) 1f else 0.5f
        binding.audioFadeOutDuration.alpha = if (enabled) 1f else 0.5f
    }

    override fun resetView() {

    }

}
