package me.lingci.dy.player.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.lib.base.util.logD
import xyz.doikki.videoplayer.player.VideoView

/**
 * Updates media playback metadata shared by long-video and short-video playback pages.
 *
 * The delayed last-played timestamp intentionally mirrors the old Activity behavior: once one
 * timestamp is written during this recorder lifetime, later playback changes do not write again.
 */
class MediaPlaybackRecorder(private val spUtil: SpUtil) {

    companion object {
        private const val MEDIA_LAST_PLAYED_UPDATE_DELAY_MS = 10_000L
    }

    // Room3：记录写入均为 suspend DAO 调用，使用独立的 IO 作用域 fire-and-forget，禁止阻塞主线程
    // （不取消：与旧实现一致，release 后已发起的单次写入仍允许完成）
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastPlayedUpdateJob: Job? = null
    private var lastPlayedUpdated = false

    /** Cancels a pending delayed last-played timestamp update without resetting written state. */
    fun cancelLastPlayedUpdate() {
        lastPlayedUpdateJob?.cancel()
        lastPlayedUpdateJob = null
    }

    /** Releases pending work owned by this recorder. */
    fun release() {
        cancelLastPlayedUpdate()
    }

    /** Stores the most recently played URL back into the matching media library entry. */
    fun saveLastPlayedUrl(mediaData: MediaData?, url: String) {
        mediaData?.let { media ->
            media.playLast = url
            val mediaId = media.id
            // 无有效 id 无法定位 media_data 行（播放列表 id 不在 media_data 表，更新为 no-op，与旧逻辑一致）
            if (mediaId.isBlank()) return
            // Room3：等价改写为字段级更新 play_last / last_played_at，替代 loadMedia→改→saveMedia 整表写回
            recorderScope.launch {
                runCatching {
                    DatabaseManager.mediaDataDao.updatePlayLast(mediaId, url, System.currentTimeMillis())
                }.onSuccess {
                    logD("lastPlay", media)
                }
            }
        }
    }

    /**
     * Schedules the delayed `lastPlayedAt` write and validates the page is still playing the same
     * item before touching persisted media state.
     */
    fun scheduleLastPlayedAtUpdate(
        scope: CoroutineScope,
        mediaData: MediaData?,
        position: Int,
        currentPosition: () -> Int,
        currentPlayState: () -> Int
    ) {
        if (lastPlayedUpdated) return
        val mediaId = mediaData?.id?.takeIf { it.isNotBlank() } ?: return
        cancelLastPlayedUpdate()
        lastPlayedUpdateJob = scope.launch {
            delay(MEDIA_LAST_PLAYED_UPDATE_DELAY_MS)
            if (lastPlayedUpdated || position != currentPosition()) return@launch
            if (currentPlayState() != VideoView.STATE_PLAYING) return@launch
            withContext(Dispatchers.IO) {
                updateMediaLastPlayedAt(mediaId)
            }
            lastPlayedUpdated = true
        }
    }

    private suspend fun updateMediaLastPlayedAt(mediaId: String) {
        // Room3：只推进 last_played_at；play_last 保持原值（先读后以原值回写，等价旧的 load→改→save 单字段语义）
        val media = DatabaseManager.mediaDataDao.getById(mediaId) ?: return
        DatabaseManager.mediaDataDao.updatePlayLast(mediaId, media.playLast, System.currentTimeMillis())
    }
}
