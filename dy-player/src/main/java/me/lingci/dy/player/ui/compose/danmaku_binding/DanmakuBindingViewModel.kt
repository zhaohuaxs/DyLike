package me.lingci.dy.player.ui.compose.danmaku_binding

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.AppMeta
import me.lingci.dy.player.ui.danmaku_binding.DanmakuBindingMatcher
import me.lingci.dy.player.ui.danmaku_binding.DanmakuBindingMediaItem
import me.lingci.dy.player.ui.danmaku_binding.bindingCopy
import me.lingci.dy.player.ui.danmaku_binding.bindingKey
import me.lingci.dy.player.ui.danmaku_binding.displayNameWithoutExtension
import me.lingci.dy.player.ui.danmaku_binding.isArchiveFile
import me.lingci.dy.player.ui.danmaku_binding.isUsableBindingTrack
import me.lingci.dy.player.ui.danmaku_binding.normalizeBindingPath
import me.lingci.dy.player.ui.danmaku_binding.removeExtensionForDisplay
import me.lingci.dy.player.ui.danmaku_binding.samePath
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.ui.long_video.PlayInfo
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.dm.view.entity.DmTrack
import me.lingci.lib.dm.view.util.ZipXmlLoader
import java.io.File

/** 媒体行的绑定提示：只描述状态，具体文案由 UI 用 stringResource 拼。 */
@Immutable
sealed interface DanmakuMatchHint {
    @Immutable data object Bound : DanmakuMatchHint
    @Immutable data class UnresolvedArchive(val count: Int) : DanmakuMatchHint
    @Immutable data object Unbound : DanmakuMatchHint
    @Immutable data object Unmatched : DanmakuMatchHint
    @Immutable data class PrimarySwitched(val title: String) : DanmakuMatchHint
    @Immutable data class ManualBound(val title: String) : DanmakuMatchHint
    @Immutable data class AutoMatched(val title: String) : DanmakuMatchHint
    @Immutable data class Scored(val episodeSame: Boolean?, val score: Int) : DanmakuMatchHint
}

@Immutable
data class DanmakuTrackUiModel(
    val key: String,
    val title: String,
    val isPrimary: Boolean,
    val unresolvedArchive: Boolean
)

@Immutable
data class DanmakuMediaUiModel(
    val sourceIndex: Int,
    val key: String,
    val title: String,
    val boundCount: Int,
    val primaryTitle: String?,
    val hint: DanmakuMatchHint,
    val hasChanges: Boolean,
    val selected: Boolean,
    val tracks: List<DanmakuTrackUiModel>
)

/** 弹幕文件的归属：无主 / 归属当前媒体 / 归属其它媒体。 */
@Immutable
sealed interface DanmakuFileOwner {
    @Immutable data object None : DanmakuFileOwner
    @Immutable data object Current : DanmakuFileOwner
    @Immutable data class Other(val mediaIndex: Int, val title: String) : DanmakuFileOwner
}

@Immutable
data class DanmakuFileUiModel(
    val sourceIndex: Int,
    val key: String,
    val title: String,
    val score: Int,
    val isArchive: Boolean,
    val owner: DanmakuFileOwner,
    val isPrimaryForCurrent: Boolean
)

@Immutable
data class DanmakuBindingSummary(
    val totalMedia: Int = 0,
    val boundMedia: Int = 0,
    val trackCount: Int = 0,
    val changedCount: Int = 0
)

enum class DanmakuBindingPhase { Loading, Content, Empty, Error }

@Immutable
data class DanmakuBindingUiState(
    val phase: DanmakuBindingPhase = DanmakuBindingPhase.Loading,
    val media: List<DanmakuMediaUiModel> = emptyList(),
    val files: List<DanmakuFileUiModel> = emptyList(),
    val query: String = "",
    val folderPath: String = "",
    val summary: DanmakuBindingSummary = DanmakuBindingSummary(),
    val selectedIndex: Int = -1
)

/** 一次性提示：ViewModel 不持有资源 id，只表达「哪条提示 + 参数」。 */
@Immutable
sealed interface DanmakuBindingMessage {
    @Immutable data class FolderLoaded(val count: Int) : DanmakuBindingMessage
    @Immutable data object NothingToClear : DanmakuBindingMessage
    @Immutable data object ClearedAll : DanmakuBindingMessage
    @Immutable data object Reset : DanmakuBindingMessage
    @Immutable data object ArchiveUnsupported : DanmakuBindingMessage
    @Immutable data object NoBoundTracks : DanmakuBindingMessage
    @Immutable data class OwnerInfo(val title: String) : DanmakuBindingMessage
    @Immutable data class Saved(val count: Int) : DanmakuBindingMessage
    @Immutable data class SaveFailed(val count: Int) : DanmakuBindingMessage
    @Immutable data object NoChanges : DanmakuBindingMessage
}

