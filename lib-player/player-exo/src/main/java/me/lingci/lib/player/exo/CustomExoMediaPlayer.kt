package me.lingci.lib.player.exo

import android.content.Context
import android.net.Uri
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.lingci.lib.base.crash.CrashToFile
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.exo.mediainfo.ExoMediaInfoProvider
import me.lingci.lib.player.exo.subtitle.Media3SubtitleMapper
import me.lingci.lib.player.capability.PlayerCapabilities
import me.lingci.lib.player.capability.PlayerCapabilityProvider
import me.lingci.lib.player.chapter.ChapterController
import me.lingci.lib.player.chapter.ChapterHelper
import me.lingci.lib.player.chapter.ChapterNode
import me.lingci.lib.player.chapter.ChapterProvider
import me.lingci.lib.player.chapter.OnChapterChangeListener
import xyz.doikki.videoplayer.exo.ExoMediaPlayer
import me.lingci.lib.player.mediainfo.MediaInfoProvider
import me.lingci.lib.player.mediainfo.MediaInfoProviderOwner
import me.lingci.lib.player.subtitle.SubtitleCueGroup
import me.lingci.lib.player.subtitle.SubtitleCueListener
import me.lingci.lib.player.subtitle.SubtitleCueProvider
import me.lingci.lib.player.subtitle.SubtitleMimeTypes
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
import java.util.Locale

/**
 * Exo backend adapter.
 *
 * This class is the only place where Media3 track/cue/media-source types are translated into the
 * dkplayer-java optional interfaces consumed by player-ui and app modules.
 */
