package me.lingci.dy.player.ui.compose.likes

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager

enum class LikesPhase { Loading, Content, Empty }

/**
 * 「我的喜欢」状态。
 *
 * [videos] 用 Room 实体承载：起播接口 `startPlayback(List<VideoData>)` 与卡片组件
 * 都是这个契约（规范 §11 P2.1 的存量整改项），这一步只把查询从 Composable 收回来。
 */
@Immutable
data class LikesUiState(
    val phase: LikesPhase = LikesPhase.Loading,
    val videos: List<VideoData> = emptyList()
)

class LikesViewModel : ViewModel() {

    val state: StateFlow<LikesUiState> = DatabaseManager.favoriteDao.observeFavoriteVideos()
        .map { videos ->
            LikesUiState(
                phase = if (videos.isEmpty()) LikesPhase.Empty else LikesPhase.Content,
                videos = videos
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LikesUiState()
        )
}
