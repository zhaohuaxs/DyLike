package me.lingci.dy.player.util

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.PlayingListItem
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import java.util.UUID

/**
 * 播放会话队列的 Room 后端。
 *
 * 队列快照语义与播放页一致：**读取不删除**，
 * 会话由播放页在 onDestroy(isFinishing) 时显式结束，异常退出残留由启动 cleanup 兜底。
 */
object PlayingListStore {

    private const val TAG = "PlayingListStore"

    /** 写入整份队列，返回 sessionId（只把 id 放进 Intent/Bundle）。 */
    suspend fun write(list: List<VideoData>, seasonLabel: String = ""): String =
        withContext(Dispatchers.IO) {
            val startedAt = SystemClock.elapsedRealtime()
            val sessionId = UUID.randomUUID().toString()
            val items = list.mapIndexed { index, video ->
                video.toPlayingListItem(sessionId, index, seasonLabel)
            }
            val dao = DatabaseManager.playingListDao
            DatabaseManager.inTransaction {
                dao.clearSession(sessionId)
                dao.insertAll(items)
            }
            Log.d(TAG, "write size=${list.size} cost=${SystemClock.elapsedRealtime() - startedAt}ms")
            sessionId
        }

    /** 读回队列；会话仍保留，可重复读。 */
    suspend fun read(sessionId: String): List<VideoData> = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val list = DatabaseManager.playingListDao.read(sessionId).map { it.toVideoData() }
        Log.d(TAG, "read size=${list.size} cost=${SystemClock.elapsedRealtime() - startedAt}ms")
        list
    }

    /** 读取季名用于季末提示：只查首行，不捞整份队列。 */
    suspend fun readSeasonLabel(sessionId: String): String = withContext(Dispatchers.IO) {
        DatabaseManager.playingListDao.readSeasonLabel(sessionId).orEmpty()
    }

    suspend fun delete(sessionId: String) {
        withContext(Dispatchers.IO) {
            DatabaseManager.playingListDao.clearSession(sessionId)
        }
    }

    suspend fun cleanup(keepRecent: Int = 3) {
        withContext(Dispatchers.IO) {
            DatabaseManager.playingListDao.clearStaleSessions(keepRecent)
        }
    }

    internal fun VideoData.toPlayingListItem(
        sessionId: String,
        index: Int,
        seasonLabel: String
    ): PlayingListItem = PlayingListItem(
        sessionId = sessionId,
        sortInSession = index,
        seasonLabel = seasonLabel,
        id = this.id,
        name = this.name,
        mediaId = this.mediaId,
        videoUrl = this.videoUrl,
        dmUrl = this.dmUrl,
        preview = this.preview,
        storageType = this.type.name,
        parentPath = this.parentPath,
        headersJson = if (this.headers.isNotEmpty()) JsonUtil.toJsonString(this.headers) else ""
    )

    internal fun PlayingListItem.toVideoData(): VideoData {
        val headers: MutableMap<String, String> = if (headersJson.isNotBlank()) {
            try {
                JsonUtil.toEntity<Map<String, String>>(headersJson).toMutableMap()
            } catch (e: Exception) {
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }
        val storageType = try {
            StorageType.valueOf(this.storageType)
        } catch (e: IllegalArgumentException) {
            StorageType.LOCAL_STORAGE
        }
        return VideoData(
            id = this.id,
            name = this.name,
            mediaId = this.mediaId,
            videoUrl = this.videoUrl,
            dmUrl = this.dmUrl,
            preview = this.preview,
            type = storageType,
            parentPath = this.parentPath,
            headers = headers
        )
    }
}
