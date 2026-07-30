package me.lingci.lib.player.mpv

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.preference.PreferenceManager
import android.view.Surface
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPV.mpvEvent
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.logD
import me.lingci.lib.player.mpv.track.MpvMediaInfoProvider
import me.lingci.lib.player.mpv.track.MpvTrackManager
import me.lingci.lib.player.mpv.track.TrackInfo
import me.lingci.lib.player.mpv.track.TrackType
import org.json.JSONArray
import org.json.JSONObject
import me.lingci.lib.player.capability.PlayerCapabilities
import me.lingci.lib.player.capability.PlayerCapabilityProvider
import me.lingci.lib.player.chapter.ChapterController
import me.lingci.lib.player.chapter.ChapterNode
import me.lingci.lib.player.chapter.ChapterProvider
import me.lingci.lib.player.chapter.OnChapterChangeListener
import me.lingci.lib.player.mediainfo.MediaInfoProvider
import me.lingci.lib.player.mediainfo.MediaInfoProviderOwner
import kotlin.math.roundToInt
import xyz.doikki.videoplayer.player.AbstractPlayer
import me.lingci.lib.player.track.ExternalTrackController
import me.lingci.lib.player.track.ExternalTrackRequest
import me.lingci.lib.player.track.MediaTrack
import me.lingci.lib.player.track.MediaTrackController
import me.lingci.lib.player.track.MediaTrackKey
import me.lingci.lib.player.track.MediaTrackProvider
import me.lingci.lib.player.track.MediaTrackSnapshot
import me.lingci.lib.player.track.MediaTrackSource
import me.lingci.lib.player.track.MediaTrackType
import me.lingci.lib.player.track.OnMediaTracksChangedListener
import xyz.doikki.videoplayer.util.L
import me.lingci.lib.player.util.SurfaceRenderTrace
import kotlin.collections.find

/**
 * https://github.com/allentown521/mpv-video/blob/main/src/main/java/is/xyz/mpv/MPVActivity.kt
 *
 * MPV backend adapter. MPV native properties/events stay in this module and are surfaced through
 * dkplayer-java optional interfaces so app modules can share the same track/media-info UI as Exo.
 */
