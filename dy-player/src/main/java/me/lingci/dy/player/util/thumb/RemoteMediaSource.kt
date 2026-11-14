package me.lingci.dy.player.util.thumb

import android.media.MediaDataSource
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import me.lingci.dy.player.entity.VideoData
import me.lingci.lib.base.okhttp.OkUtil
import me.lingci.lib.base.storage.smb.SmbAuthToken
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.exo.smb.SmbAuthManager
import okhttp3.Request
import java.io.InputStream

/**
 * 远程视频的随机读数据源，喂给 `MediaMetadataRetriever.setDataSource(MediaDataSource)`。
 *
 * 背景：`setDataSource(String)` 只认本地路径，`setDataSource(url, headers)` 走的是系统自带的
 * NuHTTP 栈，实测在 alist/WebDAV 上直接 `status = 0x80000000`（30s 后失败），且无法复用应用里
 * 已实现的重定向与凭据逻辑。改成自己按需 Range 读取后，抽帧与播放器、渲染器彻底解耦：
 * WebDAV/SMB 也能用和本地一样的 `0s → 片长10% → 1s → 3s` 回退策略，不再依赖「起播 1s 截当前画面」。
 *
 * 只读、可 seek、线程安全；`MediaMetadataRetriever` 会在解复用线程里多次调用 [readAt]。
 */
class HttpMediaDataSource(
    private val url: String,
    private val headers: Map<String, String>
) : MediaDataSource() {

    private companion object {
        const val TAG = "HttpMediaDataSource"

        /** 单块大小：一次 Range 请求覆盖多次解复用读取，避免每个小读都发一次 HTTP。 */
        const val BLOCK_SIZE = 256 * 1024

        /** 块缓存上限：解复用会在 moov 与目标帧之间来回跳，留几块可显著减少往返。 */
        const val MAX_BLOCKS = 6
    }

    private val lock = Any()
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_BLOCKS
    }

    private var length = -1L
    private var closed = false

    override fun getSize(): Long = synchronized(lock) {
        if (length < 0) length = runCatching { probeLength() }.getOrDefault(-1L)
        length
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || size <= 0 || offset < 0) return -1
        synchronized(lock) {
            if (closed) return -1
            val total = if (length >= 0) length else getSize()
            if (total >= 0 && position >= total) return -1

            val blockStart = position / BLOCK_SIZE * BLOCK_SIZE
            var block = blocks[blockStart]
            if (block == null) {
                block = fetchBlock(blockStart) ?: return -1
                blocks[blockStart] = block
            }
            val inBlock = (position - blockStart).toInt()
            if (inBlock >= block.size) return -1
            var count = minOf(size, block.size - inBlock)
            if (total >= 0) count = minOf(count.toLong(), total - position).toInt()
            if (count <= 0) return -1
            System.arraycopy(block, inBlock, buffer, offset, count)
            return count
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            blocks.clear()
        }
    }

    /** 用 `Range: bytes=0-0` 探总长，比 HEAD 兼容性好（部分 WebDAV 实现不实现 HEAD）。 */
    private fun probeLength(): Long {
        val response = OkUtil.unsafeClient.newCall(buildRequest(0L, 1)).execute()
        response.use { res ->
            if (res.code !in 200..299) {
                Log.d(TAG, "probe failed", url, res.code)
                return -1L
            }
            res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { return it }
            // 服务端忽略 Range（200）时只能靠 Content-Length，等于整个文件长度
            return res.body?.contentLength()?.takeIf { it > 0 } ?: -1L
        }
    }

    private fun fetchBlock(start: Long): ByteArray? {
        val response = try {
            OkUtil.unsafeClient.newCall(buildRequest(start, BLOCK_SIZE)).execute()
        } catch (e: Exception) {
            Log.d(TAG, "fetch block failed", start, e.message)
            return null
        }
        return response.use { res ->
            if (res.code !in 200..299) {
                Log.d(TAG, "fetch block http error", start, res.code)
                return@use null
            }
            val body = res.body ?: return@use null
            val input: InputStream = body.byteStream()
            // 服务端不认 Range 时返回 200 + 全量流，需要自己跳过前面的字节
            if (res.code == 200 && start > 0L) {
                var skipped = 0L
                while (skipped < start) {
                    val n = input.skip(start - skipped)
                    if (n <= 0L) return@use null
                    skipped += n
                }
            }
            val buffer = ByteArray(BLOCK_SIZE)
            var filled = 0
            while (filled < BLOCK_SIZE) {
                val read = input.read(buffer, filled, BLOCK_SIZE - filled)
                if (read <= 0) break
                filled += read
            }
            if (filled <= 0) null else buffer.copyOf(filled)
        }
    }

    private fun buildRequest(start: Long, count: Int): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-${start + count - 1}")
            .header("Accept", "*/*")
        headers.forEach { (key, value) ->
            if (value.isNotBlank() && !key.equals("Range", ignoreCase = true)) {
                builder.header(key, value)
            }
        }
        return builder.build()
    }
}