sealed interface DanmakuBindingEvent {
    data class Message(val message: DanmakuBindingMessage) : DanmakuBindingEvent

    /** 保存流程结束（成功、失败或无事可做），退出拦截据此放行返回。 */
    data object SaveFinished : DanmakuBindingEvent
}

/**
 * 本地弹幕绑定页 ViewModel。
 *
 * 内部沿用旧页面的业务模型 [DanmakuBindingMediaItem]（[VideoData]/[PlayInfo]/[DmTrack] 都是
 * 可变实体，不能直接进 Compose 参数），对外只暴露 [DanmakuMediaUiModel]/[DanmakuFileUiModel]。
 * 绑定结果仍落在每个视频的 PlayInfo JSON 里，与旧页面完全一致。
 */
class DanmakuBindingViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()

    /** 内部工作副本：复用旧模型的方法（hasChanges/primaryTrack/isPrimary），避免重写绑定判重逻辑。 */
    private class WorkingMedia(
        val item: DanmakuBindingMediaItem,
        var hint: DanmakuMatchHint
    )

    private val mediaItems = mutableListOf<WorkingMedia>()
    private val danmakuFiles = mutableListOf<FileEntity>()
    private var query = ""
    private var selectedIndex = -1
    private var folderPath = ""
    private var mediaId = ""
    private var loaded = false

    private val _state = MutableStateFlow(DanmakuBindingUiState())
    val state: StateFlow<DanmakuBindingUiState> = _state.asStateFlow()

    private val _events = Channel<DanmakuBindingEvent>(Channel.BUFFERED)
    val events: Flow<DanmakuBindingEvent> = _events.receiveAsFlow()

    fun load(id: String, tempFilePath: String) {
        if (loaded) return
        loaded = true
        mediaId = id
        viewModelScope.launch {
            try {
                val working = withContext(Dispatchers.IO) {
                    val videos = DanmakuBindingTransfer.readTempVideos(tempFilePath)
                    videos.map { video ->
                        val info = PlayHelper.loadInfoSync(context, video)
                        val tracks = info?.dmTrack.orEmpty()
                            .filter { it.isUsableBindingTrack() }
                            .map { it.bindingCopy() }
                            .toMutableList()
                        // 主弹幕使用条目级 key，避免同一压缩包内不同条目互相覆盖
                        val lastTrackKey = info?.lastDmTrack?.bindingKey().orEmpty()
                        val unresolvedCount = tracks.count { it.isUnresolvedLegacyArchive() }
                        WorkingMedia(
                            item = DanmakuBindingMediaItem(
                                videoData = video,
                                originalInfo = info,
                                originalTracks = tracks.map { it.bindingCopy() }.toMutableList(),
                                originalLastTrackKey = lastTrackKey,
                                workingTracks = tracks,
                                lastTrackKey = lastTrackKey
                            ),
                            hint = when {
                                unresolvedCount > 0 -> DanmakuMatchHint.UnresolvedArchive(unresolvedCount)
                                tracks.isEmpty() -> DanmakuMatchHint.Unbound
                                else -> DanmakuMatchHint.Bound
                            }
                        )
                    }
                }
                val cachedFolder = withContext(Dispatchers.IO) { loadFolderFromMeta() }
                val cachedFiles = withContext(Dispatchers.IO) {
                    if (cachedFolder.isNotBlank() && File(cachedFolder).isDirectory) {
                        scanFiles(File(cachedFolder))
                    } else {
                        emptyList()
                    }
                }
                mediaItems.clear()
                mediaItems.addAll(working)
                if (mediaItems.isNotEmpty()) {
                    selectedIndex = 0
                    mediaItems[0].item.selected = true
                }
                if (cachedFiles.isNotEmpty()) {
                    folderPath = cachedFolder
                    danmakuFiles.clear()
                    danmakuFiles.addAll(cachedFiles)
                }
                publish()
            } catch (e: Exception) {
                _state.value = DanmakuBindingUiState(phase = DanmakuBindingPhase.Error)
            }
        }
    }

    fun setQuery(value: String) {
        val next = value.trim()
        if (next == query) return
        query = next
        publish()
    }

    fun selectMedia(index: Int) {
        if (index !in mediaItems.indices) return
        selectedIndex = index
        mediaItems.forEachIndexed { i, media -> media.item.selected = i == index }
        publish()
    }

    fun chooseFolder(path: String) {
        if (path.isBlank()) return
        folderPath = path
        saveFolderToMeta(path)
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) { scanFiles(File(path)) }
            danmakuFiles.clear()
            danmakuFiles.addAll(files)
            publish()
            emitMessage(DanmakuBindingMessage.FolderLoaded(files.size))
        }
    }

    /** 点击右侧文件：无主则绑定；已归属当前媒体则切主弹幕。改绑其它媒体由 UI 先确认。 */
    fun bindFile(sourceIndex: Int) {
        val media = mediaItems.getOrNull(selectedIndex) ?: return
        val file = danmakuFiles.getOrNull(sourceIndex) ?: return
        if (file.isArchiveFile()) {
            emitMessage(DanmakuBindingMessage.ArchiveUnsupported)
            return
        }
        val key = file.bindingKey()
        if (media.item.hasTrack(key)) {
            setPrimary(selectedIndex, key)
            return
        }
        if (mediaItems.any { it.item.hasTrack(key) }) return
        attach(media, file)
    }

    /** 已确认改绑：先从原归属媒体移除，再挂到当前选中媒体。 */
    fun rebindFile(sourceIndex: Int) {
        val media = mediaItems.getOrNull(selectedIndex) ?: return
        val file = danmakuFiles.getOrNull(sourceIndex) ?: return
        if (file.isArchiveFile()) {
            emitMessage(DanmakuBindingMessage.ArchiveUnsupported)
            return
        }
        val key = file.bindingKey()
        val ownerIndex = mediaItems.indexOfFirst { it.item.hasTrack(key) }
        if (ownerIndex >= 0 && ownerIndex != selectedIndex) {
            val owner = mediaItems[ownerIndex]
            owner.item.workingTracks.removeAll { it.bindingKey() == key }
            if (owner.item.lastTrackKey == key) {
                owner.item.lastTrackKey = owner.item.primaryTrack()?.bindingKey().orEmpty()
            }
            owner.hint = if (owner.item.workingTracks.isEmpty()) {
                DanmakuMatchHint.Unbound
            } else {
                DanmakuMatchHint.Bound
            }
        }
        attach(media, file)
    }

    fun setPrimary(mediaIndex: Int, trackKey: String) {
        val media = mediaItems.getOrNull(mediaIndex) ?: return
        media.item.lastTrackKey = trackKey
        media.item.workingTracks = media.item.workingTracks.map { track ->
            track.bindingCopy().copy(selected = track.bindingKey() == trackKey)
        }.toMutableList()
        val title = media.item.workingTracks
            .firstOrNull { it.bindingKey() == trackKey }
            ?.displayNameWithoutExtension()
            .orEmpty()
        media.hint = DanmakuMatchHint.PrimarySwitched(title)
        publish()
    }

    fun removeTrack(mediaIndex: Int, trackKey: String) {
        val media = mediaItems.getOrNull(mediaIndex) ?: return
        media.item.workingTracks.removeAll { it.bindingKey() == trackKey }
        if (media.item.lastTrackKey == trackKey) {
            media.item.lastTrackKey = media.item.primaryTrack()?.bindingKey().orEmpty()
        }
        media.hint = if (media.item.workingTracks.isEmpty()) {
            DanmakuMatchHint.Unbound
        } else {
            DanmakuMatchHint.Bound
        }
        publish()
    }

    fun showBoundTracks(index: Int) {
        val media = mediaItems.getOrNull(index) ?: return
        if (media.item.workingTracks.isEmpty()) {
            emitMessage(DanmakuBindingMessage.NoBoundTracks)
        }
    }

    fun notifyOwnerInfo(ownerTitle: String) {
        emitMessage(DanmakuBindingMessage.OwnerInfo(ownerTitle))
    }

    fun notifyArchiveUnsupported() {
        emitMessage(DanmakuBindingMessage.ArchiveUnsupported)
    }

    fun notifyNothingToClear() {
        emitMessage(DanmakuBindingMessage.NothingToClear)
    }

    fun clearCurrent() {
        val media = mediaItems.getOrNull(selectedIndex) ?: return
        media.item.workingTracks.clear()
        media.item.lastTrackKey = ""
        media.hint = DanmakuMatchHint.Unbound
        publish()
    }

    fun clearAll() {
        mediaItems.forEach { media ->
            media.item.workingTracks.clear()
            media.item.lastTrackKey = ""
            media.hint = DanmakuMatchHint.Unbound
        }
        publish()
        emitMessage(DanmakuBindingMessage.ClearedAll)
    }

    fun requestClearAll() {
        if (mediaItems.none { it.item.workingTracks.isNotEmpty() }) {
            emitMessage(DanmakuBindingMessage.NothingToClear)
        } else {
            clearAll()
        }
    }

    /** 还原选择：丢弃所有未保存改动并清空弹幕目录，对齐旧页面的「排序」菜单语义。 */
    fun resetMatches() {
        query = ""
        danmakuFiles.clear()
        folderPath = ""
        mediaItems.forEach { media ->
            media.item.workingTracks = media.item.originalTracks.map { it.bindingCopy() }.toMutableList()
            media.item.lastTrackKey = media.item.originalLastTrackKey
            media.hint = if (media.item.workingTracks.isEmpty()) {
                DanmakuMatchHint.Unbound
            } else {
                DanmakuMatchHint.Bound
            }
        }
        publish()
        emitMessage(DanmakuBindingMessage.Reset)
    }

    fun hasUnsavedChanges(): Boolean = mediaItems.any { it.item.hasChanges() }

    /** 唯一保存入口：手动保存与退出保存都走这里，避免三处写盘互相覆盖。 */
    fun save() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var changed = 0
                var failed = 0
                mediaItems.forEach { media ->
                    if (!media.item.hasChanges()) return@forEach
                    val primaryKey = media.item.primaryTrack()?.bindingKey().orEmpty()
                    val sanitized = media.item.workingTracks.map { track ->
                        track.bindingCopy().copy(selected = track.bindingKey() == primaryKey)
                    }.toMutableList()
                    val primaryTrack = sanitized.firstOrNull { it.bindingKey() == primaryKey }
                        ?: sanitized.firstOrNull()
                    val info = (media.item.originalInfo ?: PlayInfo()).copy(
                        dmTrack = sanitized,
                        lastDmTrack = primaryTrack
                    )
                    val ok = PlayHelper.saveInfoSync(context, media.item.videoData, info)
                    if (ok) {
                        changed++
                        media.item.originalInfo = info
                        media.item.originalTracks = sanitized.map { it.bindingCopy() }.toMutableList()
                        media.item.lastTrackKey = primaryKey
                        media.item.originalLastTrackKey = primaryKey
                    } else {
                        failed++
                    }
                }
                changed to failed
            }
            publish()
            val (changed, failed) = result
            when {
                failed > 0 -> emitMessage(DanmakuBindingMessage.SaveFailed(failed))
                changed > 0 -> emitMessage(DanmakuBindingMessage.Saved(changed))
                else -> emitMessage(DanmakuBindingMessage.NoChanges)
            }
            _events.send(DanmakuBindingEvent.SaveFinished)
        }
    }

    private fun attach(media: WorkingMedia, file: FileEntity) {
        val track = file.toDmTrack()
        media.item.workingTracks.forEach { it.selected = false }
        media.item.workingTracks.add(track)
        media.item.lastTrackKey = track.bindingKey()
        media.hint = DanmakuMatchHint.ManualBound(file.displayNameWithoutExtension())
        publish()
    }

    private fun matchesMediaQuery(media: WorkingMedia): Boolean {
        if (query.isBlank()) return true
        val videoName = media.item.videoData.name
        if (videoName.contains(query, ignoreCase = true)) return true
        // 主弹幕标题也参与搜索：用户经常按弹幕文件名找对应视频
        val primaryTitle = media.item.primaryTrack()?.displayNameWithoutExtension().orEmpty()
        return primaryTitle.contains(query, ignoreCase = true)
    }

    private fun publish() {
        val mediaUi = mediaItems.mapIndexedNotNull { index, media ->
            if (matchesMediaQuery(media)) buildMediaUi(index, media) else null
        }
        val summary = DanmakuBindingSummary(
            totalMedia = mediaItems.size,
            boundMedia = mediaItems.count { it.item.workingTracks.isNotEmpty() },
            trackCount = mediaItems.sumOf { it.item.workingTracks.size },
            changedCount = mediaItems.count { it.item.hasChanges() }
        )
        _state.value = DanmakuBindingUiState(
            phase = if (mediaItems.isEmpty()) DanmakuBindingPhase.Empty else DanmakuBindingPhase.Content,
            media = mediaUi,
            files = buildFileUi(),
            query = query,
            folderPath = folderPath,
            summary = summary,
            selectedIndex = selectedIndex
        )
    }

    private fun buildMediaUi(index: Int, media: WorkingMedia): DanmakuMediaUiModel {
        val item = media.item
        return DanmakuMediaUiModel(
            sourceIndex = index,
            key = item.videoData.id.ifBlank { item.videoData.videoUrl },
            title = item.videoData.name,
            boundCount = item.workingTracks.size,
            primaryTitle = item.primaryTrack()?.displayNameWithoutExtension(),
            hint = media.hint,
            hasChanges = item.hasChanges(),
            selected = item.selected,
            tracks = item.workingTracks.map { track ->
                DanmakuTrackUiModel(
                    key = track.bindingKey(),
                    title = track.displayNameWithoutExtension(),
                    isPrimary = item.isPrimary(track.bindingKey()),
                    unresolvedArchive = track.isUnresolvedLegacyArchive()
                )
            }
        )
    }

    private fun buildFileUi(): List<DanmakuFileUiModel> {
        val selected = mediaItems.getOrNull(selectedIndex)
        val candidates = selected?.let {
            DanmakuBindingMatcher.match(it.item.videoData, danmakuFiles)
        }.orEmpty()
        val candidateMap = candidates.associateBy { it.file.path.normalizeBindingPath() }
        return danmakuFiles.filter { file ->
            query.isBlank() ||
                file.name.contains(query, ignoreCase = true) ||
                selected?.item?.videoData?.name?.contains(query, ignoreCase = true) == true
        }.map { file ->
            val key = file.bindingKey()
            val ownerIndex = mediaItems.indexOfFirst { it.item.hasTrack(key) }
            val owner = mediaItems.getOrNull(ownerIndex)
            val candidate = candidateMap[file.path.normalizeBindingPath()]
            val ownerUi = if (owner == null) {
                DanmakuFileOwner.None
            } else if (ownerIndex == selectedIndex) {
                DanmakuFileOwner.Current
            } else {
                DanmakuFileOwner.Other(
                    mediaIndex = ownerIndex,
                    title = owner.item.videoData.name.removeExtensionForDisplay()
                )
            }
            DanmakuFileUiModel(
                sourceIndex = danmakuFiles.indexOfFirst { it.path.samePath(file.path) },
                key = key,
                title = file.displayNameWithoutExtension(),
                score = candidate?.score ?: 0,
                isArchive = file.isArchiveFile(),
                owner = ownerUi,
                isPrimaryForCurrent = owner != null &&
                    ownerIndex == selectedIndex &&
                    owner.item.isPrimary(key)
            )
        }.sortedWith(
            compareByDescending<DanmakuFileUiModel> { it.owner is DanmakuFileOwner.Current }
                .thenByDescending { it.score }
                .thenBy { it.title }
        )
    }

    private fun scanFiles(folder: File): List<FileEntity> {
        return FileOperator.getSortedFiles(folder, FileOperator.DM_EXTENSIONS).map {
            FileEntity(
                title = it.name,
                name = it.name,
                path = it.path,
                // 压缩包统一标记 zip/7z/rar，绑定页会禁止整包绑定
                mimeType = ZipXmlLoader.archiveMimeType(it.path)
            )
        }
    }

    private fun saveFolderToMeta(path: String) {
        if (mediaId.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // MediaData.dmFolder 为 @Ignore 字段不落库，改用 app_meta 键值对按媒体 id 持久化
                DatabaseManager.appMetaDao.put(AppMeta(key = "dm_folder|$mediaId", value = path))
            }
        }
    }

    private suspend fun loadFolderFromMeta(): String {
        if (mediaId.isBlank()) return ""
        return runCatching { DatabaseManager.appMetaDao.get("dm_folder|$mediaId").orEmpty() }
            .getOrDefault("")
    }

    private fun emitMessage(message: DanmakuBindingMessage) {
        _events.trySend(DanmakuBindingEvent.Message(message))
    }

    private fun FileEntity.toDmTrack(): DmTrack {
        return DmTrack(
            title = name.ifBlank { title },
            path = path,
            mineType = mimeType,
            selected = true,
            checked = false,
            showAction = false
        )
    }
}
