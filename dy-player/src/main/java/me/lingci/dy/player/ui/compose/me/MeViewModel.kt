package me.lingci.dy.player.ui.compose.me

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import java.util.Calendar

/**
 * 「我的」页统计。
 *
 * 计数字段可空：null 表示**尚未查询完成**，UI 显示占位符，不能用 0 冒充
 * （0 是「确实没有数据」，语义不同，规范 §2.2）。
 *
 * 开关类偏好不在这个状态里，统一由 `SettingsViewModel` 持有（与「设置」页共享）。
 */
@Immutable
data class MeUiState(
    val mediaCount: Int? = null,
    val sourceCount: Int? = null,
    val monthPlayCount: Int? = null
)

/**
 * 「我的」页 ViewModel：只负责页面统计，Composable 不再直连 DAO。
 *
 * 三项统计来自 Room 的响应式计数，媒体增删、资源增删、播放进度更新后自动刷新。
 */
class MeViewModel(app: Application) : AndroidViewModel(app) {

    /** 本月起点：构造时计算一次；跨月极少数场景以进程重启为准，避免每次重组重算 */
    private val monthStart: Long = startOfMonth()

    /** 内置源（本地/外部/串流）不落库，统计时要与库内源相加，才和资源库页面一致 */
    private val builtinSourceCount = LibraryCompat.builtinSourceCount(app)

    private val counts = combine(
        DatabaseManager.mediaDataDao.observeCount(),
        DatabaseManager.sourceDataDao.observeCount(),
        DatabaseManager.playHistoryDao.observeCountSince(monthStart)
    ) { media, source, month -> Triple(media, source + builtinSourceCount, month) }

    val state: StateFlow<MeUiState> = counts
        .map { MeUiState(mediaCount = it.first, sourceCount = it.second, monthPlayCount = it.third) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState())

    private fun startOfMonth(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
