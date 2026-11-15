package me.lingci.dy.player.util

import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.entity.PlayingListItem
import me.lingci.dy.player.util.PlayingListStore.toPlayingListItem
import me.lingci.dy.player.util.PlayingListStore.toVideoData
import me.lingci.lib.base.storage.entity.StorageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放会话快照的编解码契约。
 *
 * 千条级队列会经过「VideoData → playing_list_item → VideoData」一次完整往返，
 * 任何字段丢失都会让播放页拿到错误的 URL / token；尤其 WebDAV 的 Authorization
 * 在 VideoData 上是 @Ignore，只能靠 headers_json 保留。
 */
class PlayingListStoreCodecTest {

    @Test
    fun roundTrip_keepsAllFieldsAndWebDavToken() {
        val video = VideoData(
            id = "vid-1",
            name = "S01E01.mp4",
            mediaId = "media-1",
            videoUrl = "https://host/dav/S01/E01.mp4",
            dmUrl = "https://host/dav/S01/E01.xml",
            preview = "https://host/cover.jpg",
            type = StorageType.WEBDAV,
            parentPath = "S01",
            sortIndex = 3
        ).apply { putToken("Basic dXNlcjpwYXNz") }

        val item = video.toPlayingListItem("session-1", 7, "S01")
        val restored = item.toVideoData()

        assertEquals("session-1", item.sessionId)
        assertEquals(7, item.sortInSession)
        assertEquals("S01", item.seasonLabel)
        assertEquals(StorageType.WEBDAV.name, item.storageType)
        assertEquals(video.id, restored.id)
        assertEquals(video.name, restored.name)
        assertEquals(video.mediaId, restored.mediaId)
        assertEquals(video.videoUrl, restored.videoUrl)
        assertEquals(video.dmUrl, restored.dmUrl)
        assertEquals(video.preview, restored.preview)
        assertEquals(video.parentPath, restored.parentPath)
        assertEquals(StorageType.WEBDAV, restored.type)
        assertEquals("Basic dXNlcjpwYXNz", restored.getToken())
    }

    @Test
    fun thousandItems_keepOrderAndSeasonLabel() {
        val videos = (0 until 1000).map { index ->
            VideoData(
                id = "v$index",
                name = "E%04d.mp4".format(index),
                videoUrl = "/sdcard/S01/E%04d.mp4".format(index),
                type = StorageType.LOCAL_STORAGE
            )
        }
        val items = videos.mapIndexed { index, video ->
            video.toPlayingListItem("session-large", index, "S01")
        }
        val restored = items.map { it.toVideoData() }

        assertEquals(1000, restored.size)
        assertEquals("E0000.mp4", restored.first().name)
        assertEquals("E0999.mp4", restored.last().name)
        assertEquals(500, items[500].sortInSession)
        assertEquals("E0500.mp4", restored[500].name)
        assertTrue(items.all { it.seasonLabel == "S01" })
        assertTrue(items.all { it.headersJson.isEmpty() })
    }

    @Test
    fun brokenHeadersJson_fallsBackToEmptyMap() {
        val item = PlayingListItem(
            sessionId = "s",
            sortInSession = 0,
            id = "v",
            name = "n",
            mediaId = "m",
            videoUrl = "u",
            headersJson = "{not-json"
        )
        assertTrue(item.toVideoData().headers.isEmpty())
    }

    @Test
    fun unknownStorageType_fallsBackToLocal() {
        val item = PlayingListItem(
            sessionId = "s",
            sortInSession = 0,
            id = "v",
            name = "n",
            mediaId = "m",
            videoUrl = "u",
            storageType = "NOT_A_TYPE"
        )
        assertEquals(StorageType.LOCAL_STORAGE, item.toVideoData().type)
    }
}