@Suppress("TooManyFunctions")
class MpvMediaPlayer(context: Context) : AbstractPlayer(),
    MPV.EventObserver,
    PlayerCapabilityProvider,
    MediaTrackProvider,
    MediaTrackController,
    ExternalTrackController,
    ChapterProvider,
    ChapterController,
    MediaInfoProviderOwner {

    val mpv = MPV()
    
    private var playerSupervisorJob = SupervisorJob()
    var playerScopeMain = CoroutineScope(Dispatchers.Main + playerSupervisorJob)
        private set
    var playerScopeIO = CoroutineScope(Dispatchers.IO + playerSupervisorJob)
        private set

    private var appContext: Context = context.applicationContext
    private var holder: SurfaceHolder? = null
    private var mMediaSourceHelper: MpvMediaSourceHelper = MpvMediaSourceHelper(appContext)
    private var currentPath = ""
    private var pendingPath: String? = null
    private var pendingHeaders: Map<String, String>? = null
    @Volatile
    private var hasAttachedSurface = false

    private var voInUse: String = "gpu"
    private var mIsPreparing = false
    private var configDir: String = appContext.filesDir.path
    private var cacheDir: String = appContext.cacheDir.path
    private val _psc = MpvUtil.PlaybackStateCache()
    val psc: MpvUtil.PlaybackStateCache get() = _psc
    private val trackManager = MpvTrackManager()
    // MPV reports tracks/chapters through observed properties. These cached values provide stable
    // snapshots for MediaTrackProvider and ChapterProvider callers.
    private var chapters: List<ChapterNode> = emptyList()
    private var currentChapterIndex: Int = -1
    private var mediaTracksChangedListener: OnMediaTracksChangedListener? = null
    private var chapterChangeListener: OnChapterChangeListener? = null
    var lastEventLabel: String = "idle"
        private set
    var lastEndFileReason: String = ""
        private set
    var lastEndFileError: String = ""
        private set
    
    // 初始化状态标志（由于MPV没有isInitialized方法，我们自己维护）
    @Volatile
    private var isMpvInitialized = false

    // 收到 shutdown 事件后，旧 handle 不再可靠，下次播放前需要重新初始化。
    @Volatile
    private var needsReinitialize = false
     
    // 释放状态标志（用于区分主动释放和意外关闭）
    @Volatile
    private var isReleasing = false

    @Volatile
    private var isReleased = false

    @Volatile
    private var isNativeDestroyed = false

    @Volatile
    private var hasNativeCreated = false
    
    // 硬件解码模式: "auto", "auto-copy", "no", "mediacodec", "mediacodec-copy"
    private var hwdecMode: String = "auto"
    
    // 缓存大小（MB）
    private var cacheSize: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) 64 else 32

    /**
     * MPV 顺序读优化开关的 SharedPreferences key。
     *
     * ⚠️ 跨模块约定：值由 dy-player.SpUtil.labMpvSequentialRead 写入（app 默认 SharedPreferences）。
     * player-mpv 模块不能依赖 dy-player，所以按字符串 key 读取。
     * 默认 true：老用户升级后自动获益；如某文件出现副作用可手动关。
     */
    private val SP_KEY_LAB_MPV_SEQUENTIAL_READ = "labMpvSequentialRead"
    private val SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT = true

    // HDR模式
    enum class HdrMode {
        SDR_MAPPING,       // 稳定优先：映射到 SDR 目标显示。
        HDR10_TARGET_OUTPUT, // 面向 HDR10 目标输出的尝试，不代表已验证系统级直通。
        HLG_TARGET_OUTPUT    // 面向 HLG 目标输出的尝试，不代表已验证系统级直通。
    }
    
    private var hdrMode: HdrMode = HdrMode.SDR_MAPPING
    private var lastDisplayWidth = 0
    private var lastDisplayHeight = 0

    // ========== 外部配置方法 ==========
    
    fun setVo(vo: String) {
        voInUse = vo
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setPropertyString("vo", vo)
        }
    }

    fun setConfigDir(dir: String) {
        configDir = dir
    }

    fun setCacheDir(dir: String) {
        cacheDir = dir
    }
    
    /**
     * 设置硬件解码模式
     * @param mode 可选值: "auto", "auto-copy", "no", "mediacodec", "mediacodec-copy"
     * "auto" 和 "auto-copy" 会在硬解失败时自动回退到软解
     */
    fun setHwdec(mode: String) {
        hwdecMode = mode
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setOptionString("hwdec", mode)
        }
    }
    
    /**
     * 获取当前实际使用的硬件解码器
     */
    fun getHwdec(): String {
        return if (isMpvInitialized && !isNativeDestroyed) {
            mpv.getPropertyString("hwdec-current") ?: hwdecMode
        } else {
            hwdecMode
        }
    }
    
    /**
     * 设置缓存大小
     * @param megabytes 缓存大小（MB）
     */
    fun setCacheSize(megabytes: Int) {
        cacheSize = megabytes
        if (isMpvInitialized && !isNativeDestroyed) {
            val bytes = megabytes * 1024 * 1024L
            mpv.setOptionString("demuxer-max-bytes", bytes.toString())
            mpv.setOptionString("demuxer-max-back-bytes", bytes.toString())
        }
    }
    
    /**
     * 设置HDR模式
     * @param mode HDR模式
     */
    fun setHdrMode(mode: HdrMode) {
        hdrMode = mode
        if (!isMpvInitialized || isNativeDestroyed) return
        
        when (mode) {
            HdrMode.SDR_MAPPING -> {
                mpv.setOptionString("target-prim", "bt.709")
                mpv.setOptionString("target-trc", "srgb")
                mpv.setOptionString("tone-mapping", "auto")
            }
            HdrMode.HDR10_TARGET_OUTPUT -> {
                mpv.setOptionString("target-prim", "bt.2020")
                mpv.setOptionString("target-trc", "pq")
                mpv.setOptionString("tone-mapping", "auto")
            }
            HdrMode.HLG_TARGET_OUTPUT -> {
                mpv.setOptionString("target-prim", "bt.2020")
                mpv.setOptionString("target-trc", "hlg")
                mpv.setOptionString("tone-mapping", "auto")
            }
        }
    }
    
    /**
     * 设置色调映射算法
     * @param algorithm 可选值: "clip", "mobius", "reinhard", "hable", "bt.2390", "gamma", "linear"
     */
    fun setToneMapping(algorithm: String) {
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setOptionString("tone-mapping", algorithm)
        }
    }

    override fun initPlayer() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        Utils.copyAssets(appContext)
        initialize()
    }

    fun getTrackManager(): MpvTrackManager {
        // Test/debug entry point. Normal UI should use MediaTrackProvider/MediaTrackController.
        return trackManager
    }

    fun cycleAudioTrack(): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) trackManager.cycleAudio(mpv) else false
    }

    fun cycleSubtitleTrack(): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) trackManager.cycleSubtitle(mpv) else false
    }

    fun disableSubtitleTrack(): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) trackManager.disableSubtitle(mpv) else false
    }

    fun selectAudioTrack(trackId: Int): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) trackManager.selectAudioTrack(mpv, trackId) else false
    }

    fun selectSubtitleTrack(trackId: Int): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) trackManager.selectSubtitleTrack(mpv, trackId) else false
    }

    fun selectVideoTrack(trackId: Int): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) trackManager.selectVideoTrack(mpv, trackId) else false
    }

    fun getSelectedAudioTrack(): TrackInfo? {
        return trackManager.getSelectedAudioTrack()
    }

    fun getSelectedSubtitleTrack(): TrackInfo? {
        return trackManager.getSelectedSubtitleTrack()
    }

    override fun getChapters(): List<ChapterNode> {
        return chapters
    }

    override fun getCurrentChapter(): ChapterNode? {
        return chapters.find { it.index == currentChapterIndex }
    }

    override fun setOnChapterChangeListener(listener: OnChapterChangeListener?) {
        chapterChangeListener = listener
        listener?.onChaptersLoaded(chapters)
        listener?.onCurrentChapterChanged(getCurrentChapter())
    }

    fun refreshObservedState() {
        if (!isMpvInitialized || isNativeDestroyed) return
        try {
            trackManager.updateCurrentTracks(mpv)
            notifyMediaTracksChanged()
            updateCurrentChapterByPosition(currentPosition)
            mpv.getPropertyNode("track-list")?.let {
                trackManager.updateFromMPVNode(it)
                trackManager.updateCurrentTracks(mpv)
                notifyMediaTracksChanged()
            }
            mpv.getPropertyNode("chapter-list")?.let {
                chapters = parseChapters(it)
                updateCurrentChapterByPosition(currentPosition)
                chapterChangeListener?.onChaptersLoaded(chapters)
            }
            lastEventLabel = "manual_refresh"
        } catch (e: Exception) {
            L.e("Error refreshing observed state: ${e.message}")
        }
    }

    override fun seekToChapter(index: Int): Boolean {
        val chapter = chapters.find { it.index == index } ?: return false
        seekTo(chapter.startTimeMs)
        currentChapterIndex = chapter.index
        chapterChangeListener?.onCurrentChapterChanged(chapter)
        return true
    }

    override fun seekToNextChapter(): Boolean {
        val nextChapter = chapters.firstOrNull { it.index > currentChapterIndex }
            ?: chapters.firstOrNull { it.startTimeMs > currentPosition }
            ?: return false
        return seekToChapter(nextChapter.index)
    }

    override fun seekToPreviousChapter(): Boolean {
        val position = currentPosition
        val previousChapter = chapters
            .filter { it.startTimeMs < position - 1000 }
            .maxByOrNull { it.startTimeMs }
            ?: chapters.firstOrNull()
            ?: return false
        return seekToChapter(previousChapter.index)
    }

    private fun clearChapterState() {
        chapters = emptyList()
        currentChapterIndex = -1
    }

    private fun parseChapters(node: MPVNode): List<ChapterNode> {
        return try {
            val jsonArray = JSONArray(node.toJson())
            val result = mutableListOf<ChapterNode>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optJSONObject(i) ?: continue
                val startMs = ((item.optDouble("time", -1.0)) * 1000).toLong()
                if (startMs < 0) continue
                val title = item.optString("title").ifBlank { "Chapter ${i + 1}" }
                val language = item.optString("language").takeIf { it.isNotBlank() }
                result.add(ChapterNode(i, title, startMs, null, language))
            }
            result.sortBy { it.startTimeMs }
            result.mapIndexed { idx, chapter ->
                val endTimeMs = result.getOrNull(idx + 1)?.startTimeMs
                ChapterNode(idx, chapter.title, chapter.startTimeMs, endTimeMs, chapter.language)
            }
        } catch (e: Exception) {
            L.e("Error parsing chapter-list: ${e.message}")
            emptyList()
        }
    }

    private fun dispatchChaptersLoaded(newChapters: List<ChapterNode>) {
        chapters = newChapters
        if (chapters.isEmpty()) {
            currentChapterIndex = -1
            chapterChangeListener?.onChaptersLoaded(chapters)
            chapterChangeListener?.onCurrentChapterChanged(null)
            return
        }
        updateCurrentChapterByPosition(currentPosition)
        chapterChangeListener?.onChaptersLoaded(chapters)
        playerScopeMain.launch {
            if (isReleasing || isReleased || isNativeDestroyed) return@launch
            mPlayerEventListener?.onInfo(MEDIA_INFO_CHAPTERS_LOADED, chapters.size)
            mPlayerEventListener?.onChaptersLoaded(chapters)
        }
    }

    private fun updateCurrentChapterByPosition(positionMs: Long) {
        if (chapters.isEmpty()) {
            currentChapterIndex = -1
            return
        }
        val oldChapter = getCurrentChapter()
        currentChapterIndex = chapters.indexOfLast { it.startTimeMs <= positionMs }
            .takeIf { it >= 0 }
            ?: 0
        val newChapter = getCurrentChapter()
        if (oldChapter != newChapter) {
            chapterChangeListener?.onCurrentChapterChanged(newChapter)
        }
    }

    override fun getMediaTracks(): MediaTrackSnapshot {
        // MPV exposes the current selected id, not Exo-style explicit disabled overrides. Treat
        // "tracks exist but no selected track" as the common disabled state for the panel.
        val audioDisabled = trackManager.getSelectedAudioTrack() == null && trackManager.audioTracks.isNotEmpty()
        val subtitleDisabled = trackManager.getSelectedSubtitleTrack() == null && trackManager.subtitleTracks.isNotEmpty()
        val videoDisabled = trackManager.getSelectedVideoTrack() == null && trackManager.videoTracks.isNotEmpty()
        return MediaTrackSnapshot(
            videoTracks = trackManager.videoTracks.map { it.toMediaTrack() },
            audioTracks = trackManager.audioTracks.map { it.toMediaTrack() },
            subtitleTracks = trackManager.subtitleTracks.map { it.toMediaTrack() },
            disabledTypes = buildSet {
                if (videoDisabled) add(MediaTrackType.VIDEO)
                if (audioDisabled) add(MediaTrackType.AUDIO)
                if (subtitleDisabled) add(MediaTrackType.SUBTITLE)
            }
        )
    }

    override fun setOnMediaTracksChangedListener(listener: OnMediaTracksChangedListener?) {
        mediaTracksChangedListener = listener
        listener?.onMediaTracksChanged(getMediaTracks())
    }

    override fun selectTrack(key: MediaTrackKey): Boolean {
        // MediaTrackKey.id stores MPV's native numeric track id, not the adapter list position.
        if (key.backend != BACKEND_MPV) return false
        val trackId = key.id.toIntOrNull() ?: return false
        return when (key.type) {
            MediaTrackType.VIDEO -> selectVideoTrack(trackId)
            MediaTrackType.AUDIO -> selectAudioTrack(trackId)
            MediaTrackType.SUBTITLE -> selectSubtitleTrack(trackId)
            MediaTrackType.UNKNOWN -> false
        }
    }

    override fun disableTrack(type: MediaTrackType): Boolean {
        if (!isMpvInitialized || isNativeDestroyed) return false
        return try {
            when (type) {
                MediaTrackType.VIDEO -> mpv.setPropertyString("vid", "no")
                MediaTrackType.AUDIO -> mpv.setPropertyString("aid", "no")
                MediaTrackType.SUBTITLE -> mpv.setPropertyString("sid", "no")
                MediaTrackType.UNKNOWN -> return false
            }
            trackManager.updateCurrentTracks(mpv)
            notifyMediaTracksChanged()
            true
        } catch (e: Exception) {
            L.e("MPV disable track failed: ${e.message}")
            false
        }
    }

    override fun clearTrackOverride(type: MediaTrackType): Boolean {
        if (!isMpvInitialized || isNativeDestroyed) return false
        return try {
            when (type) {
                MediaTrackType.VIDEO -> mpv.setPropertyString("vid", "auto")
                MediaTrackType.AUDIO -> mpv.setPropertyString("aid", "auto")
                MediaTrackType.SUBTITLE -> mpv.setPropertyString("sid", "auto")
                MediaTrackType.UNKNOWN -> return false
            }
            trackManager.updateCurrentTracks(mpv)
            notifyMediaTracksChanged()
            true
        } catch (e: Exception) {
            L.e("MPV clear track override failed: ${e.message}")
            false
        }
    }

    override fun setPreferredLanguages(audioLanguages: List<String>, subtitleLanguages: List<String>): Boolean {
        if (!isMpvInitialized || isNativeDestroyed) return false
        audioLanguages.joinToString(",").takeIf { it.isNotBlank() }?.let { mpv.setOptionString("alang", it) }
        subtitleLanguages.joinToString(",").takeIf { it.isNotBlank() }?.let { mpv.setOptionString("slang", it) }
        return true
    }

    override fun addExternalTrack(request: ExternalTrackRequest): Boolean {
        if (!isMpvInitialized || isNativeDestroyed) return false
        if (request.headers.isNotEmpty()) {
            L.w("MPV external track headers are not supported")
            return false
        }
        val path = if (request.uri.scheme == null || request.uri.scheme == "file") {
            request.uri.path ?: request.uri.toString()
        } else {
            request.uri.toString()
        }
        val flags = if (request.selectAfterAdd) "select" else "auto"
        val success = when (request.type) {
            MediaTrackType.AUDIO -> trackManager.addExternalAudio(mpv, path, flags, request.title, request.language)
            MediaTrackType.SUBTITLE -> trackManager.addExternalSubtitle(mpv, path, flags, request.title, request.language)
            else -> false
        }
        if (success) refreshObservedState()
        return success
    }

    override fun removeExternalTrack(key: MediaTrackKey): Boolean {
        if (key.backend != BACKEND_MPV || !isMpvInitialized || isNativeDestroyed) return false
        val trackId = key.id.toIntOrNull() ?: return false
        val type = when (key.type) {
            MediaTrackType.VIDEO -> TrackType.VIDEO
            MediaTrackType.AUDIO -> TrackType.AUDIO
            MediaTrackType.SUBTITLE -> TrackType.SUBTITLE
            MediaTrackType.UNKNOWN -> return false
        }
        val success = trackManager.removeTrack(mpv, trackId, type)
        if (success) notifyMediaTracksChanged()
        return success
    }

    override fun createMediaInfoProvider(): MediaInfoProvider {
        return MpvMediaInfoProvider(this)
    }

    override fun getMediaInfoProviderName(): String {
        return "MPV"
    }

    override fun getPlayerCapabilities(): PlayerCapabilities {
        return PlayerCapabilities(
            canListTracks = true,
            canSelectVideoTrack = true,
            canSelectAudioTrack = true,
            canSelectSubtitleTrack = true,
            canDisableAudio = true,
            canDisableSubtitle = true,
            canAddExternalAudio = true,
            canAddExternalSubtitle = true,
            canProvideSubtitleCues = false,
            canRenderSubtitleInternally = true,
            canProvideChapters = true,
            canProvideMediaInfo = true,
            maxPlaybackSpeed = 4f,
            maxLongPressSpeed = 4f,
            requiresSurfaceRenderView = true,
            supportsScreenshot = true
        )
    }

    private fun notifyMediaTracksChanged() {
        playerScopeMain.launch {
            mediaTracksChangedListener?.onMediaTracksChanged(getMediaTracks())
        }
    }

    private fun TrackInfo.toMediaTrack(): MediaTrack {
        val type = this.type.toMediaTrackType()
        return MediaTrack(
            key = MediaTrackKey(
                type = type,
                id = id.toString(),
                // Duplicated for callers that still inspect the common index fields; MPV selection
                // always interprets this value as the native track id.
                groupIndex = id,
                trackIndex = id,
                backend = BACKEND_MPV
            ),
            title = displayName,
            language = lang,
            codec = codec,
            isSelected = isSelected,
            isDefault = isDefault,
            isExternal = isExternal,
            isSupported = true,
            source = if (isExternal) MediaTrackSource.EXTERNAL else MediaTrackSource.EMBEDDED,
            filePath = filePath
        )
    }

    private fun TrackType.toMediaTrackType(): MediaTrackType {
        return when (this) {
            TrackType.VIDEO -> MediaTrackType.VIDEO
            TrackType.AUDIO -> MediaTrackType.AUDIO
            TrackType.SUBTITLE -> MediaTrackType.SUBTITLE
        }
    }

    private fun ensurePlayerScopes() {
        if (!playerSupervisorJob.isActive) {
            playerSupervisorJob = SupervisorJob()
            playerScopeMain = CoroutineScope(Dispatchers.Main + playerSupervisorJob)
            playerScopeIO = CoroutineScope(Dispatchers.IO + playerSupervisorJob)
        }
    }

    fun getVoState(): String {
        return if (isMpvInitialized && !isNativeDestroyed) {
            mpv.getPropertyString("vo") ?: voInUse
        } else {
            voInUse
        }
    }

    private data class EndFileInfo(
        val reason: String,
        val error: String,
    )

    private fun parseEndFileInfo(data: MPVNode): EndFileInfo {
        return try {
            val json = JSONObject(data.toJson())
            EndFileInfo(
                reason = json.optString("reason").ifBlank { "unknown" },
                error = json.opt("file_error")?.toString()?.takeIf { it.isNotBlank() }
                    ?: json.opt("error")?.toString()?.takeIf { it.isNotBlank() }
                    ?: ""
            )
        } catch (e: Exception) {
            val raw = data.toString()
            EndFileInfo(
                reason = raw,
                error = ""
            )
        }
    }

    private fun initialize() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        ensurePlayerScopes()
        logD("MPV initialize", isMpvInitialized)
        try {
            if (isMpvInitialized.not()) {

                mpv.create(appContext)
                hasNativeCreated = true
            }

            /* set normal options (user-supplied config can override) */
            mpv.setOptionString("config", "yes")
            mpv.setOptionString("config-dir", configDir)
            for (opt in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) {
                mpv.setOptionString(opt, cacheDir)
            }

            // 设置初始化选项（必须在mpv.init()之前）
            setInitOptions()

            if (isMpvInitialized.not()) {

                mpv.init()
            }
            
            // 设置运行时选项（在mpv.init()之后）
            setRuntimeOptions()

            /* set hardcoded options */
            mpv.setOptionString("force-window", "no")
            // Keep the embedded player alive after EOF so replay can reuse the instance.
            mpv.setOptionString("idle", "yes")

            mpv.removeObserver(this)
            mpv.addObserver(this)
            isReleasing = false
            isReleased = false
            isNativeDestroyed = false
            needsReinitialize = false
            isMpvInitialized = true
            // 开始监听属性变化
            observeProperties()
        } catch (e: Exception) {
            isMpvInitialized = false
            needsReinitialize = true
            if (!hasNativeCreated) {
                isNativeDestroyed = true
            }
            L.e("MPV initialize failed: ${e.message}")
        }
    }

    private fun ensureInitializedForPlayback() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        if (!isMpvInitialized || needsReinitialize) {
            initialize()
        }
    }

    private fun detachSurfaceIfNeeded() {
        if (!hasNativeCreated || !isMpvInitialized || !hasAttachedSurface) return
        try {
            SurfaceRenderTrace.d("MpvMediaPlayer", "detachSurface hasNativeCreated=$hasNativeCreated initialized=$isMpvInitialized")
            mpv.detachSurface()
        } catch (e: Exception) {
            L.e("MPV detachSurface failed: ${e.message}")
            SurfaceRenderTrace.e("MpvMediaPlayer", "detachSurface failed: ${e.message}")
        } finally {
            hasAttachedSurface = false
        }
    }

    private fun safeRemoveObserver() {
        if (!hasNativeCreated || isNativeDestroyed) return
        try {
            mpv.removeObserver(this)
        } catch (e: Exception) {
            L.e("MPV removeObserver failed: ${e.message}")
        }
    }

    private fun safeStop() {
        if (!isMpvInitialized || isNativeDestroyed) return
        try {
            mpv.command("stop")
        } catch (e: Exception) {
            L.e("MPV stop failed: ${e.message}")
        }
    }

    private fun safeSetVoNull() {
        if (!isMpvInitialized || isNativeDestroyed) return
        try {
            mpv.setOptionString("vo", "null")
        } catch (e: Exception) {
            L.e("MPV set vo=null failed: ${e.message}")
        }
    }

    private fun safeDestroyNative() {
        if (!hasNativeCreated || isNativeDestroyed) {
            isNativeDestroyed = true
            return
        }
        try {
            mpv.destroy()
        } catch (e: Exception) {
            L.e("MPV destroy failed: ${e.message}")
        } finally {
            hasNativeCreated = false
            isNativeDestroyed = true
        }
    }

    private fun preparePlaybackRequest(path: String, headers: Map<String, String>?) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        pendingPath = path
        pendingHeaders = headers?.toMap()

        safeStop()
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setPropertyBoolean("pause", false)
        }

        psc.clear()
        lastDisplayWidth = 0
        lastDisplayHeight = 0
        trackManager.clear()
        clearChapterState()

        mMediaSourceHelper.setHeaders(mpv, headers?.toMutableMap() ?: mutableMapOf())

        mIsPreparing = true
        playbackHasStarted = false
    }

    private fun flushPendingLoadIfReady() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        val path = pendingPath ?: return
        if (!isMpvInitialized || isNativeDestroyed || !hasAttachedSurface) {
            // loadfile before a Surface is attached can leave MPV with an invalid VO/black frame.
            // Deferring here matches PlayerCapabilities.requiresSurfaceRenderView.
            L.d("Defer loadfile until surface ready, path=$path attached=$hasAttachedSurface")
            SurfaceRenderTrace.d("MpvMediaPlayer", "defer loadfile initialized=$isMpvInitialized nativeDestroyed=$isNativeDestroyed attached=$hasAttachedSurface path=$path")
            return
        }

        pendingPath = null
        val headers = pendingHeaders
        pendingHeaders = null
        mMediaSourceHelper.setHeaders(mpv, headers?.toMutableMap() ?: mutableMapOf())

        try {
            L.d("Flush deferred loadfile: $path")
            SurfaceRenderTrace.d("MpvMediaPlayer", "flush loadfile path=$path headers=${headers?.size ?: 0}")
            mpv.command("loadfile", path, "replace")
        } catch (e: Exception) {
            L.e("Error loading file: ${e.message}")
            SurfaceRenderTrace.e("MpvMediaPlayer", "loadfile failed: ${e.message}")
            if (!isReleasing && !isReleased && !isNativeDestroyed) {
                mPlayerEventListener?.onError()
            }
            mIsPreparing = false
        }
    }

    override fun setDataSource(path: String?, headers: Map<String, String>?) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        Log.d(this, "setDataSource", path , currentPath)
        currentPath = path?:""
        if (path.isNullOrBlank()) {
            return
        }
        ensureInitializedForPlayback()
        if (!isMpvInitialized || isNativeDestroyed) return
        preparePlaybackRequest(path, headers)
        flushPendingLoadIfReady()
    }

    override fun setDataSource(fd: AssetFileDescriptor) {
        // 暂时不支持AssetFileDescriptor
        L.w("AssetFileDescriptor not supported")
    }

    override fun start() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        logD("MPV start", isMpvInitialized)
        ensureInitializedForPlayback()
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setPropertyBoolean("pause", false)
        }
    }

    override fun pause() {
        logD("MPV pause", isMpvInitialized)
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setPropertyBoolean("pause", true)
        }
    }

    override fun stop() {
        logD("MPV stop", isMpvInitialized)
        playbackHasStarted = false
        safeStop()
    }

    override fun prepareAsync() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        logD("MPV prepareAsync")
        mIsPreparing = true
        // MPV会自动准备，这里主要是标记状态
    }

    override fun reset() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        logD("MPV reset", isMpvInitialized)
        playbackHasStarted = false
        pendingPath = null
        pendingHeaders = null
        if (isMpvInitialized && !isNativeDestroyed) {
            safeStop()
            // 不设置vo = "null"，保持当前vo设置，避免重新播放时无画面
        }
        mIsPreparing = false
        // 清空状态缓存
        psc.clear()
        lastDisplayWidth = 0
        lastDisplayHeight = 0
        trackManager.clear()
        clearChapterState()
    }

    override fun isPlaying(): Boolean {
        return if (isMpvInitialized && !isNativeDestroyed) {
            playbackHasStarted && mpv.getPropertyBoolean("pause")?.not() == true
        } else {
            false
        }
    }

    override fun seekTo(time: Long) {
        if (isMpvInitialized && !isNativeDestroyed) {
            L.d("seek to $time pos ${mpv.getPropertyDouble("time-pos")} duration ${mpv.getPropertyDouble("duration/full")}")
            mpv.setPropertyDouble("time-pos", time / 1000.0)
        }
    }

    override fun release() {
        logD("MPV release", isMpvInitialized)
        if (isReleased && isNativeDestroyed) return
        if (isReleasing) return
        isReleasing = true
        mIsPreparing = false
        playbackHasStarted = false
        currentPath = ""
        pendingPath = null
        pendingHeaders = null

        try {
            safeRemoveObserver()
            mPlayerEventListener = null
            playerSupervisorJob.cancel()
            safeStop()
            safeSetVoNull()
            detachSurfaceIfNeeded()
            safeDestroyNative()
        } finally {
            isMpvInitialized = false
            needsReinitialize = true
            hasAttachedSurface = false
            holder = null

            psc.clear()
            lastDisplayWidth = 0
            lastDisplayHeight = 0
            trackManager.clear()
            clearChapterState()
            mMediaSourceHelper.release()

            isReleased = true
            isReleasing = false
        }

        L.d("MPV player released")
    }

    fun destroy() {
        release()
    }

    override fun getCurrentPosition(): Long {
        return if (isMpvInitialized && !isNativeDestroyed) {
            val pos = mpv.getPropertyDouble("time-pos")
            if (pos != null) {
                (pos * 1000.0).toLong()
            } else {
                0L
            }
        } else {
            0L
        }
    }

    override fun getDuration(): Long {
        if (!isMpvInitialized || isNativeDestroyed) return 0L
        
        try {
            // 优先使用 duration/full 获取更准确的时长
            val durationFull = mpv.getPropertyDouble("duration/full")
            if (durationFull != null) {
                return (durationFull * 1000).toLong() // 转换为毫秒
            }
            // ... 其他备用方法
        } catch (e: Exception) {
            L.e("Error getting duration: ${e.message}")
        }
        
        return 0L
    }

    override fun getBufferedPercentage(): Int {
        if (!isMpvInitialized || isNativeDestroyed) return 0
        
        try {
            // 尝试获取缓冲百分比
            val buffered = mpv.getPropertyInt("cache-buffering-state")
            if (buffered != null) {
                return buffered
            }
            
            // 备用方案：通过已缓冲时间计算
            val cacheTime = mpv.getPropertyInt("demuxer-cache-time") ?: 0
            val duration = getDuration()
            return if (duration > 0L) {
                (cacheTime * 100 / duration).coerceIn(0, 100)
            } else {
                0
            }.toInt()
        } catch (e: Exception) {
            L.e("Error getting buffered percentage: ${e.message}")
            return 0
        }
    }

    override fun setSurface(surface: Surface) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        Log.d(this, "setSurface", isMpvInitialized)
        SurfaceRenderTrace.d("MpvMediaPlayer", "setSurface initialized=$isMpvInitialized released=$isReleased nativeDestroyed=$isNativeDestroyed surface=$surface valid=${surface.isValid}")
        ensureInitializedForPlayback()
        if (isMpvInitialized && !isNativeDestroyed) {
            var attached = false
            try {
                // Surface attachment is the last prerequisite for deferred loadfile requests.
                mpv.attachSurface(surface)
                attached = true
                hasAttachedSurface = true
                SurfaceRenderTrace.d("MpvMediaPlayer", "attachSurface success")
                mpv.setPropertyString("vo", voInUse)
                mpv.setOptionString("force-window", "yes")
                flushPendingLoadIfReady()
            } catch (e: Exception) {
                if (!attached) {
                    hasAttachedSurface = false
                }
                L.e("MPV attachSurface failed: ${e.message}")
                SurfaceRenderTrace.e("MpvMediaPlayer", "attachSurface failed: ${e.message}")
            }
        }
    }

    override fun setDisplay(holder: SurfaceHolder?) {
        this.holder = holder
        if (holder == null) {
            SurfaceRenderTrace.d("MpvMediaPlayer", "setDisplay null initialized=$isMpvInitialized attached=$hasAttachedSurface")
            if (isReleasing || isReleased || isNativeDestroyed) return
            if (!isMpvInitialized || isNativeDestroyed) return
            safeSetVoNull()
            try {
                mpv.setOptionString("force-window", "no")
            } catch (e: Exception) {
                L.e("MPV set force-window=no failed: ${e.message}")
            }
            detachSurfaceIfNeeded()
            return
        }

        if (isReleasing || isReleased || isNativeDestroyed) return
        ensureInitializedForPlayback()
        SurfaceRenderTrace.d("MpvMediaPlayer", "setDisplay holder surface=${holder.surface} valid=${holder.surface?.isValid} initialized=$isMpvInitialized attached=$hasAttachedSurface")
        holder.surface?.let {
            setSurface(it)
        }
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        if (isMpvInitialized && !isNativeDestroyed) {
            try {
                // 计算平均音量 (0.0-1.0) 转换为百分比
                val volume = ((leftVolume + rightVolume) / 2 * 100).toInt()
                mpv.setPropertyInt("volume", volume)
            } catch (e: Exception) {
                L.e("Error setting volume: ${e.message}")
            }
        }
    }

    override fun setLooping(isLooping: Boolean) {
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setOptionString("loop-file", if (isLooping) "inf" else "no")
        }
    }

    /**
     * 初始化选项（必须在mpv.init()之前设置）
     */
    private fun setInitOptions() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        mpv.setOptionString("profile", "fast")
        
        // GPU上下文
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        
        // 显示刷新率
        val disp = ContextCompat.getDisplayOrDefault(appContext)
        val refreshRate = disp.mode.refreshRate
        L.d("Display ${disp.displayId} reports FPS of $refreshRate")
        mpv.setOptionString("display-fps-override", refreshRate.toString())
        
        // 缓存大小
        val bytes = cacheSize * 1024 * 1024L
        mpv.setOptionString("demuxer-max-bytes", bytes.toString())
        mpv.setOptionString("demuxer-max-back-bytes", bytes.toString())

        // MPV 顺序读优化：禁用 ffmpeg mov demuxer 的 interleaved_read，避免 badly-interleaved
        // MP4 over HTTP 触发 seek 风暴。跨模块读 dy-player 写入的 labMpvSequentialRead。
        val mpvSequentialRead = try {
            PreferenceManager.getDefaultSharedPreferences(appContext)
                .getBoolean(SP_KEY_LAB_MPV_SEQUENTIAL_READ, SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT)
        } catch (e: Exception) {
            L.e("Failed to read mpv sequential read preference: ${e.message}")
            SP_LAB_MPV_SEQUENTIAL_READ_DEFAULT
        }
        if (mpvSequentialRead) {
            mpv.setOptionString("demuxer-lavf-o", "interleaved_read=0")
        }
    }
    
    /**
     * 运行时选项（可以在mpv.init()之后设置）
     */
    private fun setRuntimeOptions() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        // 视频输出
        mpv.setOptionString("vo", voInUse)
        
        // 硬件解码（auto支持软解回退）
        mpv.setOptionString("hwdec", hwdecMode)
        mpv.setOptionString("hwdec-codecs", "all")
        
        // 音频输出
        mpv.setOptionString("ao", "audiotrack,opensles")
        
        // 视频同步
        mpv.setOptionString("video-sync", "audio")

        // SurfaceView 保持容器尺寸，画面比例交给 MPV 在 Surface 内自适应。
        mpv.setOptionString("keepaspect", "yes")
        mpv.setOptionString("video-unscaled", "no")
        mpv.setOptionString("panscan", "0")
        
        // 其他优化选项
        mpv.setOptionString("vd-lavc-film-grain", "cpu")
        
        // HDR/色彩空间配置
        setHdrMode(hdrMode)
    }

    override fun setOptions() {
        if (isReleasing || isReleased || isNativeDestroyed) return
        setInitOptions()
        if (isMpvInitialized && !isNativeDestroyed) {
            setRuntimeOptions()
        }
    }

    override fun setSpeed(speed: Float) {
        if (isMpvInitialized && !isNativeDestroyed) {
            mpv.setPropertyDouble("speed", speed.toDouble())
        }
    }

    override fun getSpeed(): Float {
        return if (isMpvInitialized && !isNativeDestroyed) {
            mpv.getPropertyDouble("speed")?.toFloat() ?: 1f
        } else {
            1f
        }
    }

    override fun getTcpSpeed(): Long {
        // 尝试获取网络速度
        if (!isMpvInitialized || isNativeDestroyed) return 0
        
        try {
            val networkSpeed = mpv.getPropertyInt("network-bandwidth")
            if (networkSpeed != null) {
                // 转换为KB/s
                return networkSpeed / 1024L
            }
        } catch (e: Exception) {
            L.e("Error getting network speed: ${e.message}")
        }
        return 0
    }

    // 处理播放状态变化（用于when中未明确处理的事件）
    private fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            // 保留给其他未在event()中直接处理的事件
            // MPV_EVENT_FILE_LOADED、MPV_EVENT_START_FILE、MPV_EVENT_END_FILE已在event()中处理
            else -> {
                L.d("Unhandled MPV event: $playbackState")
            }
        }
    }

    // 处理播放器错误
    private fun onPlayerError(error: Exception) {
        L.e("MPV Error: ${error.message}")
        playbackHasStarted = false
        mPlayerEventListener?.onError()
    }

    // 处理视频尺寸变化
    private fun onVideoSizeChanged() {
        if (psc.videoWidth > 0 && psc.videoHeight > 0) {
            // 首帧门控：raw 尺寸先到而 DAR 未到时不回调，避免首帧用编码尺寸显示后再跳变。
            if (!isDisplaySizeReadyForFirstFrame()) {
                return
            }
            val (displayWidth, displayHeight) = resolveDisplayVideoSize()
            if (displayWidth <= 0 || displayHeight <= 0) {
                return
            }
            if (displayWidth == lastDisplayWidth && displayHeight == lastDisplayHeight) {
                return
            }
            lastDisplayWidth = displayWidth
            lastDisplayHeight = displayHeight
            L.d(
                "Video size changed: raw=${psc.videoWidth}x${psc.videoHeight}, " +
                    "display=${displayWidth}x${displayHeight}, aspect=${psc.videoAspect}, rotation=${psc.videoRotation}"
            )
            mPlayerEventListener?.onVideoSizeChanged(displayWidth, displayHeight)
            if (psc.videoRotation != 0) {
                mPlayerEventListener?.onInfo(MEDIA_INFO_VIDEO_ROTATION_CHANGED, psc.videoRotation)
            }
        }
    }

    private fun isDisplaySizeReadyForFirstFrame(): Boolean {
        if (lastDisplayWidth > 0 && lastDisplayHeight > 0) {
            return true
        }
        if (psc.videoAspect > 0.0) {
            return true
        }
        return false
    }

    private fun resolveDisplayVideoSize(): Pair<Int, Int> {
        val rawWidth = psc.videoWidth
        val rawHeight = psc.videoHeight
        if (rawWidth <= 0 || rawHeight <= 0) {
            return rawWidth to rawHeight
        }

        var aspect = psc.videoAspect
        if (aspect <= 0.0 && isMpvInitialized && !isNativeDestroyed) {
            try {
                val latestAspect = mpv.getPropertyDouble("video-params/aspect") ?: 0.0
                if (latestAspect > 0.0) {
                    aspect = latestAspect
                    psc.update("video-params/aspect", latestAspect)
                }
            } catch (_: Exception) {
            }
        }
        if (aspect <= 0.0) {
            // 没有DAR信息，使用原始像素尺寸（旋转时宽高互换）
            return if (psc.videoRotation % 180 == 90) {
                rawHeight to rawWidth
            } else {
                rawWidth to rawHeight
            }
        }

        // 计算显示尺寸（video-params/aspect是原始存储的DAR，不含旋转）
        val displayHeight = (rawWidth / aspect).roundToInt().coerceAtLeast(1)
        val displayWidth = rawWidth

        // 旋转90/270度时，宽高互换（MPV内部已处理旋转，显示尺寸需对应旋转后的画面）
        return if (psc.videoRotation % 180 == 90) {
            displayHeight to displayWidth
        } else {
            displayWidth to displayHeight
        }
    }
    
    // 检查视频尺寸是否发生变化
    private fun checkVideoSizeChanged() {
        if (psc.videoWidth > 0 && psc.videoHeight > 0) {
            playerScopeMain.launch {
                if (isReleasing || isReleased || isNativeDestroyed) return@launch
                onVideoSizeChanged()
            }
        }
    }

    override fun eventProperty(property: String) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        L.d("Property changed: $property")
        psc.update(property, mpv)
    }

    override fun eventProperty(property: String, value: Long) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        //L.d("Long property changed: $property = $value")
        psc.update(property, value)
        when (property) {
            "time-pos" -> {
                updateCurrentChapterByPosition(value * 1000L)
            }
            "vid", "aid", "sid" -> {
                trackManager.updateCurrentTracks(mpv)
                notifyMediaTracksChanged()
            }
            "chapter" -> {
                currentChapterIndex = value.toInt().coerceAtLeast(-1)
            }
            "width", "height", "video-params/w", "video-params/h", "video-params/rotate" -> {
                checkVideoSizeChanged()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        L.d("Boolean property changed: $property = $value")
        psc.update(property, value)
        when(property) {
            "pause" -> {
                // 暂停状态由isPlaying()直接获取，无需回调
            }
            "paused-for-cache" -> {
                playerScopeMain.launch {
                    if (isReleasing || isReleased || isNativeDestroyed) return@launch
                    if (value) {
                        mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_START, 0)
                    } else {
                        mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_END, psc.bufferedPercentage)
                    }
                }
            }
            "seeking" -> {
                playerScopeMain.launch {
                    if (isReleasing || isReleased || isNativeDestroyed) return@launch
                    if (value) {
                        mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_START, 0)
                    } else {
                        mPlayerEventListener?.onInfo(MEDIA_INFO_BUFFERING_END, psc.bufferedPercentage)
                    }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        psc.update(property, value)
        L.d("String property changed: $property = $value")
    }

    override fun eventProperty(property: String, value: Double) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        //L.d("Double property changed: $property = $value")
        psc.update(property, value)
        when (property) {
            "time-pos" -> {
                updateCurrentChapterByPosition((value * 1000.0).toLong())
            }
            "video-params/aspect" -> {
                checkVideoSizeChanged()
            }
        }
    }

    override fun eventProperty(property: String, value: MPVNode) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        // Observed MPV properties are the source of truth for the generic track/chapter/media-info
        // adapters. New adapter fields usually need a matching property added in observeProperties().
        when (property) {
            "track-list" -> {
                trackManager.updateFromMPVNode(value)
                trackManager.updateCurrentTracks(mpv)
                notifyMediaTracksChanged()
            }
            "chapter-list" -> {
                dispatchChaptersLoaded(parseChapters(value))
            }
            else -> {
                L.d("mpvNode $property $value")
            }
        }
    }

    private var statsLuaMode = 0
    private var playbackHasStarted = false
    private var onloadCommands = mutableListOf<Array<String>>()

    override fun event(eventId: Int, data: MPVNode) {
        if (isReleasing || isReleased || isNativeDestroyed) return
        L.d("MPV Event ID: $eventId")
        
        when (eventId) {
            mpvEvent.MPV_EVENT_SHUTDOWN -> {
                lastEventLabel = "shutdown"
                playbackHasStarted = false
                mIsPreparing = false
                isMpvInitialized = false
                needsReinitialize = true
                hasAttachedSurface = false
                hasNativeCreated = false
                isNativeDestroyed = true
                // SHUTDOWN表示播放器正在退出，不做回调避免状态混乱
                L.d("MPV shutdown event received, isReleasing=$isReleasing")
            }
            mpvEvent.MPV_EVENT_START_FILE -> {
                lastEventLabel = "start_file"
                SurfaceRenderTrace.d("MpvMediaPlayer", "event START_FILE pending=${pendingPath != null} attached=$hasAttachedSurface")
                if (this.statsLuaMode > 0 && !playbackHasStarted) {
                    mpv.command("script-binding", "stats/display-page-${this.statsLuaMode}-toggle")
                }
                playerScopeMain.launch {
                    if (isReleasing || isReleased || isNativeDestroyed) return@launch
                    mPlayerEventListener?.onPrepared()
                }
                playbackHasStarted = true
            }
            mpvEvent.MPV_EVENT_FILE_LOADED -> {
                lastEventLabel = "file_loaded"
                SurfaceRenderTrace.d("MpvMediaPlayer", "event FILE_LOADED attached=$hasAttachedSurface preparing=$mIsPreparing")
                if (mIsPreparing && mPlayerEventListener != null) {
                    playerScopeMain.launch {
                        if (isReleasing || isReleased || isNativeDestroyed) return@launch
                        refreshVideoGeometryFromMpv()
                        onVideoSizeChanged()
                        mPlayerEventListener?.onInfo(MEDIA_INFO_RENDERING_START, 0)
                    }
                    mIsPreparing = false
                }
            }
            mpvEvent.MPV_EVENT_END_FILE -> {
                lastEventLabel = "end_file"
                playbackHasStarted = false
                mIsPreparing = false
                val endFileInfo = parseEndFileInfo(data)
                lastEndFileReason = endFileInfo.reason
                lastEndFileError = endFileInfo.error
                L.d("MPV end-file reason=${endFileInfo.reason} error=${endFileInfo.error} releasing=$isReleasing")
                SurfaceRenderTrace.d("MpvMediaPlayer", "event END_FILE reason=${endFileInfo.reason} error=${endFileInfo.error} releasing=$isReleasing")
                when {
                    isReleasing -> {
                    }
                    endFileInfo.reason == "eof" -> {
                        playerScopeMain.launch {
                            if (isReleasing || isReleased || isNativeDestroyed) return@launch
                            mPlayerEventListener?.onCompletion()
                        }
                    }
                    endFileInfo.reason == "error" -> {
                        playerScopeMain.launch {
                            if (isReleasing || isReleased || isNativeDestroyed) return@launch
                            mPlayerEventListener?.onError()
                        }
                    }
                    else -> {
                        L.d("Ignore end-file event for reason=${endFileInfo.reason}")
                    }
                }
            }
            mpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                lastEventLabel = "video_reconfig"
                SurfaceRenderTrace.d("MpvMediaPlayer", "event VIDEO_RECONFIG attached=$hasAttachedSurface")
                // 视频重新配置时，触发视频尺寸更新
                playerScopeMain.launch {
                    if (isReleasing || isReleased || isNativeDestroyed) return@launch
                    // 尝试获取最新的尺寸信息
                    try {
                        refreshVideoGeometryFromMpv()
                        onVideoSizeChanged()
                    } catch (e: Exception) {
                        L.e("Error getting video dimensions on reconfig: ${e.message}")
                    }
                }
            }
            else -> {
                lastEventLabel = "event_$eventId"
                // 处理其他事件
                onPlaybackStateChanged(eventId)
            }
        }
    }


    fun observeProperties() {
        if (!isMpvInitialized || isNativeDestroyed) return
        try {
            // Keep this list aligned with PlaybackStateCache, MpvTrackManager and media-info fields.
            val props = mapOf(
                "time-pos" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
                "duration/full" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
                "pause" to MPV.mpvFormat.MPV_FORMAT_FLAG,
                "paused-for-cache" to MPV.mpvFormat.MPV_FORMAT_FLAG,
                "seeking" to MPV.mpvFormat.MPV_FORMAT_FLAG,
                "cache-buffering-state" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "demuxer-cache-time" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
                "speed" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
                "track-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
                "vid" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "aid" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "sid" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "video-params/aspect" to MPV.mpvFormat.MPV_FORMAT_DOUBLE,
                "video-params/w" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "video-params/h" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "video-params/rotate" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "chapter" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "chapter-list" to MPV.mpvFormat.MPV_FORMAT_NODE,
                "playlist-pos" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "playlist-count" to MPV.mpvFormat.MPV_FORMAT_INT64,
                "video-format" to MPV.mpvFormat.MPV_FORMAT_STRING,
                "media-title" to MPV.mpvFormat.MPV_FORMAT_STRING,
                "metadata/by-key/Artist" to MPV.mpvFormat.MPV_FORMAT_STRING,
                "metadata/by-key/Album" to MPV.mpvFormat.MPV_FORMAT_STRING,
                "loop-playlist" to MPV.mpvFormat.MPV_FORMAT_STRING,
                "loop-file" to MPV.mpvFormat.MPV_FORMAT_STRING,
                "shuffle" to MPV.mpvFormat.MPV_FORMAT_FLAG,
                "hwdec-current" to MPV.mpvFormat.MPV_FORMAT_STRING
            )
            props.forEach { (k, v) ->
                mpv.observeProperty(k, v)
            }
        } catch (e: Exception) {
            L.e("Error observing properties: ${e.message}")
        }
    }

    private companion object {
        private const val BACKEND_MPV = "mpv"
    }

    private fun refreshVideoGeometryFromMpv() {
        if (!isMpvInitialized || isNativeDestroyed) return
        val latestW = mpv.getPropertyInt("video-params/w")?.toLong()
        if (latestW != null && latestW > 0L) {
            psc.update("video-params/w", latestW)
        }
        val latestH = mpv.getPropertyInt("video-params/h")?.toLong()
        if (latestH != null && latestH > 0L) {
            psc.update("video-params/h", latestH)
        }
        val latestAspect = mpv.getPropertyDouble("video-params/aspect")
        if (latestAspect != null && latestAspect > 0.0) {
            psc.update("video-params/aspect", latestAspect)
        }
        val latestRotate = mpv.getPropertyInt("video-params/rotate")?.toLong()
        if (latestRotate != null) {
            psc.update("video-params/rotate", latestRotate)
        }
    }





}
