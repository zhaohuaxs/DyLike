package me.lingci.dy.player.ui.long_video

import android.content.Context
import android.graphics.Typeface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.view.LongVideoControlView
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.DensityCalculator
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.dm.view.common.DmInitializer
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.entity.DmTrackMode
import me.lingci.lib.dm.view.entity.xml.DmItem
import me.lingci.lib.dm.view.util.XmlConverter
import me.lingci.lib.dm.view.util.XmlMerger
import me.lingci.lib.dm.view.util.ZipXmlLoader
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.widget.component.DanmakuControlView
import me.lingci.lib.player.widget.component.DmConfControlView
import me.lingci.lib.player.widget.component.DmListControlView
import me.lingci.lib.player.widget.component.DmSelectControlView
import me.lingci.lib.player.widget.component.DmTrackControlView
import me.lingci.lib.player.widget.videoview.CustomVideoView
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 长视频弹幕控制器。
 *
 * 负责弹幕视图初始化、轨道选择/恢复、XML/ZIP 加载、弹幕合并、列表曲线刷新和播放信息缓存。
 * Activity 只保留播放页编排和当前视频数据来源，避免弹幕文件格式与 UI 回调继续堆在页面类里。
 */
class LongDanmakuController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val spUtil: SpUtil,
    private val danmakuView: DanmakuControlView,
    private val dmSelectView: DmSelectControlView,
    private val dmConfView: DmConfControlView,
    private val dmTrackView: DmTrackControlView,
    private val dmListView: DmListControlView,
    private val longVideoControlView: LongVideoControlView,
    private val videoView: CustomVideoView,
    private val currentVideoData: () -> VideoData?,
    private val currentVideoName: () -> String,
    private val isMerge: () -> Boolean,
    private val setMergeState: (Boolean) -> Unit,
    // 播放历史来源上下文（play_history 进度同步用，由 Activity 按进入来源派生）
    private val historySource: () -> HistorySource
) {

    /** 应用进入播放页时的全局弹幕样式、性能和过滤配置。 */
    fun applyInitialSettings() {
        setDmFont()
        danmakuView.toggleVis(spUtil.showDm)
        danmakuView.showFps(spUtil.showDmFps)
        danmakuView.setStrokeMultiple(spUtil.dmStrokeMultiple)
        danmakuView.updateTextBold(spUtil.dmBold)
        danmakuView.setDmGradient(spUtil.dmGradientMode, spUtil.dmGradientRatio)
        danmakuView.setGradientWithTextColor(spUtil.dmGradientWithTextColor)
        danmakuView.setMergeOption(
            spUtil.dmShowTime,
            spUtil.dmMergeMode,
            spUtil.dmMergeShow,
            spUtil.dmMergeTop,
            spUtil.debugMode
        )
        if (spUtil.dmFilterMode) {
            spUtil.dmFilter?.let { filterStr ->
                if (filterStr.isNotBlank()) {
                    val keywords = filterStr.split(DmInitializer.FILTER_SEPARATOR).filter { it.isNotBlank() }
                    if (keywords.isNotEmpty()) {
                        danmakuView.addBlackList(false, *keywords.toTypedArray())
                    }
                }
            }
        }
    }

    /** 绑定弹幕设置、选择、轨道、合并和列表面板回调。 */
    fun bind() {
        dmConfView.setOnValueChangeListener { type, value ->
            when (type) {
                DmConfControlView.TYPE_SIZE -> danmakuView.updateDanmuSize()
                DmConfControlView.TYPE_LINE,
                DmConfControlView.TYPE_TOP_LINE,
                DmConfControlView.TYPE_BOTTOM_LINE -> danmakuView.updateMaxLine()

                DmConfControlView.TYPE_OPACITY -> danmakuView.updateDanmuAlpha()
                DmConfControlView.TYPE_STROKE -> danmakuView.updateDanmuStroke()
                DmConfControlView.TYPE_OFFSET -> danmakuView.updateOffsetTime()
                DmConfControlView.TYPE_ROLL -> danmakuView.updateMobileDanmuState()
                DmConfControlView.TYPE_TOP -> danmakuView.updateTopDanmuState()
                DmConfControlView.TYPE_BOTTOM -> danmakuView.updateBottomDanmuState()
                DmConfControlView.TYPE_STYLE -> danmakuView.updateTextBold(spUtil.dmBold)
                DmConfControlView.TYPE_MARGIN -> danmakuView.updateDanmuMargin()
                DmConfControlView.TYPE_VIEW_MARGIN -> danmakuView.updateDanMuViewMargin()
                DmConfControlView.TYPE_SCROLL_SPEED -> danmakuView.updateScrollSpeed()
                DmConfControlView.TYPE_FPS -> danmakuView.showFps(value as Boolean)
                else -> {}
            }
        }
        danmakuView.updateScrollSpeed()
        dmSelectView.setOnDmSelectListener { dmTrackConf ->
            dmTrackView.setConf(dmTrackConf)
            when (dmTrackConf.trackMode) {
                DmTrackMode.MULTI_MERGE -> {
                    dmTrackConf.dmTrack.let { track ->
                        track.checked = true
                        dmTrackView.addDmTrack(track)
                        if (dmTrackView.dataSize() > 1) {
                            merge(false)
                        } else {
                            danmakuView.release()
                            loadTrack(track)
                        }
                    }
                }

                else -> {
                    dmTrackConf.dmTrack.let { selectedTrack ->
                        dmTrackView.addDmTrack(selectedTrack)
                        val currentTrack = dmTrackView.selectTrack(selectedTrack) ?: selectedTrack.apply {
                            selected = true
                        }
                        loadTrack(currentTrack)
                    }
                }
            }
            saveCacheInfo(videoView.currentPosition, false, dmTrackConf.dmTrack)
        }
        dmTrackView.setOnChangeTrackListener { track, _ ->
            danmakuView.release()
            loadTrack(track)
            saveCacheInfo(videoView.currentPosition, false, track)
        }
        dmTrackView.setOnMergeTrackListener { onSave ->
            merge(onSave)
        }
        dmTrackView.setOnDmOffsetListener { _ ->
            danmakuView.syncTime()
        }
        dmTrackView.setOnRemoveTrackListener {
            saveCacheInfo(videoView.currentPosition, false)
        }
        dmListView.setOnChangeTimeListener { _, _ -> }
    }

    /** 清空弹幕列表面板和进度条密度曲线。 */
    fun clearPanels() {
        dmListView.cleanDmList()
        longVideoControlView.updateCurveData(floatArrayOf())
    }

    /** 新视频开始播放前重置弹幕视图、轨道数据和选择面板状态。 */
    fun resetForNewPlayback() {
        release()
        dmTrackView.cleanData()
        dmSelectView.resetView()
        // 重置偏移 UI 与全局 offsetPosition，避免上一集偏移残留
        dmConfView.resetOffset()
    }

    /** 释放弹幕渲染资源。 */
    fun release() {
        danmakuView.release()
    }

    /** 将弹幕时间同步到播放器当前进度。 */
    fun syncTime() {
        danmakuView.syncTime()
    }

    /** 播放倍速变化后同步弹幕滚动速度。 */
    fun updateSpeed() {
        danmakuView.updateDanmuSpeed()
    }

    /** 切换弹幕轨道面板显示状态。 */
    fun switchTrackPanel() {
        dmTrackView.switchVib()
    }

    /** 切换弹幕列表面板，并滚动到当前播放进度附近。 */
    fun switchListPanel(currentPosition: Long) {
        dmListView.switchVib()
        dmListView.scrollToPosition(currentPosition)
    }

    /** 应用用户选择的弹幕字体；空路径表示恢复默认字体。 */
    fun onFontChange(fontPath: String) {
        if (fontPath.isNotBlank()) {
            scope.launch(Dispatchers.IO) {
                danmakuView.setTypeface(Typeface.createFromFile(fontPath))
            }
        } else {
            danmakuView.clearTypeface()
        }
    }

    /** 添加一个本地 XML 弹幕文件并立即加载。 */
    fun addDmFile(file: File) {
        if (file.exists()) {
            dmTrackView.addDmTrack(
                DmTrack(
                    title = file.name,
                    path = file.path
                )
            )
            danmakuView.release()
            danmakuView.loadDanMu(file.path)
            // 新文件 offset=0，重置偏移 UI，避免显示上一轨道的偏移
            dmConfView.resetOffset()
            setDmList(file.path)
        }
    }

    /** 按保存的播放信息恢复上次选中的弹幕轨道，必要时回退到已选/首个轨道。 */
    fun restoreSavedTrack(info: PlayInfo): Boolean {
        info.lastDmTrack?.let { lastTrack ->
            dmTrackView.findTrack(lastTrack)?.let { track ->
                dmTrackView.selectTrack(track)
                loadTrack(track)
                return true
            }
        }
        dmTrackView.listSelectedTrack().let { selectedTracks ->
            if (selectedTracks.size > 1) {
                setMergeState(false)
                merge(false)
                return true
            }
            if (selectedTracks.size == 1) {
                loadTrack(selectedTracks.first())
                return true
            }
        }
        dmTrackView.allTrack().firstOrNull()?.let { track ->
            loadTrack(track)
            return true
        }
        return false
    }

    /**
     * 恢复历史播放信息里的弹幕轨道列表。
     * 旧数据惰性迁移：压缩包轨道缺少 archiveEntryName 时，用旧 title（旧版本 title 即条目名）回填并写回；
     * title 等于包文件名表示历史“整包绑定”，无法推断条目，不做猜测，加载时会提示用户重选。
     */
    fun addTracks(info: PlayInfo) {
        val migratedTracks = info.dmTrack.map { migrateTrack(it) }
        val needPersist = migratedTracks.zip(info.dmTrack)
            .any { (newTrack, oldTrack) -> newTrack.archiveEntryName != oldTrack.archiveEntryName }
        var missingCount = 0
        for (track in migratedTracks) {
            if (File(track.path).exists()) {
                dmTrackView.addDmTrack(track)
            } else {
                missingCount++
            }
        }
        if (missingCount > 0) {
            // 不再静默丢弃：明确告诉用户哪些轨道因文件不可用被跳过
            longVideoControlView.showTips("有 $missingCount 个弹幕文件不存在，已跳过")
        }
        if (needPersist) {
            val videoData = currentVideoData() ?: return
            scope.launch(Dispatchers.IO) {
                PlayHelper.saveInfoSync(
                    context,
                    videoData,
                    info.copy(dmTrack = migratedTracks.toMutableList())
                )
            }
        }
    }

    /** 旧轨道惰性迁移：只补写可安全推断的 archiveEntryName，不做任何名称猜测 */
    private fun migrateTrack(track: DmTrack): DmTrack {
        if (!track.isArchiveTrack() || track.archiveEntryName.isNotBlank()) return track
        if (track.isUnresolvedLegacyArchive()) return track
        return track.copy(archiveEntryName = track.title.trim())
    }

    /** 当前是否存在可加载的弹幕轨道。 */
    fun hasTracks(): Boolean {
        return dmTrackView.allTrack().isNotEmpty()
    }

    /** 保存当前播放进度、弹幕轨道列表和最后使用的轨道到播放信息文件。 */
    fun saveCacheInfo(process: Long, update: Boolean = true, track: DmTrack? = null) {
        val videoData = currentVideoData() ?: return
        val source = historySource()
        // 总时长用于 play_history 的"已看完"判断；无效时传 0（保留 DAO 中原值）
        val duration = videoView.duration.takeIf { it > 0 } ?: 0L
        if (update) {
            PlayHelper.updateInfo(
                context,
                scope,
                videoData,
                process,
                dmTrackView.allTrack(),
                track ?: dmTrackView.allTrack().lastOrNull(),
                source,
                duration
            )
        } else {
            PlayHelper.saveInfo(
                context,
                scope,
                videoData,
                process,
                dmTrackView.allTrack(),
                track ?: dmTrackView.allTrack().lastOrNull(),
                source
            )
        }
    }

    /** 把动作投递到主线程；用 videoView.post 取代 runOnUiThread，避免每个回调起一个协程。 */
    private fun postOnMain(action: () -> Unit) {
        videoView.post(action)
    }

    /** 根据轨道类型加载 XML 或 ZIP 内的弹幕内容。 */
    private fun loadTrack(track: DmTrack) {
        danmakuView.release()
        if (track.isArchiveTrack()) {
            loadZipTrack(track)
        } else {
            danmakuView.loadDanMu(track.path)
            // loadDanMu 会重置 offsetPosition=0，此处重新应用轨道持久化偏移
            applyTrackOffset(track)
            setDmList(track.path)
        }
    }

    /**
     * 加载轨道后，将该轨道的持久化偏移应用到全局 offsetPosition 与 DmConfControlView UI。
     * loadDanMu 内部会把 offsetPosition 重置为 0，此处需要重新设置，确保切换轨道时
     * 保留各轨道独立的偏移配置。
     */
    private fun applyTrackOffset(track: DmTrack) {
        PlayerInitializer.Danmu.offsetPosition = track.offset
        dmConfView.applyOffset(track.offset)
    }

    /** 异步读取压缩包内的 XML 弹幕，并把错误状态转换为用户可见提示。 */
    private fun loadZipTrack(track: DmTrack) {
        val entryName = track.resolvedArchiveEntryName()
        if (entryName.isBlank()) {
            // 旧数据只有包名（整包绑定），无法知道该打开哪个条目：不猜测，提示用户重选
            postOnMain {
                clearPanels()
                longVideoControlView.showTips("该压缩包弹幕缺少包内条目信息，请重新选择")
            }
            return
        }
        scope.launch(Dispatchers.IO) {
            val fileEntity = FileEntity(
                name = entryName,
                title = track.title,
                path = track.path,
                mimeType = track.normalizedMineType()
            )
            var usedPassword = track.password
            var result = openZip(fileEntity, usedPassword, track)
            var retriedWithCached = false
            if (result.state == ZipXmlLoader.OpenResultState.WRONG_PASSWORD) {
                // 播放侧优先使用该包在 SP 中的最近一次有效密码，不同才重试一次
                val cachedPassword = dmSelectView.archivePassword(track.path)
                if (cachedPassword.isNotBlank() && cachedPassword != usedPassword) {
                    usedPassword = cachedPassword
                    retriedWithCached = true
                    result = openZip(fileEntity, usedPassword, track)
                }
            }
            if (result.state == ZipXmlLoader.OpenResultState.SUCCESS && retriedWithCached) {
                // 重试成功：回写轨道与 SP，后续切换轨道不再重复试错
                track.password = usedPassword
                dmSelectView.updateArchivePassword(track.path, usedPassword)
            }
            when (result.state) {
                ZipXmlLoader.OpenResultState.SUCCESS -> {
                    val byteArray = result.stream?.use { it.readBytes() }
                    val dmList = byteArray?.let {
                        XmlConverter.fromXmlInput(ByteArrayInputStream(it)).list
                    }.orEmpty()
                    withContext(Dispatchers.Main) {
                        if (byteArray == null) {
                            clearPanels()
                            longVideoControlView.showTips("弹幕读取失败")
                            return@withContext
                        }
                        danmakuView.loadDanMu(ByteArrayInputStream(byteArray))
                        // loadDanMu 会重置 offsetPosition=0，此处重新应用轨道持久化偏移
                        applyTrackOffset(track)
                        applyList(dmList)
                        if (retriedWithCached) {
                            // 持久化重试成功后的密码，避免下次恢复再试错
                            saveCacheInfo(videoView.currentPosition, false, track)
                        }
                    }
                }

                ZipXmlLoader.OpenResultState.WRONG_PASSWORD -> {
                    // 失败后清掉失效缓存，引导用户重新输入
                    dmSelectView.clearArchivePassword(track.path)
                    withContext(Dispatchers.Main) {
                        clearPanels()
                        longVideoControlView.showTips(
                            // 只有真正用缓存密码重试过才提示「已使用…」；缓存与轨道密码相同未重试时不出现该文案
                            if (retriedWithCached) {
                                "弹幕密码错误，已使用该压缩包最近一次有效密码，请在弹幕选择面板重新输入"
                            } else {
                                "弹幕密码错误，请在弹幕选择面板重新输入"
                            }
                        )
                        // “重新选择”：直接打开弹幕选择面板并预填该压缩包路径
                        dmSelectView.selectArchive(track.path)
                    }
                }

                ZipXmlLoader.OpenResultState.UNSUPPORTED_METHOD -> {
                    withContext(Dispatchers.Main) {
                        clearPanels()
                        longVideoControlView.showTips("压缩包方法不支持")
                    }
                }

                ZipXmlLoader.OpenResultState.ENTRY_NOT_FOUND -> {
                    withContext(Dispatchers.Main) {
                        clearPanels()
                        longVideoControlView.showTips("压缩包条目不存在")
                    }
                }

                ZipXmlLoader.OpenResultState.ERROR -> {
                    withContext(Dispatchers.Main) {
                        clearPanels()
                        longVideoControlView.showTips("弹幕读取失败")
                    }
                }
            }
        }
    }

    /** 按条目 key 打开压缩包内 XML（P1 的密码重试也复用此入口）。 */
    private fun openZip(
        fileEntity: FileEntity,
        password: String,
        track: DmTrack
    ): ZipXmlLoader.OpenResult {
        return ZipXmlLoader.loadXmlFromZipResult(
            fileEntity,
            password,
            track.archiveEntryRaw,
            track.archiveEntryIndex,
            track.title
        )
    }

    /** 异步解析 XML 文件并刷新弹幕列表/密度曲线。 */
    private fun setDmList(path: String) {
        scope.launch(Dispatchers.IO) {
            val dmList = XmlConverter.fromXmlFile(path).list
            withContext(Dispatchers.Main) {
                applyList(dmList)
            }
        }
    }

    /** 刷新弹幕列表面板，并根据弹幕时间分布更新进度条密度曲线。 */
    private fun applyList(items: List<DmItem>) {
        val list = items.sortedBy { it.time }
        if (list.isEmpty()) {
            clearPanels()
            return
        }
        dmListView.setDmList(list)
        val countList = list.map { item -> (item.time * 1000).toLong() }
        val curveData = if (countList.isEmpty()) {
            floatArrayOf()
        } else {
            DensityCalculator.calculateDensity(countList, videoView.duration)
        }
        longVideoControlView.updateCurveData(curveData)
    }

    /** 应用持久化的自定义弹幕字体。 */
    private fun setDmFont() {
        if (spUtil.dmFontMode) {
            spUtil.dmCurrentFont?.let {
                if (it.isNotBlank()) {
                    val file = File(it)
                    if (file.exists()) {
                        danmakuView.setTypeface(Typeface.createFromFile(file))
                        dmConfView.setCustomFont(it)
                    }
                }
            }
        }
    }

    /** 合并已勾选的弹幕轨道，可选择只内存加载或保存为 XML 文件。 */
    private fun merge(onSave: Boolean) {
        if (isMerge()) {
            longVideoControlView.showTips("正在合并，清稍等")
            return
        }
        val dmTrackList = dmTrackView.listSelectedTrack()
        if (dmTrackList.isEmpty()) {
            return
        }
        setMergeState(true)
        scope.launch(Dispatchers.IO) {
            if (onSave) {
                // 合并是异步操作，切走视频后 name 可能变空；此时放弃保存，避免生成无名 .xml。
                val currentName = currentVideoName()
                if (currentName.isBlank()) {
                    withContext(Dispatchers.Main) {
                        longVideoControlView.showTips("视频已切换，合并取消")
                        setMergeState(false)
                    }
                    return@launch
                }
                val file = AppFile(context).buildCustom("xml", "${currentName}.xml")
                val success = AtomicInteger(dmTrackList.size)
                XmlMerger.mergeXmlParts(dmTrackList, file) { name, message ->
                    if (message.isNotBlank()) {
                        success.getAndDecrement()
                        // 合并回调在 IO 线程同步触发 N 次，用 view.post 比 scope.launch(Main) 轻。
                        postOnMain {
                            longVideoControlView.showSubTips("$name $message")
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    longVideoControlView.showTips("${success.get()}个文件合并成功")
                }
            } else {
                ByteArrayOutputStream().use { outputStream ->
                    XmlMerger.mergeXmlParts(dmTrackList, outputStream) { name, message ->
                        if (message.isNotBlank()) {
                            postOnMain {
                                longVideoControlView.showSubTips("$name $message")
                            }
                        }
                    }
                    val byteArray = outputStream.toByteArray()
                    ByteArrayInputStream(byteArray).use { inputStream ->
                        withContext(Dispatchers.Main) {
                            try {
                                danmakuView.release()
                                danmakuView.loadDanMu(inputStream)
                                // 合并时各 track.offset 已应用到弹幕时间属性，合并后偏移应为 0
                                dmConfView.resetOffset()
                                longVideoControlView.showTips("合并装载成功")
                            } catch (e: Exception) {
                                FileOperator.writeText(
                                    scope,
                                    AppFile(context).buildCustom(
                                        "logs",
                                        "dm_track_merge_${System.currentTimeMillis()}.log"
                                    ),
                                    e.stackTraceToString()
                                )
                            }
                        }
                    }
                    ByteArrayInputStream(byteArray).use { inputStream ->
                        val dmList = XmlConverter.fromXmlInput(inputStream).list
                        withContext(Dispatchers.Main) {
                            applyList(dmList)
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                setMergeState(false)
            }
        }
    }
}
