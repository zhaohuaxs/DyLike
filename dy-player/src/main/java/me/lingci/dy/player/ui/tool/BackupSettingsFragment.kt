package me.lingci.dy.player.ui.tool

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.databinding.FragmentBackupSettingBinding
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.dailog.DialogHelper
import me.lingci.lib.base.json.JSONObject
import me.lingci.lib.base.ui.BaseFragment
import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.ui.file_select.FileSelectorActivity.Companion.selectorFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.createNew
import java.io.File

/**
 * 备份设置
 */
class BackupSettingsFragment : BaseFragment() {

    private var _binding: FragmentBackupSettingBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }
    private lateinit var activityLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBackupSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    private fun init() {
        initView()
        activityLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, data) ->
            if (resultCode == AppCompatActivity.RESULT_OK) {
                data?.selectorFile { list, isFolder ->
                    if (isFolder.not()) {
                        DialogHelper.createAction(
                            requireContext(),
                            "确认还原APP数据",
                            "会覆盖掉当前数据，再次确认"
                        ) {
                            appInput(list.first())
                        }.show()
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initView() {
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbar.title = "应用备份"

        binding.backupData.setOnClickListener {
            backupData()
        }
        binding.restoreData.setOnClickListener {
            restoreData()
        }
    }

    override fun resetView() {

    }

    private fun backupData() {
        val data = JSONObject()
        // app
        data.putValue("backupType", "dy_like")
        data.putValue("dataSchemaVersion", spUtil.dataSchemaVersion)
        data.putValue("isFirst", spUtil.isFirst)
        data.putValue("sourceJson", spUtil.sourceJson!!)
        data.putValue("mediaJson", spUtil.mediaJson!!)
        data.putValue("historyJson", spUtil.historyJson!!)
        data.putValue("likeJson", spUtil.likeJson!!)
        data.putValue("dayStr", spUtil.dayStr!!)
        data.putValue("videoDetailMode", spUtil.videoDetailMode)
        data.putValue("videoPlayerCore", spUtil.videoPlayerCore)
        data.putValue("shortVideoPlayerCore", spUtil.shortVideoPlayerCore)
        // Export the legacy boolean only for older tools. It cannot represent AUTO and treats every
        // non-Exo core as false, so restore logic must prefer videoPlayerCore when present. Remove
        // this write after the old backup compatibility window is closed.
        data.putValue("videoPlayerExo", spUtil.dyPlayerCore == DyPlayerCore.EXO)
        data.putValue("longVideoMode", spUtil.longVideoMode)
        data.putValue("shortRandom", spUtil.shortRandom)
        data.putValue("dmGradientMode", spUtil.dmGradientMode)
        data.putValue("dmGradientRatio", spUtil.dmGradientRatio)
        data.putValue("dmGradientWithTextColor", spUtil.dmGradientWithTextColor)
        data.putValue("dmStrokeMultipleMode", spUtil.dmStrokeMultipleMode)
        data.putValue("dmStrokeMultiple", spUtil.dmStrokeMultiple)
        data.putValue("dmFontMode", spUtil.dmFontMode)
        data.putValue("dmCurrentFont", spUtil.dmCurrentFont!!)
        data.putValue("firstScanMovie", spUtil.firstScanMovie)
        data.putValue("surfaceRender", spUtil.surfaceRender)
        data.putValue("dmMergeMode", spUtil.dmMergeMode)
        data.putValue("dmShowTime", spUtil.dmShowTime)
        data.putValue("dmMergeTop", spUtil.dmMergeTop)
        data.putValue("dmMergeShow", spUtil.dmMergeShow)
        data.putValue("browserUsedAll", spUtil.browserUsedAll)
        data.putValue("browserSort", spUtil.browserSort)
        data.putValue("browserShowHide", spUtil.browserShowHide)
        data.putValue("newHome", spUtil.newHome)
        data.putValue("sortRender", spUtil.sortRender)
        data.putValue("useOkhttp", spUtil.useOkhttp)
        data.putValue("autoNext", spUtil.autoNext)
        data.putValue("loopList", spUtil.loopList)
        data.putValue("showShortTitle", spUtil.showShortTitle)
        data.putValue("showShortLike", spUtil.showShortLike)
        data.putValue("showShortComment", spUtil.showShortComment)
        data.putValue("showShortPager", spUtil.showShortPager)
        data.putValue("shortPlayNext", spUtil.shortPlayNext)
        data.putValue("shortLifeSpeed", spUtil.shortLifeSpeed)
        data.putValue("shortRightSpeed", spUtil.shortRightSpeed)
        data.putValue("shortTitleStrategy", spUtil.shortTitleStrategy)
        data.putValue("shortTitleDelimiter", spUtil.shortTitleDelimiter!!)
        data.putValue("shortTitleRegex", spUtil.shortTitleRegex!!)
        data.putValue("shortTitleMaxLines", spUtil.shortTitleMaxLines)
        // base
        data.putValue("debugMode", spUtil.debugMode)
        data.putValue("showDm", spUtil.showDm)
        data.putValue("dmBold", spUtil.dmBold)
        data.putValue("dmConf", spUtil.dmConf!!)
        data.putValue("seSsData", spUtil.seSsData!!)
        data.putValue("lastFolder", spUtil.lastFolder!!)
        data.putValue("useFolders", spUtil.useFolders!!)
        data.putValue("passStroke", spUtil.passStroke)
        data.putValue("downFolder", spUtil.downFolder!!)
        data.putValue("customColor", spUtil.customColor!!)
        data.putValue("customGradient", spUtil.customGradient!!)
        data.putValue("customColorScheme", spUtil.customColorScheme!!)
        data.putValue("paletteOptions", spUtil.paletteOptions!!)
        data.putValue("downPalette", spUtil.downPalette)
        data.putValue("showDmFps", spUtil.showDmFps)
        data.putValue("iconDefault", spUtil.iconDefault)
        data.putValue("labSurfaceRgba", spUtil.labSurfaceRgba)
        data.putValue("labSurfaceZOrder", spUtil.labSurfaceZOrder)
        data.putValue("labMpvSpecialRender", spUtil.labMpvSpecialRender)
        data.putValue("labMpvSuperResolution", spUtil.labMpvSuperResolution)
        // list
        val basePath = requireContext().getExternalFilesDir("info")
        basePath?.list()?.let { list ->
            val playInfoList = JSONObject()
            for (path in list) {
                File(basePath, path).let { file ->
                    if (file.exists()) {
                        Log.d(this, "info path", file.path)
                        playInfoList.putValue(file.name, file.readText())
                    }
                }
            }
            data.putValue("playInfoList", playInfoList)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val filename = "dy_backup_${AppUtil.formatNow()}.json"
            FileOperator.writeText(FileOperator.buildDownFile("DyLike", filename), data.toJsonStr())
            withContext(Dispatchers.Main) {
                ToastUtil.showToast(requireContext(), "备份完成，/Download/DyLike/${filename}")
            }
        }
    }

    private fun restoreData() {
        DialogHelper.createAction(requireContext(), "还原APP数据", "会覆盖掉当前数据，谨慎使用") {
            FileSelectorActivity.startSingle(
                requireActivity(),
                FileOperator.JSON_EXTENSIONS,
                activityLauncher
            )
        }.show()
    }

    private fun appInput(filePath: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val dataJson = FileOperator.readText(filePath)
            val data = JSONObject(dataJson)
            withContext(Dispatchers.Main) {
                if (data.getString("backupType") != "dy_like") {
                    ToastUtil.showToast(requireContext(), "导入文件非DyLike备份")
                    return@withContext
                }
            }
            if (data.hasKey("playInfoList")) {
                val playInfo = data.getJSONObject("playInfoList")
                requireContext().getExternalFilesDir("info")?.let { infoFile ->
                    for (name in playInfo.keys()) {
                        File(infoFile, name).apply {
                            if (exists().not()) {
                                createNew()
                            }
                            writeText(playInfo.getString(name))
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                // base
                if (data.hasKey("debugMode")) {
                    spUtil.debugMode = data.getBoolean("debugMode")
                }
                if (data.hasKey("showDm")) {
                    spUtil.showDm = data.getBoolean("showDm")
                }
                if (data.hasKey("dmBold")) {
                    spUtil.dmBold = data.getBoolean("dmBold")
                }
                if (data.hasKey("dmConf")) {
                    spUtil.dmConf = data.getString("dmConf")
                }
                if (data.hasKey("seSsData")) {
                    spUtil.seSsData = data.getString("seSsData")
                }
                if (data.hasKey("lastFolder")) {
                    spUtil.lastFolder = data.getString("lastFolder")
                }
                if (data.hasKey("useFolders")) {
                    spUtil.useFolders = data.getString("useFolders")
                }
                if (data.hasKey("passStroke")) {
                    spUtil.passStroke = data.getBoolean("passStroke")
                }
                if (data.hasKey("downFolder")) {
                    spUtil.downFolder = data.getString("downFolder")
                }
                if (data.hasKey("customColor")) {
                    spUtil.customColor = data.getString("customColor")
                }
                if (data.hasKey("customGradient")) {
                    spUtil.customGradient = data.getString("customGradient")
                }
                if (data.hasKey("customColorScheme")) {
                    spUtil.customColorScheme = data.getString("customColorScheme")
                }
                if (data.hasKey("paletteOptions")) {
                    spUtil.paletteOptions = data.getString("paletteOptions")
                }
                if (data.hasKey("downPalette")) {
                    spUtil.downPalette = data.getBoolean("downPalette")
                }
                if (data.hasKey("showDmFps")) {
                    spUtil.showDmFps = data.getBoolean("showDmFps")
                }
                if (data.hasKey("iconDefault")) {
                    spUtil.iconDefault = data.getBoolean("iconDefault")
                }
                if (data.hasKey("labSurfaceRgba")) {
                    spUtil.labSurfaceRgba = data.getBoolean("labSurfaceRgba")
                }
                if (data.hasKey("labSurfaceZOrder")) {
                    spUtil.labSurfaceZOrder = data.getBoolean("labSurfaceZOrder")
                }
                if (data.hasKey("labMpvSpecialRender")) {
                    spUtil.labMpvSpecialRender = data.getBoolean("labMpvSpecialRender")
                }
                if (data.hasKey("labMpvSuperResolution")) {
                    spUtil.labMpvSuperResolution = data.getBoolean("labMpvSuperResolution")
                }
                // app
                if (data.hasKey("isFirst")) {
                    spUtil.isFirst = data.getBoolean("isFirst")
                }
                if (data.hasKey("dataSchemaVersion")) {
                    spUtil.dataSchemaVersion = data.getInt("dataSchemaVersion")
                } else {
                    spUtil.dataSchemaVersion = 0
                }
                if (data.hasKey("sourceJson")) {
                    spUtil.sourceJson = data.getString("sourceJson")
                }
                if (data.hasKey("mediaJson")) {
                    spUtil.mediaJson = data.getString("mediaJson")
                }
                if (data.hasKey("historyJson")) {
                    spUtil.historyJson = data.getString("historyJson")
                }
                if (data.hasKey("likeJson")) {
                    spUtil.likeJson = data.getString("likeJson")
                }
                if (data.hasKey("dayStr")) {
                    spUtil.dayStr = data.getString("dayStr")
                }
                if (data.hasKey("videoDetailMode")) {
                    spUtil.videoDetailMode = data.getBoolean("videoDetailMode")
                }
                if (data.hasKey("videoPlayerCore")) {
                    // Prefer the enum/int backup because it preserves MPV/AUTO. SpUtil normalizes
                    // invalid values from edited or future backups back to a supported core.
                    spUtil.videoPlayerCore = data.getInt("videoPlayerCore")
                } else if (data.hasKey("videoPlayerExo")) {
                    // Old backups only know Exo/non-Exo. Non-Exo now maps to MPV because IJK was removed.
                    spUtil.videoPlayerCore = DyPlayerCore.fromLegacyExo(data.getBoolean("videoPlayerExo")).value
                }
                if (data.hasKey("shortVideoPlayerCore")) {
                    spUtil.shortVideoPlayerCore = data.getInt("shortVideoPlayerCore")
                }
                if (data.hasKey("longVideoMode")) {
                    spUtil.longVideoMode = data.getBoolean("longVideoMode")
                }
                if (data.hasKey("shortRandom")) {
                    spUtil.shortRandom = data.getBoolean("shortRandom")
                }
                if (data.hasKey("dmGradientMode")) {
                    spUtil.dmGradientMode = data.getBoolean("dmGradientMode")
                }
                if (data.hasKey("dmGradientRatio")) {
                    spUtil.dmGradientRatio = data.getInt("dmGradientRatio")
                }
                if (data.hasKey("dmGradientWithTextColor")) {
                    spUtil.dmGradientWithTextColor = data.getBoolean("dmGradientWithTextColor")
                }
                if (data.hasKey("dmStrokeMultipleMode")) {
                    spUtil.dmStrokeMultipleMode = data.getBoolean("dmStrokeMultipleMode")
                }
                if (data.hasKey("dmStrokeMultiple")) {
                    spUtil.dmStrokeMultiple = data.getDouble("dmStrokeMultiple").toFloat()
                }
                if (data.hasKey("dmFontMode")) {
                    spUtil.dmFontMode = data.getBoolean("dmFontMode")
                }
                if (data.hasKey("dmCurrentFont")) {
                    spUtil.dmCurrentFont = data.getString("dmCurrentFont")
                }
                if (data.hasKey("firstScanMovie")) {
                    spUtil.firstScanMovie = data.getBoolean("firstScanMovie")
                }
                if (data.hasKey("surfaceRender")) {
                    spUtil.surfaceRender = data.getBoolean("surfaceRender")
                }
                if (data.hasKey("dmMergeMode")) {
                    spUtil.dmMergeMode = data.getBoolean("dmMergeMode")
                }
                if (data.hasKey("dmShowTime")) {
                    spUtil.dmShowTime = data.getBoolean("dmShowTime")
                }
                if (data.hasKey("dmMergeTop")) {
                    spUtil.dmMergeTop = data.getInt("dmMergeTop")
                }
                if (data.hasKey("dmMergeShow")) {
                    spUtil.dmMergeShow = data.getInt("dmMergeShow")
                }
                if (data.hasKey("browserUsedAll")) {
                    spUtil.browserUsedAll = data.getBoolean("browserUsedAll")
                }
                if (data.hasKey("browserSort")) {
                    spUtil.browserSort = data.getInt("browserSort")
                }
                if (data.hasKey("browserShowHide")) {
                    spUtil.browserShowHide = data.getBoolean("browserShowHide")
                }
                if (data.hasKey("newHome")) {
                    spUtil.newHome = data.getBoolean("newHome")
                }
                if (data.hasKey("sortRender")) {
                    spUtil.sortRender = data.getBoolean("sortRender")
                }
                if (data.hasKey("useOkhttp")) {
                    spUtil.useOkhttp = data.getBoolean("useOkhttp")
                }
                if (data.hasKey("autoNext")) {
                    spUtil.autoNext = data.getBoolean("autoNext")
                }
                if (data.hasKey("loopList")) {
                    spUtil.loopList = data.getBoolean("loopList")
                }
                if (data.hasKey("showShortTitle")) {
                    spUtil.showShortTitle = data.getBoolean("showShortTitle")
                }
                if (data.hasKey("showShortLike")) {
                    spUtil.showShortLike = data.getBoolean("showShortLike")
                }
                if (data.hasKey("showShortComment")) {
                    spUtil.showShortComment = data.getBoolean("showShortComment")
                }
                if (data.hasKey("showShortPager")) {
                    spUtil.showShortPager = data.getBoolean("showShortPager")
                }
                if (data.hasKey("shortPlayNext")) {
                    spUtil.shortPlayNext = data.getBoolean("shortPlayNext")
                }
                if (data.hasKey("shortLifeSpeed")) {
                    spUtil.shortLifeSpeed = data.getFloat("shortLifeSpeed")
                }
                if (data.hasKey("shortRightSpeed")) {
                    spUtil.shortRightSpeed = data.getFloat("shortRightSpeed")
                }
                if (data.hasKey("shortTitleStrategy")) {
                    spUtil.shortTitleStrategy = data.getInt("shortTitleStrategy")
                }
                if (data.hasKey("shortTitleDelimiter")) {
                    spUtil.shortTitleDelimiter = data.getString("shortTitleDelimiter")
                }
                if (data.hasKey("shortTitleRegex")) {
                    spUtil.shortTitleRegex = data.getString("shortTitleRegex")
                }
                if (data.hasKey("shortTitleMaxLines")) {
                    spUtil.shortTitleMaxLines = data.getInt("shortTitleMaxLines")
                }
                LibraryCompat.migrateIfNeeded(spUtil)
                DialogHelper.createMsg(requireContext(), "还原APP数据", "导入完成", "确认").show()
            }
        }
    }

}