@OptIn(UnstableApi::class)
class CustomExoMediaPlayer(context: Context) : ExoMediaPlayer(context),
    PlayerCapabilityProvider,
    MediaTrackProvider,
    MediaTrackController,
    ExternalTrackController,
    ChapterProvider,
    ChapterController,
    MediaInfoProviderOwner,
    SubtitleCueProvider {

    companion object {
        private const val SUBTITLE_TRACE_INPUT_TAG = "SubtitleTraceInput"
        private const val CUE_DIMEN_UNSET = Cue.DIMEN_UNSET
        private const val BACKEND_EXO = "exo"
    }

    private val mContext = context
    private var debugMode = false
    private var isCacheEnabled = false
    private var subtitleCueListener: SubtitleCueListener? = null
    private var latestCueGroup: SubtitleCueGroup? = null
    private var trackSelector: DefaultTrackSelector
    private var pendingSelectSubtitle = false
    private var pendingTextTrackSnapshot: Set<String> = emptySet()
    private var mediaTracksChangedListener: OnMediaTracksChangedListener? = null
    private var subtitleAttachStrategy: SubtitleAttachStrategy = SubtitleAttachStrategy.MERGE_SUBTITLE_SOURCE
    // Exo external audio/subtitle support is implemented by rebuilding a merged media source while
    // preserving playback position. These fields keep the base source separate from attached tracks.
    private var baseMediaSource: MediaSource? = null
    private var externalAudioSource: MediaSource? = null
    private var externalSubtitleSource: MediaSource? = null

    // 章节相关
    private var chapters: List<ChapterNode> = emptyList()
    private var chapterListener: OnChapterChangeListener? = null
    private var currentChapter: ChapterNode? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // #14 selectTextByDefault: true = 优先选择默认字幕轨
        trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setPreferredTextLanguage("zh")
                .setPreferredAudioLanguage("ja") // #13 修正语言代码 "jap" → "ja"
                .setRendererDisabled(C.TRACK_TYPE_TEXT, false) //确保字幕渲染器未禁用
                .setAllowVideoMixedMimeTypeAdaptiveness(true)  // 如果存在，启用视频自适应。 这取决于情况
                .setSelectTextByDefault(true)
                .build()
        }
        setTrackSelector(trackSelector)
        //setRenderersFactory(FFmpegOnlyRenderersFactory(context))
    }

    val internalPlayer: androidx.media3.exoplayer.ExoPlayer?
        get() = mInternalPlayer

    override fun setSubtitleCueListener(listener: SubtitleCueListener?) {
        subtitleCueListener = listener
        if (listener == null) {
            return
        }
        // New listeners should immediately receive the last frame so subtitle UI survives panel or
        // controller reattachment without waiting for the next cue callback.
        latestCueGroup?.let(listener::onSubtitleCues)
    }

    fun setPreferredLanguages(audioLanguage: String?, subtitleLanguage: String?) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(audioLanguage)
            .setPreferredTextLanguage(subtitleLanguage)
            .build()
    }

    fun updatePreferredLanguages(audioLanguage: String, subtitleLanguage: String) {
        val currentParams = trackSelector.parameters
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setPreferredAudioLanguage(
                if (audioLanguage.isNotBlank()) audioLanguage
                else currentParams.preferredAudioLanguages.firstOrNull()
            )
            .setPreferredTextLanguage(
                if (subtitleLanguage.isNotBlank()) subtitleLanguage
                else currentParams.preferredTextLanguages.firstOrNull()
            )
            .build()
    }

    override fun initPlayer() {
        super.initPlayer()
        mInternalPlayer.addListener(
            object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    tracks.groups.forEach {
                        Log.d(this, "addListener", it.isSupported, it.isSelected, it.mediaTrackGroup.getFormat(0).toString())
                    }

                    if (pendingSelectSubtitle) {
                        // 外挂字幕挂载后优先定位新增文本轨，避免“按顺序盲选”导致内封/外挂来回跳。
                        val textTracks = tracks.groups.filter {
                            it.type == C.TRACK_TYPE_TEXT && it.isSupported
                        }
                        val newSubtitleTrack = textTracks.firstOrNull { track ->
                            buildTextTrackKey(track) !in pendingTextTrackSnapshot
                        }
                        val targetSubtitleTrack = newSubtitleTrack ?: textTracks.lastOrNull()
                        if (targetSubtitleTrack != null) {
                            changeTrack(targetSubtitleTrack)
                        }
                        pendingSelectSubtitle = false
                        pendingTextTrackSnapshot = emptySet()
                    }
                    mediaTracksChangedListener?.onMediaTracksChanged(getMediaTracks())
                }

                override fun onCues(cueGroup: CueGroup) {
                    super.onCues(cueGroup)
                    Log.d(this@CustomExoMediaPlayer, "onCues", cueGroup.cues.size)
                    traceSubtitleInput(cueGroup)
                    // Keep Media3 CueGroup inside player-exo. player-ui only sees SubtitleCueGroup.
                    val subtitleCueGroup = Media3SubtitleMapper.toSubtitleCueGroup(cueGroup)
                    latestCueGroup = subtitleCueGroup
                    subtitleCueListener?.onSubtitleCues(subtitleCueGroup)
                }

                override fun onPlayerError(error: PlaybackException) {
                    // #15 StuckPlayerException 自动重试播放
                    if (error.cause is StuckPlayerException) {
                        Log.d(this@CustomExoMediaPlayer, "StuckPlayerException detected, retrying")
                    }
                    super.onPlayerError(error)
                    if (debugMode) {
                        CrashToFile.saveExceptionToFile(mContext, error, "exo")
                    }
                }
            }
        )
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        // Cache selection used to live in CustomVideoView. Keeping it here prevents player-ui from
        // importing Exo media-source helpers just to choose a data source implementation.
        if (isCacheEnabled) {
            mMediaSource = mMediaSourceHelper.getMediaSource(path, headers, true)
        } else {
            super.setDataSource(path, headers)
        }
        baseMediaSource = mMediaSource
        externalAudioSource = null
        externalSubtitleSource = null
    }

    fun setDataSource(dataSource: MediaSource) {
        baseMediaSource = dataSource
        externalAudioSource = null
        externalSubtitleSource = null
        mMediaSource = dataSource
    }

    fun setDebugMode(debug: Boolean) {
        this.debugMode = debug
    }

    fun setCacheEnabled(isCacheEnabled: Boolean) {
        this.isCacheEnabled = isCacheEnabled
    }

    fun getCacheEnabled(): Boolean {
        return isCacheEnabled
    }

    fun useOkhttp(used: Boolean) {
        mMediaSourceHelper.setUseOkhttp(used)
    }

    fun buildDataSource(url: String, headers: Map<String, String>): MediaSource {
        return mMediaSourceHelper.getMediaSource(url, headers, isCacheEnabled)
    }

    fun setSubtitleAttachStrategy(strategy: SubtitleAttachStrategy) {
        subtitleAttachStrategy = strategy
    }

    private fun traceSubtitleInput(cueGroup: CueGroup) {
        if (!debugMode || cueGroup.cues.isEmpty()) {
            return
        }
        Log.d(
            SUBTITLE_TRACE_INPUT_TAG,
            "frameUs=${cueGroup.presentationTimeUs}",
            "cueCount=${cueGroup.cues.size}"
        )
        cueGroup.cues.forEachIndexed { index, cue ->
            Log.d(
                SUBTITLE_TRACE_INPUT_TAG,
                "cue#$index",
                "text=${previewCueText(cue.text)}",
                "line=${formatCueFloat(cue.line)}",
                "lineType=${cue.lineType}",
                "lineAnchor=${cue.lineAnchor}",
                "position=${formatCueFloat(cue.position)}",
                "positionAnchor=${cue.positionAnchor}",
                "size=${formatCueFloat(cue.size)}",
                "textAlignment=${cue.textAlignment}",
                "verticalType=${cue.verticalType}",
                "textSize=${formatCueFloat(cue.textSize)}",
                "textSizeType=${cue.textSizeType}",
                "bitmap=${cue.bitmap != null}",
                "spans=${summarizeCueSpans(cue.text)}",
                "foregroundSpans=${summarizeForegroundColors(cue.text)}",
                "backgroundSpans=${summarizeBackgroundColors(cue.text)}"
            )
        }
    }

    private fun summarizeCueSpans(text: CharSequence?): String {
        val spanned = text as? Spanned ?: return "[]"
        if (spanned.isEmpty()) {
            return "[]"
        }
        val spans = spanned.getSpans(0, spanned.length, Any::class.java)
        if (spans.isEmpty()) {
            return "[]"
        }
        return spans.groupingBy { it.javaClass.simpleName.ifBlank { it.javaClass.name } }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(prefix = "[", postfix = "]") { "${it.key}:${it.value}" }
    }

    private fun previewCueText(text: CharSequence?): String {
        val normalized = text?.toString()?.replace("\n", "\\n")?.replace("\r", "") ?: "<null>"
        return if (normalized.length <= 80) normalized else normalized.take(77) + "..."
    }

    private fun summarizeForegroundColors(text: CharSequence?): String {
        val spanned = text as? Spanned ?: return "[]"
        val spans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
        if (spans.isEmpty()) {
            return "[]"
        }
        return spans.joinToString(prefix = "[", postfix = "]") { formatColor(it.foregroundColor) }
    }

    private fun summarizeBackgroundColors(text: CharSequence?): String {
        val spanned = text as? Spanned ?: return "[]"
        val spans = spanned.getSpans(0, spanned.length, BackgroundColorSpan::class.java)
        if (spans.isEmpty()) {
            return "[]"
        }
        return spans.joinToString(prefix = "[", postfix = "]") { formatColor(it.backgroundColor) }
    }

    private fun formatCueFloat(value: Float): String {
        return if (value == CUE_DIMEN_UNSET) {
            "UNSET"
        } else {
            String.format(Locale.US, "%.3f", value)
        }
    }

    private fun formatColor(color: Int): String {
        return String.format(Locale.US, "#%08X", color)
    }

    override fun setOnMediaTracksChangedListener(listener: OnMediaTracksChangedListener?) {
        mediaTracksChangedListener = listener
        listener?.onMediaTracksChanged(getMediaTracks())
    }

    override fun getMediaTracks(): MediaTrackSnapshot {
        val player = internalPlayer ?: return MediaTrackSnapshot()
        val videoTracks = mutableListOf<MediaTrack>()
        val audioTracks = mutableListOf<MediaTrack>()
        val subtitleTracks = mutableListOf<MediaTrack>()
        // Rebuild a common snapshot from Media3 currentTracks. groupIndex/trackIndex are transient
        // Media3 selection coordinates and must only be used with the current Exo track set.
        player.currentTracks.groups.forEachIndexed { groupIndex, group ->
            val type = group.type.toMediaTrackType()
            for (trackIndex in 0 until group.length) {
                val track = group.toMediaTrack(groupIndex, trackIndex, type)
                when (type) {
                    MediaTrackType.VIDEO -> videoTracks.add(track)
                    MediaTrackType.AUDIO -> audioTracks.add(track)
                    MediaTrackType.SUBTITLE -> subtitleTracks.add(track)
                    MediaTrackType.UNKNOWN -> Unit
                }
            }
        }
        val disabledTypes = player.trackSelectionParameters.disabledTrackTypes
            .mapNotNull { it.toMediaTrackType().takeIf { type -> type != MediaTrackType.UNKNOWN } }
            .toSet()
        return MediaTrackSnapshot(
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            disabledTypes = disabledTypes
        )
    }

    override fun selectTrack(key: MediaTrackKey): Boolean {
        // Only Exo-created keys know how to map back to Media3 track groups.
        if (key.backend != BACKEND_EXO) return false
        val trackType = key.type.toExoTrackType() ?: return false
        val groups = internalPlayer?.currentTracks?.groups ?: return false
        val group = groups.getOrNull(key.groupIndex) ?: return false
        if (group.type != trackType || key.trackIndex !in 0 until group.length || !group.isTrackSupported(key.trackIndex)) {
            return false
        }
        mInternalPlayer.trackSelectionParameters =
            mInternalPlayer.trackSelectionParameters
                .buildUpon()
                // A previous disableTrack call persists in TrackSelectionParameters, so selecting a
                // concrete track must explicitly re-enable that renderer type first.
                .setTrackTypeDisabled(group.type, false)
                .setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, key.trackIndex)
                )
                .build()
        return true
    }

    override fun disableTrack(type: MediaTrackType): Boolean {
        val trackType = type.toExoTrackType() ?: return false
        mInternalPlayer.trackSelectionParameters =
            mInternalPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, true)
                .build()
        return true
    }

    override fun clearTrackOverride(type: MediaTrackType): Boolean {
        val trackType = type.toExoTrackType() ?: return false
        mInternalPlayer.trackSelectionParameters =
            mInternalPlayer.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(trackType)
                .setTrackTypeDisabled(trackType, false)
                .build()
        return true
    }

    override fun setPreferredLanguages(audioLanguages: List<String>, subtitleLanguages: List<String>): Boolean {
        setPreferredLanguages(audioLanguages.firstOrNull(), subtitleLanguages.firstOrNull())
        return true
    }

    private fun changeTrack(trackGroup: Tracks.Group) {
        if (trackGroup.isSupported) {
            Log.d("changeTrack", trackGroup.type, trackGroup.mediaTrackGroup.getFormat(0).toString())
            mInternalPlayer.trackSelectionParameters =
                mInternalPlayer.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(trackGroup.type, false)
                    .setOverrideForType(
                        TrackSelectionOverride(trackGroup.mediaTrackGroup, 0)
                    )
                    .build()
        }
    }

    fun attachExternalSubtitle(uri: Uri, mimeType: String = MimeTypes.APPLICATION_SUBRIP, language: String? = null) {
        // 先记录挂载前的文本轨快照，onTracksChanged 时用来识别“新增的外挂轨道”。
        pendingTextTrackSnapshot = captureCurrentTextTrackSnapshot()
        when (subtitleAttachStrategy) {
            SubtitleAttachStrategy.REBUILD_MEDIA_ITEM -> attachExternalSubtitleByMediaItem(uri, mimeType, language)
            SubtitleAttachStrategy.MERGE_SUBTITLE_SOURCE -> attachExternalSubtitleByMediaSource(uri, mimeType, language)
        }
    }

    private fun attachExternalSubtitleByMediaItem(uri: Uri, mimeType: String, language: String?) {
        // MediaItem rebuild is the most compatible Exo path but replaces the current item, so it has
        // to restore position and play state manually.
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage(language)
            .build()

        val currentMediaItem = mInternalPlayer.getMediaItemAt(0)
        val videoUri = currentMediaItem.localConfiguration?.uri ?: return

        val newMediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setSubtitleConfigurations(listOf(subtitleConfig))
            .build()

        val currentPosition = mInternalPlayer.currentPosition
        val isPlaying = mInternalPlayer.isPlaying

        pendingSelectSubtitle = true

        mInternalPlayer.setMediaItem(newMediaItem)
        mInternalPlayer.seekTo(currentPosition)
        mInternalPlayer.prepare()

        if (isPlaying) {
            mInternalPlayer.play()
        }
    }

    private fun attachExternalSubtitleByMediaSource(uri: Uri, mimeType: String, language: String?) {
        if (baseMediaSource == null) {
            Log.d(this, "attachExternalSubtitleByMediaSource fallback", "baseMediaSource is null")
            // 极端场景下兜底到 MediaItem 方案，保证外挂字幕仍可挂载。
            attachExternalSubtitleByMediaItem(uri, mimeType, language)
            return
        }
        val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage(language)
            .build()
        val currentPosition = mInternalPlayer.currentPosition
        val isPlaying = mInternalPlayer.isPlaying
        val dataSourceFactory = DefaultDataSource.Factory(
            mContext,
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        )
        externalSubtitleSource = SingleSampleMediaSource.Factory(dataSourceFactory)
            .createMediaSource(subtitleConfig, C.TIME_UNSET)
        pendingSelectSubtitle = true
        rebuildMergedSource()?.let { mergedSource ->
            mMediaSource = mergedSource
            mInternalPlayer.setMediaSource(mergedSource, currentPosition)
            mInternalPlayer.prepare()
            if (isPlaying) {
                mInternalPlayer.play()
            }
        }
    }

    fun attachExternalAudio(uri: Uri) {
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .build()
        val dataSourceFactory = DefaultDataSource.Factory(mContext)
        externalAudioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)
        rebuildAndPlayMergedSource()
    }

    override fun addExternalTrack(request: ExternalTrackRequest): Boolean {
        // Translate the generic request into Exo media sources. Subtitle MIME fallback is normalized
        // here so callers do not need Media3 MimeTypes; selectAfterAdd is best-effort and the current
        // subtitle path selects the newly attached track for existing subtitle UI behavior.
        return when (request.type) {
            MediaTrackType.SUBTITLE -> {
                val mimeType = request.mimeType
                    ?: SubtitleMimeTypes.fromPath(request.uri.path)
                    ?: MimeTypes.APPLICATION_SUBRIP
                attachExternalSubtitle(request.uri, mimeType, request.language)
                true
            }
            MediaTrackType.AUDIO -> {
                attachExternalAudio(request.uri)
                true
            }
            else -> false
        }
    }

    private fun rebuildAndPlayMergedSource() {
        val currentPosition = mInternalPlayer.currentPosition
        val isPlaying = mInternalPlayer.isPlaying
        rebuildMergedSource()?.let { mergedSource ->
            mMediaSource = mergedSource
            mInternalPlayer.setMediaSource(mergedSource, currentPosition)
            mInternalPlayer.prepare()
            if (isPlaying) {
                mInternalPlayer.play()
            }
        }
    }

    private fun rebuildMergedSource(): MediaSource? {
        // Source order matters: video first, then external audio/subtitle. Exo merges timelines by
        // index, so rebuilding from the stored parts is safer than mutating the current source.
        val sourceList = mutableListOf<MediaSource>()
        baseMediaSource?.let { sourceList.add(it) }
        externalAudioSource?.let { sourceList.add(it) }
        externalSubtitleSource?.let { sourceList.add(it) }
        if (sourceList.isEmpty()) {
            return null
        }
        return if (sourceList.size == 1) {
            sourceList.first()
        } else {
            MergingMediaSource(*sourceList.toTypedArray())
        }
    }

    // #16 静音 API — media3 1.9.0 新特性，自动保存/恢复音量
    fun mute() {
        mInternalPlayer.mute()
    }

    fun unmute() {
        mInternalPlayer.unmute()
    }

    // ==================== 章节相关 API ====================

    /**
     * 设置章节监听器
     */
    fun setChapterListener(listener: OnChapterChangeListener?) {
        this.chapterListener = listener
    }

    override fun setOnChapterChangeListener(listener: OnChapterChangeListener?) {
        setChapterListener(listener)
    }

    /**
     * 获取当前加载的章节列表
     */
    override fun getChapters(): List<ChapterNode> = chapters

    /**
     * 获取当前播放位置对应的章节
     */
    override fun getCurrentChapter(): ChapterNode? = currentChapter

    /**
     * 跳转到指定章节
     */
    override fun seekToChapter(index: Int): Boolean {
        if (index !in chapters.indices) return false
        val chapter = chapters[index]
        seekTo(chapter.startTimeMs)
        currentChapter = chapter
        chapterListener?.onCurrentChapterChanged(chapter)
        return true
    }

    /**
     * 从指定 URI 提取章节信息
     */
    fun extractChapters(uri: Uri) {
        scope.launch {
            try {
                chapters = ChapterHelper.extractChapters(mContext, uri)
                Log.d(this@CustomExoMediaPlayer, "extractChapters", "Found ${chapters.size} chapters")
                chapterListener?.onChaptersLoaded(chapters)

                if (chapters.isNotEmpty()) {
                    mPlayerEventListener?.onChaptersLoaded(chapters)
                }
            } catch (e: Exception) {
                Log.d(this@CustomExoMediaPlayer, "extractChapters", "Failed to extract chapters", e)
            }
        }
    }

    /**
     * 更新当前章节 (应在播放进度更新时调用)
     */
    fun updateCurrentChapter(positionMs: Long) {
        val newChapter = ChapterHelper.findCurrentChapter(chapters, positionMs)
        if (newChapter != currentChapter) {
            currentChapter = newChapter
            chapterListener?.onCurrentChapterChanged(newChapter)
        }
    }

    /**
     * 获取下一章节
     */
    fun getNextChapter(): ChapterNode? {
        val currentIndex = currentChapter?.index ?: return null
        return chapters.getOrNull(currentIndex + 1)
    }

    /**
     * 获取上一章节
     */
    fun getPreviousChapter(): ChapterNode? {
        val currentIndex = currentChapter?.index ?: return null
        return chapters.getOrNull(currentIndex - 1)
    }

    /**
     * 跳转到下一章节
     */
    override fun seekToNextChapter(): Boolean {
        return getNextChapter()?.let { seekToChapter(it.index) } == true
    }

    /**
     * 跳转到上一章节
     */
    override fun seekToPreviousChapter(): Boolean {
        return getPreviousChapter()?.let { seekToChapter(it.index) } == true
    }

    override fun createMediaInfoProvider(): MediaInfoProvider {
        return ExoMediaInfoProvider(this)
    }

    override fun getMediaInfoProviderName(): String {
        return "ExoPlayer"
    }

    override fun getPlayerCapabilities(): PlayerCapabilities {
        // Exo exposes the richest feature set and can feed generic subtitle cues to player-ui.
        return PlayerCapabilities(
            canListTracks = true,
            canSelectVideoTrack = true,
            canSelectAudioTrack = true,
            canSelectSubtitleTrack = true,
            canDisableAudio = true,
            canDisableSubtitle = true,
            canAddExternalAudio = true,
            canAddExternalSubtitle = true,
            canProvideSubtitleCues = true,
            canRenderSubtitleInternally = true,
            canProvideChapters = true,
            canProvideMediaInfo = true,
            maxPlaybackSpeed = 4f,
            maxLongPressSpeed = 4f,
            requiresSurfaceRenderView = false,
            supportsScreenshot = true
        )
    }

    override fun reset() {
        // A reused Exo instance must not leak cue frames, pending subtitle selection state, or merged
        // external sources from the previous playback item into the next one.
        latestCueGroup = null
        baseMediaSource = null
        externalAudioSource = null
        externalSubtitleSource = null
        pendingSelectSubtitle = false
        pendingTextTrackSnapshot = emptySet()
        super.reset()
    }

    override fun release() {
        // Release also clears app callbacks because BaseVideoView may create a fresh backend later.
        mediaTracksChangedListener = null
        subtitleCueListener = null
        latestCueGroup = null
        baseMediaSource = null
        externalAudioSource = null
        externalSubtitleSource = null
        pendingSelectSubtitle = false
        pendingTextTrackSnapshot = emptySet()
        super.release()
    }

    private fun captureCurrentTextTrackSnapshot(): Set<String> {
        // 使用格式关键字段构造集合快照，降低仅靠 id 匹配导致的误判。
        return mInternalPlayer.currentTracks.groups
            .asSequence()
            .filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            .map { buildTextTrackKey(it) }
            .toSet()
    }

    private fun buildTextTrackKey(trackGroup: Tracks.Group): String {
        val format = trackGroup.getTrackFormat(0)
        return listOf(
            format.id.orEmpty(),
            format.label.orEmpty(),
            format.language.orEmpty(),
            format.sampleMimeType.orEmpty(),
            format.codecs.orEmpty()
        ).joinToString("|")
    }

    private fun Tracks.Group.toMediaTrack(
        groupIndex: Int,
        trackIndex: Int,
        type: MediaTrackType
    ): MediaTrack {
        val format = getTrackFormat(trackIndex)
        // Format.id is optional and can be duplicated. The generated id keeps the UI stable while
        // groupIndex/trackIndex remain the real selection coordinates for Exo.
        val formatId = format.id?.takeIf { it.isNotBlank() } ?: "$groupIndex:$trackIndex"
        val isSupported = isTrackSupported(trackIndex)
        val bitrate = format.bitrate.takeIf { it > 0 }?.toLong() ?: 0L
        return MediaTrack(
            key = MediaTrackKey(
                type = type,
                id = "$groupIndex:$trackIndex:$formatId",
                groupId = formatId,
                groupIndex = groupIndex,
                trackIndex = trackIndex,
                backend = BACKEND_EXO
            ),
            title = format.label ?: type.defaultTitle(trackIndex),
            language = format.language,
            codec = format.codecs,
            mimeType = format.sampleMimeType ?: format.containerMimeType,
            bitrate = bitrate,
            isSelected = isTrackSelected(trackIndex),
            isDefault = format.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
            isForced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
            isExternal = false,
            isSupported = isSupported,
            source = MediaTrackSource.UNKNOWN,
            width = format.width.takeIf { it > 0 } ?: 0,
            height = format.height.takeIf { it > 0 } ?: 0,
            frameRate = format.frameRate.takeIf { it > 0 } ?: 0f,
            rotation = format.rotationDegrees.takeIf { it > 0 } ?: 0,
            sampleRate = format.sampleRate.takeIf { it > 0 } ?: 0,
            channelCount = format.channelCount.takeIf { it > 0 } ?: 0,
            channelLayout = format.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
            extras = mapOf(
                "containerMimeType" to format.containerMimeType.orEmpty(),
                "roleFlags" to format.roleFlags.toString(),
                "selectionFlags" to format.selectionFlags.toString()
            )
        )
    }

    private fun Int.toMediaTrackType(): MediaTrackType {
        return when (this) {
            C.TRACK_TYPE_VIDEO -> MediaTrackType.VIDEO
            C.TRACK_TYPE_AUDIO -> MediaTrackType.AUDIO
            C.TRACK_TYPE_TEXT -> MediaTrackType.SUBTITLE
            else -> MediaTrackType.UNKNOWN
        }
    }

    private fun MediaTrackType.toExoTrackType(): Int? {
        return when (this) {
            MediaTrackType.VIDEO -> C.TRACK_TYPE_VIDEO
            MediaTrackType.AUDIO -> C.TRACK_TYPE_AUDIO
            MediaTrackType.SUBTITLE -> C.TRACK_TYPE_TEXT
            MediaTrackType.UNKNOWN -> null
        }
    }

    private fun MediaTrackType.defaultTitle(trackIndex: Int): String {
        return when (this) {
            MediaTrackType.VIDEO -> "Video Track $trackIndex"
            MediaTrackType.AUDIO -> "Audio Track $trackIndex"
            MediaTrackType.SUBTITLE -> "Subtitle Track $trackIndex"
            MediaTrackType.UNKNOWN -> "Track $trackIndex"
        }
    }

}
