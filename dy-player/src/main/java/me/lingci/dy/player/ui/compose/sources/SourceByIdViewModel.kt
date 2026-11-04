package me.lingci.dy.player.ui.compose.sources

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.room.DatabaseManager

/** [loading] 与 [source] 分开：加载中不能当成「资源库已删除」，否则会先闪一帧错误态。 */
@Immutable
data class SourceLookupUiState(
    val loading: Boolean = true,
    val source: SourceData? = null
)

/**
 * 按 id 取单个资源库（浏览页入口用）。
 *
 * 只查库内记录：内置源（本地/外部/串流）不落库，由调用方用 id 现场构造，
 * 所以这里查不到不等于「已删除」，是否回退内置源由 Route 决定。
 */
class SourceByIdViewModel : ViewModel() {

    private val _state = MutableStateFlow(SourceLookupUiState())
    val state: StateFlow<SourceLookupUiState> = _state.asStateFlow()

    private var loadedId: String? = null

    fun load(sourceId: String) {
        if (loadedId == sourceId) return
        loadedId = sourceId
        viewModelScope.launch {
            val source = withContext(Dispatchers.IO) {
                DatabaseManager.sourceDataDao.getById(sourceId)
            }
            _state.value = SourceLookupUiState(loading = false, source = source)
        }
    }
}
