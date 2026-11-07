package me.lingci.lib.player.exo.smb

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.context.SingletonContext
import jcifs.smb.SmbException
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import me.lingci.lib.base.storage.smb.SmbAuthToken
import java.io.IOException

/**
 * SMB DataSource for ExoPlayer (Media3).
 *
 * Uses [SmbRandomAccessFile] for true O(1) random-access seek instead of
 * the previous InputStream.skip() approach which was O(n) and unusable
 * for large files.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(
    private val cifsContext: CIFSContext = SingletonContext.getInstance()
) : BaseDataSource(true) {

    private companion object {
        const val READ_AHEAD_SIZE = 512 * 1024
    }

    @UnstableApi
    open class Factory(
        private val headers: Map<String, String>? = null
    ) : DataSource.Factory {

        override fun createDataSource(): DataSource {
            val credentials = SmbAuthToken.decode(headers?.get("Authorization"))
            return SmbDataSource(
                SmbAuthManager.getContext(
                    credentials?.username ?: headers?.get("username")?.trim(),
                    credentials?.password ?: headers?.get("password"),
                    credentials?.domain ?: headers?.get("domain")?.trim()
                )
            )
        }
    }

    private var randomAccessFile: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened = false

    // ExoPlayer 的解析读取通常只有几 KB，直接 read 会变成每块一次 SMB 往返；
    // 预读整块后顺序读走内存，把往返次数从 per-read 降到 per-block。
    private var readBuffer: ByteArray? = null
    private var readBufferOffset = 0
    private var readBufferLength = 0

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        try {
            val smbFile = SmbFile(uri.toString(), cifsContext)

            // SmbRandomAccessFile constructor: connects + opens file handle
            val raf = SmbRandomAccessFile(smbFile, "r")

            // True random access seek — O(1), sends SMB2 READ with offset
            if (dataSpec.position > 0) {
                raf.seek(dataSpec.position)
            }

            randomAccessFile = raf
            readBufferOffset = 0
            readBufferLength = 0

            val fileLength = raf.length()
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                dataSpec.length
            } else {
                fileLength - dataSpec.position
            }

            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (e: SmbException) {
            throw IOException("Failed to open SMB source: $uri", e)
        }
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (readBufferOffset >= readBufferLength && !fillReadBuffer()) {
            return C.RESULT_END_OF_INPUT
        }

        val buffered = readBuffer ?: return C.RESULT_END_OF_INPUT
        val count = minOf(
            readLength,
            readBufferLength - readBufferOffset,
            bytesRemaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )
        System.arraycopy(buffered, readBufferOffset, buffer, offset, count)
        readBufferOffset += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    /** 顺序预读一块；返回 false 表示已到文件末尾。 */
    private fun fillReadBuffer(): Boolean {
        val raf = randomAccessFile ?: return false
        val target = minOf(READ_AHEAD_SIZE.toLong(), bytesRemaining).toInt()
        if (target <= 0) return false

        val buffer = readBuffer ?: ByteArray(READ_AHEAD_SIZE).also { readBuffer = it }
        var filled = 0
        try {
            // 单次 SMB read 可能被协议缓冲上限截断，循环补齐以减少上层小块读取。
            while (filled < target) {
                val read = raf.read(buffer, filled, target - filled)
                if (read <= 0) break
                filled += read
            }
        } catch (e: SmbException) {
            // On read failure, close the handle to prevent leak.
            // ExoPlayer retries by creating a new DataSource via Factory.
            try { raf.close() } catch (_: Exception) {}
            randomAccessFile = null
            readBuffer = null
            readBufferOffset = 0
            readBufferLength = 0
            throw IOException("SMB read failed", e)
        }

        if (filled <= 0) return false
        readBufferOffset = 0
        readBufferLength = filled
        return true
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        readBuffer = null
        readBufferOffset = 0
        readBufferLength = 0
        try {
            randomAccessFile?.close()
        } catch (_: SmbException) {
            // best-effort close
        } finally {
            randomAccessFile = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
