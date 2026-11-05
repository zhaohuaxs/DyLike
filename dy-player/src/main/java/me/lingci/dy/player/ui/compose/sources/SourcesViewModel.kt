package me.lingci.dy.player.ui.compose.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.storage.entity.StorageType

/**
 * 资源库页状态。
 *
 * 列表恒有内置源（本地存储 / 串流播放），因此没有 Empty 分支：
 * 真正的空态只可能出现在内置源也构造不出来时，那种情况按 Error 处理更准确。
 */
sealed interface SourcesUiState {
    data object Loading : SourcesUiState
    data class Content(val sources: List<SourceData>) : SourcesUiState
    data class Error(val message: String) : SourcesUiState
}

/**
 * 资源库页（WebDAV / SMB 管理）的 ViewModel。
 *
 * 内置源与库内源在同一条 Flow 里拼装，保证「列表内容」与「我的页统计」同源
 * （统计走 LibraryCompat.builtinSourceCount + observeCount）。
 */
class SourcesViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<SourcesUiState>(SourcesUiState.Loading)
    val uiState: StateFlow<SourcesUiState> = _uiState.asStateFlow()

    private val builtinSources: List<SourceData> by lazy { buildBuiltinSources() }

    init {
        viewModelScope.launch {
            runCatching {
                DatabaseManager.sourceDataDao.observeAll().collect { custom ->
                    _uiState.value = SourcesUiState.Content(builtinSources + custom.sortedBy { it.index })
                }
            }.onFailure {
                _uiState.value = SourcesUiState.Error(it.message.orEmpty())
            }
        }
    }

    /**
     * 新增或更新一条资源库。
     *
     * 与旧版 SourceFragment.showSourceManager 保持一致：id 为空时按 [LibraryCompat.sourceId]
     * 生成稳定 id，库内不存在则插入、已存在则更新，调用方不需要区分新增/编辑。
     */
    fun saveSource(source: SourceData) {
        viewModelScope.launch {
            runCatching {
                val dao = DatabaseManager.sourceDataDao
                val id = LibraryCompat.sourceId(source)
                source.id = id
                val existing = dao.getById(id)
                if (existing == null) {
                    if (source.index == 0) source.index = nextIndex()
                    dao.insert(source)
                } else {
                    // 编辑不重排：沿用原有 index，否则改个名字就会跳到最后一位
                    source.index = existing.index
                    dao.update(source)
                }
            }.onFailure {
                _uiState.value = SourcesUiState.Error(it.message.orEmpty())
            }
        }
    }

    /** 删除资源库并清理其站点前缀下的播放历史（与旧版行为一致）。 */
    fun deleteSource(source: SourceData) {
        viewModelScope.launch {
            runCatching {
                DatabaseManager.sourceDataDao.delete(source)
                DatabaseManager.playHistoryDao.deleteByVideoUrlPrefix("${source.siteUrl}%")
            }.onFailure {
                _uiState.value = SourcesUiState.Error(it.message.orEmpty())
            }
        }
    }

    private suspend fun nextIndex(): Int {
        val list = DatabaseManager.sourceDataDao.list()
        return (list.maxOfOrNull { it.index } ?: 0) + 1
    }

    private fun buildBuiltinSources(): List<SourceData> {
        // 本地存储与外部存储合并为一个入口，内部存储 / 存储卡的切换在浏览页里做
        return BuiltinSources.all(getApplication())
    }
}
