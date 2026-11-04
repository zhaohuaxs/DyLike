package me.lingci.dy.player.ui.compose.media_add

import android.app.Application
import androidx.annotation.StringRes
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.media.MediaHelper
import me.lingci.dy.player.util.decodeHtmlEntities

/** 「在线媒体库」表单状态：只有渲染需要的字段，实体留在 ViewModel 里。 */
@Immutable
data class MediaLinkUiState(
    val isEdit: Boolean = false,
    val linkText: String = "",
    val title: String = "",
    val playType: String = "",
    val coverPath: String? = null,
    val itemNames: List<String> = emptyList(),
    val parsing: Boolean = false,
    /** 编辑态还在读库：此时 [MediaData] 尚未到位，先把保存按钮关掉 */
    val loading: Boolean = false
) {
    /** 编辑态哪怕一集都没有也允许保存（用户可能就是来清空分集的） */
    val canSave: Boolean get() = !loading && (isEdit || itemNames.isNotEmpty())
}

sealed interface MediaLinkEvent {
    data class Message(@param:StringRes val textRes: Int) : MediaLinkEvent
}

/**
 * 在线媒体库表单。
 *
 * 以前这个 Sheet 自己做三件事：`LaunchedEffect` 里查分集、按钮 onClick 里同步解析链接
 * （主线程跑正则 + 逐行切分）、保存时直接改实体。现在三件事都在这里：
 * - 编辑态一次性读库，读的是「分集」而不是整份媒体实体；
 * - 解析放到 IO，失败给一次性提示；
 * - [MediaData] 只在 ViewModel 内部流转，Composable 拿不到实体，也就没法在渲染里改数据。
 */
class MediaLinkAddViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MediaLinkUiState())
    val state: StateFlow<MediaLinkUiState> = _state.asStateFlow()

    private val _events = Channel<MediaLinkEvent>(Channel.BUFFERED)
    val events: Flow<MediaLinkEvent> = _events.receiveAsFlow()

    /** 待保存的实体。解析/编辑都只改这里，界面看到的是 [MediaLinkUiState] 的投影 */
    private var working: MediaData? = null
    private var videos: List<VideoData> = emptyList()

    /**
     * 打开表单：`mediaId == null` 为新增。
     *
     * 每次打开都重置并重读——ViewModel 挂在 Activity 上，关掉表单不会销毁它，
     * 若沿用上一轮的分集列表，重新打开会看到「上次删掉的条目又回来了」。
     */
    fun start(mediaId: String?) {
        working = null
        videos = emptyList()
        _state.value = MediaLinkUiState(isEdit = mediaId != null, loading = mediaId != null)
        if (mediaId == null) return

        viewModelScope.launch {
            val (media, items) = withContext(Dispatchers.IO) {
                DatabaseManager.mediaDataDao.getById(mediaId) to
                    DatabaseManager.videoDataDao.listByMedia(mediaId)
            }
            working = media
            videos = items
            _state.update {
                it.copy(
                    loading = false,
                    title = media?.title.orEmpty(),
                    playType = media?.playType.orEmpty(),
                    coverPath = media?.showFile?.takeIf { path -> path.isNotBlank() },
                    itemNames = items.map { video -> video.name.decodeHtmlEntities() }
                )
            }
        }
    }

    fun onLinkChange(text: String) = _state.update { it.copy(linkText = text) }

    /** 解析输入框里的链接列表；失败不改动当前表单，只提示 */
    fun parse() {
        val text = _state.value.linkText
        if (text.isBlank()) {
            send(MediaLinkEvent.Message(R.string.hint_media_link_empty))
            return
        }
        if (_state.value.parsing) return
        viewModelScope.launch {
            _state.update { it.copy(parsing = true) }
            val parsed = withContext(Dispatchers.IO) {
                runCatching { MediaHelper.createMediaFromString(text) }.getOrNull()
            }
            _state.update { it.copy(parsing = false) }
            if (parsed == null || parsed.items.isEmpty()) {
                send(MediaLinkEvent.Message(R.string.hint_media_link_unparsed))
                return@launch
            }
            working = parsed
            videos = parsed.items
            _state.update {
                it.copy(
                    title = parsed.title,
                    playType = parsed.playType,
                    coverPath = parsed.showFile.takeIf { path -> path.isNotBlank() },
                    itemNames = parsed.items.map { video -> video.name.decodeHtmlEntities() }
                )
            }
        }
    }

    fun removeAt(index: Int) {
        if (index !in videos.indices) return
        videos = videos.toMutableList().also { it.removeAt(index) }
        _state.update { it.copy(itemNames = videos.map { video -> video.name.decodeHtmlEntities() }) }
    }

    fun clearAll() {
        videos = emptyList()
        _state.update { it.copy(itemNames = emptyList()) }
    }

    /**
     * 保存：分集回填实体后同步返回，由界面交给宿主落库。
     *
     * 不走事件通道——表单保存后立刻关闭，收集方随组合消失，事件会滞留到下次打开。
     */
    fun save(): MediaData? {
        val media = working ?: return null
        media.items = videos.toMutableList()
        return media
    }

    private fun send(event: MediaLinkEvent) {
        viewModelScope.launch { _events.send(event) }
    }
}
