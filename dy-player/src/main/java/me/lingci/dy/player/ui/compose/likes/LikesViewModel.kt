package me.lingci.dy.player.ui.compose.likes

import android.app.Application
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
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.decodeHtmlEntities

enum class LikesPhase { Loading, Content, Empty }

/**
 * 「我的喜欢」里的一张卡。
 *
 * [thumbPath] 已在 ViewModel 的 IO 段解析好：缩略图文件存在就用它，否则退回 `preview` 地址。
 * 之前这段判断写在 Composable 里，等于每次重组都做一次 `File.exists()`。
 */
@Immutable
data class LikeItemUi(
    /** 列表稳定 key，同时是缩略图文件名（VideoData.md5()） */
    val key: String,
    val title: String,
    val thumbPath: String?
)

@Immutable
data class LikesUiState(
    val phase: LikesPhase = LikesPhase.Loading,
    val items: List<LikeItemUi> = emptyList()
)

/**
 * 起播请求。
 *
 * 播放层契约是 `startPlayback(List<VideoData>)`，所以 [VideoData] 只能以一次性事件的形态出现，
 * 不进 [LikesUiState] —— 与历史页 `HistoryEvent.PlaySingle` 同一套做法。
 */
sealed interface LikesEvent {
    data class Play(val videos: List<VideoData>, val index: Int) : LikesEvent
}

class LikesViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(LikesUiState())
    val state: StateFlow<LikesUiState> = _state.asStateFlow()

    private val _events = Channel<LikesEvent>(Channel.BUFFERED)
    val events: Flow<LikesEvent> = _events.receiveAsFlow()

    /** 起播用的原始列表；只在这里流转，不暴露给 UI。 */
    private var videos: List<VideoData> = emptyList()

    init {
        viewModelScope.launch {
            DatabaseManager.favoriteDao.observeFavoriteVideos().collect { list ->
                videos = list
                val items = withContext(Dispatchers.IO) { list.map { it.toItemUi() } }
                _state.value = LikesUiState(
                    phase = if (items.isEmpty()) LikesPhase.Empty else LikesPhase.Content,
                    items = items
                )
            }
        }
    }

    /** 点某张卡起播：下标在 UI 侧产出，整份列表由这里补齐。 */
    fun play(index: Int) {
        val list = videos
        if (index !in list.indices) return
        viewModelScope.launch { _events.send(LikesEvent.Play(list, index)) }
    }

    private fun VideoData.toItemUi(): LikeItemUi {
        val thumb = AppUtil.buildThumbFile(getApplication(), md5())
        val path = when {
            thumb.exists() -> thumb.absolutePath
            preview.isNotBlank() -> preview
            else -> null
        }
        return LikeItemUi(
            key = md5(),
            title = name.decodeHtmlEntities(),
            thumbPath = path
        )
    }
}