/**
 * SMB 视频的随机读数据源。
 *
 * 复用 `SmbRandomAccessFile` 做 O(1) seek（与 Exo 侧 [me.lingci.lib.player.exo.smb.SmbDataSource] 同一套），
 * 凭据从 [VideoData.getToken] 的 `smb:` 令牌里解出，与播放链路完全一致。
 *
 * 带块预读：解复用会连续做大量几 KB 的读，若每次直接 read 就是一次 SMB 往返。
 * 真机实测 312MB 的 mkv 不加预读抽一张图要 15.2s，加 256KB 块缓存后才可接受。
 */
class SmbMediaDataSource(url: String, token: String?) : MediaDataSource() {

    private companion object {
        const val TAG = "SmbMediaDataSource"
        const val BLOCK_SIZE = 256 * 1024
        const val MAX_BLOCKS = 6
    }

    private val lock = Any()
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_BLOCKS
    }

    private val randomAccessFile: SmbRandomAccessFile
    private val length: Long
    private var closed = false

    init {
        val credentials = SmbAuthToken.decode(token)
        val context = SmbAuthManager.getContext(
            credentials?.username,
            credentials?.password,
            credentials?.domain
        )
        val file = SmbFile(url, context)
        randomAccessFile = SmbRandomAccessFile(file, "r")
        length = randomAccessFile.length()
        Log.d(TAG, "opened", url, "length", length)
    }

    override fun getSize(): Long = length

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0L || size <= 0 || offset < 0 || position >= length) return -1
        synchronized(lock) {
            if (closed) return -1
            val blockStart = position / BLOCK_SIZE * BLOCK_SIZE
            var block = blocks[blockStart]
            if (block == null) {
                block = fetchBlock(blockStart) ?: return -1
                blocks[blockStart] = block
            }
            val inBlock = (position - blockStart).toInt()
            if (inBlock >= block.size) return -1
            var count = minOf(size, block.size - inBlock)
            count = minOf(count.toLong(), length - position).toInt()
            if (count <= 0) return -1
            System.arraycopy(block, inBlock, buffer, offset, count)
            return count
        }
    }

    /** 顺序读一整块到内存；SMB 单次 read 会被协议缓冲上限截断，循环补齐。 */
    private fun fetchBlock(start: Long): ByteArray? {
        val want = minOf(BLOCK_SIZE.toLong(), length - start).toInt()
        if (want <= 0) return null
        val buffer = ByteArray(want)
        return try {
            randomAccessFile.seek(start)
            var filled = 0
            while (filled < want) {
                val read = randomAccessFile.read(buffer, filled, want - filled)
                if (read <= 0) break
                filled += read
            }
            when {
                filled <= 0 -> null
                filled == want -> buffer
                else -> buffer.copyOf(filled)
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetch block failed", start, e.message)
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            blocks.clear()
            runCatching { randomAccessFile.close() }
        }
    }
}

/**
 * 为视频选择可用的抽帧数据源；返回 null 表示该源无法用 `MediaMetadataRetriever` 解复用。
 *
 * - 本地文件不走这里（直接传路径）。
 * - HLS/DASH 清单（m3u8/mpd）无法解析成单一媒体文件，只能回退播放器截图。
 */
object RemoteThumbSource {

    private const val TAG = "RemoteThumbSource"

    fun open(video: VideoData): MediaDataSource? {
        val url = video.videoUrl
        return try {
            when {
                url.startsWith("http://", true) || url.startsWith("https://", true) -> {
                    if (isManifest(url)) {
                        Log.d(TAG, "manifest skipped", url)
                        null
                    } else {
                        HttpMediaDataSource(url, video.headers)
                    }
                }

                url.startsWith("smb://", true) -> SmbMediaDataSource(url, video.getToken())

                else -> null
            }
        } catch (e: Exception) {
            Log.d(TAG, "open failed", url, e.message)
            null
        }
    }

    fun isManifest(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }
}
