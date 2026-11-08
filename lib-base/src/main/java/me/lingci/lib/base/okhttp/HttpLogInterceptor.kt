package me.lingci.lib.base.okhttp

import me.lingci.lib.base.util.Log
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException
import java.util.Locale

/**
 * 调试用网络日志拦截器，替代 okhttp 的 `HttpLoggingInterceptor(Level.BODY)`。
 *
 * 原实现的问题：BODY 级别会把**整个响应体**读进内存再回放给它自己，
 * 对媒体请求等于「整个文件下载完，open() 才返回」——
 * 小文件看不出来，29GB 的 ISO 必然 OOM，而且断流/超时这类错误会从 `read()`
 * 提前到 `open()`，抓到的现象和 release 包不一致。
 *
 * 这里按「内容类型 + 长度」决定输出什么：
 *
 * | 响应 | 输出 |
 * | --- | --- |
 * | 文本类（text/… 前缀、json、xml、m3u8、srt 等） | 用 `peekBody` 取前 [maxTextBytes] 字节打印，超长标注截断 |
 * | 其它（video/…、audio/…、octet-stream 等） | 只打印类型与大小，绝不碰内容 |
 *
 * 关键点：`peekBody` 只读不消费，所以对播放链路零影响 —— 它跟 BODY 级别的
 * 「读完再回放」是两件事。
 */
