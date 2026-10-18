package me.lingci.dy.player.migrate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.logD

/**
 * 迁移页 ViewModel：进入页面预统计展示数量（§5.4），点击开始后执行迁移。
 */
class MigrationViewModel(app: Application) : AndroidViewModel(app) {

    /** 进入页面时的预统计（布局中七项数量的数据源） */
    data class Preview(
        val sources: Int,
        val media: Int,
        val videos: Int,
        val history: Int,
        val favorites: Int,
        val playlists: Int,
        val playlistItems: Int,
        val shuffles: Int
    )

    sealed class State {
        data object Idle : State()
        data class Prepared(val preview: Preview) : State()
        data object Running : State()
        data class Success(val result: MigrationResult) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    /** 预统计旧数据规模（仅解析 SP JSON，不触发迁移） */
    fun prepare() {
        if (_state.value !is State.Idle) return
        viewModelScope.launch {
            val sp = SpUtil(getApplication())
            val preview = withContext(Dispatchers.IO) {
                // 注意：SPManager.string 委托返回可空 String?，需 orEmpty() 后再解析
                val sources = JsonUtil.toList<SourceData>(sp.sourceJson.orEmpty())
                val media = JsonUtil.toList<MediaData>(sp.mediaJson.orEmpty())
                val history = JsonUtil.toList<VideoData>(sp.historyJson.orEmpty())
                val likes = JsonUtil.toList<VideoData>(sp.likeJson.orEmpty())
                val playlists = JsonUtil.toList<MediaData>(sp.playlistJson.orEmpty())
                val shuffleMap = runCatching {
                    JsonUtil.toEntity<Map<String, MediaShuffleState>>(sp.mediaShuffleJson.orEmpty())
                }.getOrDefault(emptyMap())
                Preview(
                    sources = sources.size,
                    media = media.count { it.type.isLibraryType },
                    videos = media.sumOf { it.items.size } + history.size +
                            likes.size + playlists.sumOf { it.items.size },
                    history = history.size,
                    favorites = likes.size,
                    playlists = playlists.size,
                    playlistItems = playlists.sumOf { it.items.size },
                    shuffles = shuffleMap.size
                )
            }
            _state.value = State.Prepared(preview)
        }
    }

    /** 开始迁移（单事务，失败自动回滚） */
    fun startMigration() {
        if (_state.value is State.Running) return
        _state.value = State.Running
        viewModelScope.launch {
            try {
                val result = MigrationExecutor.perform(getApplication(), SpUtil(getApplication()))
                _state.value = State.Success(result)
            } catch (e: Exception) {
                logD("migration failed", e.message ?: e.toString(), e)
                _state.value = State.Failed(e.message ?: e.toString())
            }
        }
    }
}
