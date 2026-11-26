package me.lingci.lib.base.okhttp

import me.lingci.lib.base.okhttp.OkUtil.await
import me.lingci.lib.base.util.Log
import okhttp3.FormBody
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection

/**
 * 带 HTTP 状态码的下载结果。
 * [code] 为响应状态码，[stream] 仅在 2xx 且有 body 时非空。
 */
data class HttpStreamResult(val code: Int, val stream: InputStream?)

class GetRequest(url: String) : BaseRequest<GetRequest>(url) {

    override fun get(): String {
        return try {
            getClient().newCall(buildRequest()).execute().body?.string() ?: ""
        } catch (e: Exception) {
            Log.d(this, e.message)
            ""
        }
    }

    override suspend fun execute(): Result<String> = runCatching {
        getClient().newCall(buildRequest()).await()
    }

    // 在 BaseRequest 类中添加
    suspend fun checkPreflight(): Boolean {
        return OkUtil.isConnectionAvailable(this.url)
    }

    /**
     * 下载大文件或流数据，同时返回 HTTP 状态码。
     * 非 2xx（如 304/412/403）会关闭 response 并返回 code + null。
     */
    fun downloadWithStatus(): HttpStreamResult {
        val response = getClient().newCall(buildRequest()).execute() // 这里的 execute 在协程中需注意线程
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            return HttpStreamResult(code, null)
        }
        // 注意：调用方需要在使用完后关闭 InputStream
        return HttpStreamResult(response.code, response.body?.byteStream())
    }

    /**
     * 下载大文件或流数据的协程实现
     */
    fun download(): InputStream? = downloadWithStatus().stream

    /**
     * 针对流媒体/文件下载的扩展
     * 调用处直接在协程中获取 InputStream，使用完后自动关闭
     */
    suspend fun executeStream(): Result<InputStream> = runCatching {
        val request = Request.Builder().url(url).headers(headers.toHeaders()).get().build()

        // .use 是 Kotlin 的 try-with-resources，保证 ResponseBody 自动关闭
        getClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            response.body?.byteStream() ?: throw IOException("Body is null")
        }
    }

    private fun buildRequest(): Request {
        return Request.Builder().url(buildUrl()).headers(headers.toHeaders()).get().build()
    }

    private fun buildUrl(): HttpUrl {
        return url.toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

}

class PostRequest(url: String) : BaseRequest<PostRequest>(url) {

    private var jsonBody: String? = null
    private val files = mutableMapOf<String, MutableList<File>>()

    fun body(json: String) = apply { this.jsonBody = json }
    fun addFile(key: String, file: File?) = apply {
        file?.let { files.getOrPut(key) { mutableListOf() }.add(it) }
    }

    override fun get(): String {
        return try {
            getClient().newCall(buildRequest()).execute().body?.string() ?: ""
        } catch (e: Exception) {
            Log.d(this, e.message)
            ""
        }
    }

    override suspend fun execute(): Result<String> = runCatching {
        getClient().newCall(buildRequest()).await()
    }

    private fun buildRequest(): Request {
        return Request.Builder().url(url).headers(headers.toHeaders()).post(buildRequestBody())
            .build()
    }

    private fun buildRequestBody(): RequestBody {
        return when {
            jsonBody != null -> jsonBody!!.toRequestBody("application/json".toMediaType())
            files.isNotEmpty() -> MultipartBody.Builder().setType(MultipartBody.FORM).apply {
                params.forEach { (k, v) -> addFormDataPart(k, v) }
                files.forEach { (k, list) ->
                    list.forEach { file ->
                        addFormDataPart(
                            k,
                            file.name,
                            file.asRequestBody(getMime(file))
                        )
                    }
                }
            }.build()

            else -> FormBody.Builder().apply { params.forEach { (k, v) -> add(k, v) } }.build()
        }
    }

    private fun getMime(file: File) = (URLConnection.getFileNameMap().getContentTypeFor(file.name)
        ?: "application/octet-stream").toMediaType()
}

// 顶层入口函数
fun httpGet(url: String) = GetRequest(url)
fun httpPost(url: String) = PostRequest(url)
