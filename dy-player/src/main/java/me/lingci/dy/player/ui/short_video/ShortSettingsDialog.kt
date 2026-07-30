package me.lingci.dy.player.ui.short_video

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import me.lingci.dy.player.databinding.DialogShortSettingsBinding
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.danmaku.PlayerInitializer

/**
 * @author : happyc
 * time    : 2026/01/31
 * desc    :
 * version : 1.0
 */
open class ShortSettingsDialog() : BottomSheetDialogFragment(), View.OnClickListener {

    private lateinit var binding: DialogShortSettingsBinding
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }
    private var onChange: (() -> Unit)? = null
    private var onTimerClose: (() -> Unit)? = null

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
        binding = DialogShortSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        Log.d(this@ShortSettingsDialog, "onViewStateRestored")
        super.onViewStateRestored(savedInstanceState)
    }

    override fun onDestroyView() {
        Log.d(this@ShortSettingsDialog, "onDestroyView")
        super.onDestroyView()
    }

    fun onChange(onChange: () -> Unit) {
        this.onChange = onChange
    }

    fun onTimerClose(onTimerClose: () -> Unit) {
        this.onTimerClose = onTimerClose
    }

    fun updateTimerCloseStatus(statusText: String) {
        if (::binding.isInitialized) {
            binding.tvTimerCloseStatus.text = statusText
        }
    }

    private fun init() {
        binding.swShowTitle.isChecked = PlayerInitializer.Player.shortShowTitle
        binding.swShowTitle.setOnCheckedChangeListener { _, checked ->
            PlayerInitializer.Player.shortShowTitle = checked
            onChange?.invoke()
            spUtil.showShortTitle = checked
        }
        binding.swShowLike.isChecked = PlayerInitializer.Player.shortShowLike
        binding.swShowLike.setOnCheckedChangeListener { _, checked ->
            PlayerInitializer.Player.shortShowLike = checked
            onChange?.invoke()
            spUtil.showShortLike = checked
        }
        binding.swShowComment.isChecked = PlayerInitializer.Player.shortShowComment
        binding.swShowComment.setOnCheckedChangeListener { _, checked ->
            PlayerInitializer.Player.shortShowComment = checked
            onChange?.invoke()
            spUtil.showShortComment = checked
        }
        binding.swShowSeekbar.isChecked = PlayerInitializer.Player.shortShowSeekbar
        binding.swShowSeekbar.setOnCheckedChangeListener { _, checked ->
            PlayerInitializer.Player.shortShowSeekbar = checked
            onChange?.invoke()
            spUtil.showShortSeekbar = checked
        }
        binding.swShowPager.isChecked = PlayerInitializer.Player.shortShowPager
        binding.swShowPager.setOnCheckedChangeListener { _, checked ->
            PlayerInitializer.Player.shortShowPager = checked
            onChange?.invoke()
            spUtil.showShortPager = checked
        }
        binding.swAutoNext.isChecked = PlayerInitializer.Player.shortAutoNext
        binding.swAutoNext.setOnCheckedChangeListener {  _, checked ->
            PlayerInitializer.Player.shortAutoNext = checked
            onChange?.invoke()
            spUtil.shortPlayNext = checked
        }
        binding.swShowMore.isChecked = PlayerInitializer.Player.shortShowMore
        binding.swShowMore.setOnCheckedChangeListener { _, checked ->
            PlayerInitializer.Player.shortShowMore = checked
            onChange?.invoke()
            spUtil.showShortMore = checked
        }
        binding.swHideSysBar.isChecked = PlayerInitializer.Player.shortShowSysBar.not()
        binding.swHideSysBar.setOnCheckedChangeListener {  _, checked ->
            PlayerInitializer.Player.shortShowSysBar = checked.not()
            spUtil.showSysBar = checked.not()
            onChange?.invoke()
        }

        changeSelectLeft(true)
        changeSelectRight(true)

        binding.tvLeft05.setOnClickListener(this)
        binding.tvLeft1.setOnClickListener(this)
        binding.tvLeft15.setOnClickListener(this)
        binding.tvLeft2.setOnClickListener(this)
        binding.tvLeft25.setOnClickListener(this)
        binding.tvLeft3.setOnClickListener(this)
        binding.tvRight05.setOnClickListener(this)
        binding.tvRight1.setOnClickListener(this)
        binding.tvRight15.setOnClickListener(this)
        binding.tvRight2.setOnClickListener(this)
        binding.tvRight25.setOnClickListener(this)
        binding.tvRight3.setOnClickListener(this)

        binding.tvTimerClose.setOnClickListener {
            onTimerClose?.invoke()
            dismiss()
        }
    }

    override fun onClick(v: View?) {
        v?.let {
            v.isSelected = true
            val left = getLeft(v)
            if (left > 0) {
                changeSelectLeft(false)
                PlayerInitializer.Player.shortLeftSpeed = left
            }
            val right = getRight(v)
            if (right > 0) {
                changeSelectRight(false)
                PlayerInitializer.Player.shortRightSpeed = right
            }
            onChange?.invoke()
        }
    }

    private fun changeSelectLeft(select: Boolean) {
        when (PlayerInitializer.Player.shortLeftSpeed) {
            0.5f -> binding.tvLeft05.isSelected = select
            1f -> binding.tvLeft1.isSelected = select
            1.5f -> binding.tvLeft15.isSelected = select
            2f -> binding.tvLeft2.isSelected = select
            2.5f -> binding.tvLeft25.isSelected = select
            3f -> binding.tvLeft3.isSelected = select
            else -> binding.tvLeft05.isSelected = select
        }
    }

    private fun changeSelectRight(select: Boolean) {
        when (PlayerInitializer.Player.shortRightSpeed) {
            0.5f -> binding.tvRight05.isSelected = select
            1f -> binding.tvRight1.isSelected = select
            1.5f -> binding.tvRight15.isSelected = select
            2f -> binding.tvRight2.isSelected = select
            2.5f -> binding.tvRight25.isSelected = select
            3f -> binding.tvRight3.isSelected = select
            else -> binding.tvRight2.isSelected = select
        }
    }

    private fun getLeft(v: View): Float {
        return when (v.id) {
            binding.tvLeft05.id -> 0.5f
            binding.tvLeft1.id -> 1f
            binding.tvLeft15.id -> 1.5f
            binding.tvLeft2.id -> 2f
            binding.tvLeft25.id -> 2.5f
            binding.tvLeft3.id -> 3f
            else -> -1f
        }
    }

    private fun getRight(v: View): Float {
        return when (v.id) {
            binding.tvRight05.id -> 0.5f
            binding.tvRight1.id -> 1f
            binding.tvRight15.id -> 1.5f
            binding.tvRight2.id -> 2f
            binding.tvRight25.id -> 2.5f
            binding.tvRight3.id -> 3f
            else -> -1f
        }
    }

}
