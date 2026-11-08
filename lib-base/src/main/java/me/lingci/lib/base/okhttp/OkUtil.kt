package me.lingci.lib.base.okhttp

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.lingci.lib.base.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object OkUtil {
    private const val TIMEOUT_CONNECT = 10L
    private const val TIMEOUT_READ = 60L

    // 公共 Headers 存储
    val commonHeaders = mutableMapOf<String, String>()

    // 标准客户端单例
    val client: OkHttpClient by lazy { createClient(false) }

    // 信任所有证书的客户端单例
    val unsafeClient: OkHttpClient by lazy { createClient(true) }

    fun get(url: String): GetRequest {
        return httpGet(url)
    }

    fun post(url: String): PostRequest {
        return httpPost(url)
    }

    private fun createClient(unsafe: Boolean): OkHttpClient {
        return OkHttpClient.Builder().apply {
            connectTimeout(TIMEOUT_CONNECT, TimeUnit.SECONDS)
            readTimeout(TIMEOUT_READ, TimeUnit.SECONDS)
            writeTimeout(TIMEOUT_READ, TimeUnit.SECONDS)

            // 日志拦截器：文本类响应才打印内容（最多 8KB），
            // 二进制只输出类型与大小。原 BODY 级别会把整个响应体读进内存，
            // 媒体请求等于「文件下完才起播」，大文件直接 OOM。
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLogInterceptor())
            }

            if (unsafe) {
                val trustManager = UnsafeTrustManager()
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trustManager), SecureRandom())
                }
                sslSocketFactory(sslContext.socketFactory, trustManager)
                hostnameVerifier { _, _ -> true }
            }
        }.build()
    }

    /**
     * 协程扩展：将 OkHttp 的 Call 转换为挂起函数
     */
    suspend fun Call.await(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() } // 协程取消时自动取消网络请求
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (it.isSuccessful) {
                        continuation.resume(it.body?.string().orEmpty())
                    } else {
                        continuation.resumeWithException(IOException("HTTP Error: ${it.code}"))
                    }
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
    }

    /**
     * 测试连接是否可用
     * @param url 目标地址
     * @param timeoutSeconds 超时时间（默认 3 秒，测试连接不宜过长）
     * @return 是否可连接
     */
    suspend fun isConnectionAvailable(url: String, timeoutSeconds: Long = 3L): Boolean =
        withContext(
            Dispatchers.IO
        ) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .head() // 使用 HEAD 方法，省流量且速度快
                    .build()

                // 创建一个短超时的临时 Client 或重用现有 Client 的 Builder
                val testClient = client.newBuilder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .build()

                // 执行请求
                testClient.newCall(request).execute().use { response ->
                    // 2xx 或 3xx 均认为网络连通
                    response.isSuccessful || response.code in 300..399
                }
            } catch (e: Exception) {
                false
            }
        }

    @SuppressLint("CustomX509TrustManager")
    private class UnsafeTrustManager : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

}