internal class HttpLogInterceptor(
    private val maxTextBytes: Long = DEFAULT_MAX_TEXT_BYTES,
    private val logHeaders: Boolean = true,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedNs = System.nanoTime()

        Log.d(TAG, "--> ${request.method} ${request.url}")
        if (logHeaders) {
            logBlock("--> headers:", formatHeaders(request.headers))
        }
        logRequestBody(request)

        val response: Response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            Log.d(
                TAG,
                "<-- FAILED ${request.method} ${request.url} (${elapsedMs(startedNs)}ms): " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
            throw e
        }

        logResponse(response, request, startedNs)
        return response
    }

    private fun logResponse(response: Response, request: Request, startedNs: Long) {
        val body = response.body
        val contentType = body?.contentType()
        val length = body?.contentLength() ?: -1L

        Log.d(
            TAG,
            buildString {
                append("<-- ${response.code} ${response.message} ${request.url}")
                append(" (${elapsedMs(startedNs)}ms, ")
                append(if (length >= 0) formatSize(length) else "unknown size")
                if (contentType != null) append(", $contentType")
                append(')')
            }
        )
        if (logHeaders) {
            logBlock("<-- headers:", formatHeaders(response.headers))
        }

        if (body == null || request.method == "HEAD") return
        // 204/304 按协议就没有实体
        if (response.code == 204 || response.code == 304) return

        if (!isTextual(contentType)) {
            Log.d(TAG, "<-- body skipped (binary): ${describe(body, contentType, length)}")
            return
        }

        val peeked = try {
            response.peekBody(maxTextBytes)
        } catch (e: Exception) {
            Log.d(TAG, "<-- body peek failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val text = try {
            peeked.string()
        } catch (e: Exception) {
            Log.d(TAG, "<-- body read failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (text.isEmpty()) {
            Log.d(TAG, "<-- body: (empty)")
            return
        }
        val truncated = length < 0 || length > maxTextBytes
        val suffix = if (truncated) {
            val total = if (length >= 0) formatSize(length) else "unknown"
            "\n... [truncated: showing first ${formatSize(maxTextBytes)}, total $total]"
        } else {
            ""
        }
        logBlock("<-- body:", text + suffix)
    }

    /**
     * 请求体只在「非 one-shot + 长度已知 + 长度不超限」时才读。
     *
     * `writeTo` 对 one-shot body 是破坏性的，直接写会把请求体提前消费掉；
     * 对已知长度的普通 body（FormBody、String 包装）重复写是安全的。
     */
    private fun logRequestBody(request: Request) {
        val body = request.body ?: return
        val contentType = body.contentType()
        val length = try {
            body.contentLength()
        } catch (e: Exception) {
            -1L
        }

        if (body.isOneShot() || body.isDuplex()) {
            Log.d(TAG, "--> body one-shot, skipped: ${describe(null, contentType, length)}")
            return
        }
        if (!isTextual(contentType)) {
            Log.d(TAG, "--> body skipped (binary): ${describe(null, contentType, length)}")
            return
        }
        if (length < 0) {
            Log.d(TAG, "--> body skipped (unknown length): $contentType")
            return
        }
        if (length > maxTextBytes) {
            Log.d(TAG, "--> body skipped (too large): ${describe(null, contentType, length)}")
            return
        }

        val buffer = Buffer()
        try {
            body.writeTo(buffer)
        } catch (e: Exception) {
            Log.d(TAG, "--> body write failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        val text = buffer.readUtf8()
        if (text.isEmpty()) return
        logBlock("--> body:", text)
    }

    private fun describe(body: ResponseBody?, contentType: MediaType?, length: Long): String {
        val size = when {
            length >= 0 -> formatSize(length)
            body != null -> "unknown size"
            else -> "unknown size"
        }
        return "${contentType ?: "no content-type"}, $size"
    }

    private fun formatHeaders(headers: Headers): String = buildString {
        for ((name, value) in headers) {
            if (length > 0) append('\n')
            append("  $name: ")
            append(if (isSensitive(name)) REDACTED else value.take(MAX_HEADER_VALUE_CHARS))
        }
    }

    /**
     * 按 logcat 单行上限切块输出。logcat 大约 4000 字符截断，
     * 这里留足余量，避免长 XML / JSON 被系统截掉一半。
     */
    private fun logBlock(title: String, content: String) {
        if (content.isEmpty()) {
            Log.d(TAG, title)
            return
        }
        var index = 0
        var first = true
        while (index < content.length) {
            var end = minOf(index + MAX_LINE_CHARS, content.length)
            if (end < content.length) {
                // 尽量在换行处断开，避免把 JSON/XML 的一行切成两半
                val lastNewline = content.lastIndexOf('\n', end - 1)
                if (lastNewline > index + MAX_LINE_CHARS / 2) {
                    end = lastNewline + 1
                }
            }
            val head = if (first) title else "$title (cont.)"
            Log.d(TAG, "$head\n${content.substring(index, end)}")
            index = end
            first = false
        }
    }

    private fun isTextual(contentType: MediaType?): Boolean {
        if (contentType == null) return false
        val type = contentType.type.lowercase(Locale.US)
        val subtype = contentType.subtype.lowercase(Locale.US)
        if (type == "text") return true
        if (subtype.endsWith("+json") || subtype.endsWith("+xml")) return true
        return "$type/$subtype" in TEXT_MIME_TYPES
    }

    private fun isSensitive(name: String): Boolean =
        name.lowercase(Locale.US) in SENSITIVE_HEADERS

    private fun elapsedMs(startedNs: Long): Long = (System.nanoTime() - startedNs) / 1_000_000

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private companion object {
        const val TAG = "OkHttp"

        /** 文本响应最多打印 8KB，足够看清 JSON / XML 结构，又不会把大响应拖进内存。 */
        const val DEFAULT_MAX_TEXT_BYTES = 8L * 1024

        /** 单条 logcat 的字符预算，留出 tag 与标题的空间。 */
        const val MAX_LINE_CHARS = 1200

        const val MAX_HEADER_VALUE_CHARS = 200
        const val REDACTED = "<redacted>"

        /** 明确按文本处理的非 text 前缀类型（媒体清单、字幕、WebDAV 属性等）。 */
        val TEXT_MIME_TYPES = setOf(
            "application/json",
            "application/xml",
            "application/javascript",
            "application/x-javascript",
            "application/ecmascript",
            "application/x-www-form-urlencoded",
            "application/x-ndjson",
            "application/graphql",
            "application/sdp",
            "application/dash+xml",
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/mpegurl",
            "application/x-subrip",
            "application/ttml+xml",
            "application/vnd.api+json",
            "application/problem+json",
        )

        val SENSITIVE_HEADERS = setOf(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
        )
    }
}
