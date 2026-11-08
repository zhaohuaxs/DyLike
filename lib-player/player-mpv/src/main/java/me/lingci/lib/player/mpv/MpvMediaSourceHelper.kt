package me.lingci.lib.player.mpv

import android.content.Context
import `is`.xyz.mpv.MPV
import xyz.doikki.videoplayer.util.L
import java.util.concurrent.ConcurrentHashMap

class MpvMediaSourceHelper(private val context: Context) {
    
    // 存储不同URL的HTTP头信息
    private val urlHeadersMap: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /**
     * 设置HTTP请求头
     * @param headers HTTP请求头映射
     */
    fun setHeaders(mpv: MPV, headers: MutableMap<String, String>) {
        if (headers.isNotEmpty()) {
            try {
                // 处理User-Agent
                if (headers.containsKey("User-Agent")) {
                    val value = headers.remove("User-Agent")
                    if (!value.isNullOrBlank()) {
                        mpv.setPropertyString("user-agent", value)
                        L.d("Set user-agent: $value")
                    }
                }

                // 处理其他HTTP头
                if (headers.isNotEmpty()) {
                    val headersString = headers.map {
                        "${it.key}: ${it.value.replace(",", "\\,")}"
                    }.joinToString(",")
                    mpv.setPropertyString("http-header-fields", headersString)
                    L.d("Set HTTP headers: $headersString")
                }
            } catch (e: Exception) {
                L.e("Failed to set HTTP headers: ${e.message}")
            }
        } else {
            mpv.setPropertyString("http-header-fields", "")
        }
    }
    
    /**
     * 为特定URL设置HTTP请求头
     * @param url 媒体URL
     * @param headers HTTP请求头映射
     */
    fun setHeadersForUrl(mpv: MPV, url: String, headers: MutableMap<String, String>) {
        if (url.isEmpty() || headers.isEmpty()) return
        
        val headersString = headers.map {
            "${it.key}: ${it.value.replace(",", "\\,")}"
        }.joinToString(",")
        urlHeadersMap[url] = headersString
        
        // 如果是当前正在加载的URL，立即应用
        try {
            try {
                val currentUrl = mpv.getPropertyString("stream-open-filename")
                if (currentUrl == url) {
                    setHeaders(mpv, headers)
                }
            } catch (e: Exception) {
                L.e("Failed to check current URL for headers: ${e.message}")
            }
        } catch (e: Exception) {
            L.e("Failed to setup network settings: ${e.message}")
        }
    }
    
    /**
     * 配置缓存设置。
     *
     * 只用内存缓存：移动端开 `cache-on-disk` 会额外占存储且随机 IO 更差，实测对流式播放无收益，
     * 因此这里不再设置该选项（mpv 默认即不落盘）。
     *
     * @param cacheEnabled 是否启用缓存
     * @param cacheSize 缓存大小（MB）
     */
    fun setupCache(mpv: MPV, cacheEnabled: Boolean, cacheSize: Int = 64) {
        try {
            if (cacheEnabled) {
                val cacheBytes = cacheSize * 1024 * 1024L
                // 开启网络缓存（auto 已是默认，这里显式声明，避免被用户 config 关掉）
                mpv.setOptionString("cache", "yes")
                mpv.setOptionString("demuxer-max-bytes", cacheBytes.toString())
                // 回看缓存减半：拖回退时仍可复用，但不与前瞻缓抢占内存
                mpv.setOptionString("demuxer-max-back-bytes", (cacheBytes / 2).toString())
                L.d("Cache enabled, size: ${cacheSize}MB")
            } else {
                mpv.setOptionString("cache", "no")
                L.d("Cache disabled")
            }
        } catch (e: Exception) {
            L.e("Failed to setup cache: ${e.message}")
        }
    }
    
    /**
     * 配置网络设置。
     *
     * 注意 `network-timeout` 的单位是**秒**（mpv 默认 60），旧实现按毫秒传 `timeout * 1000`，
     * 等于把超时放大 1000 倍，网络无响应时会一直挂着，这里修正为秒。
     *
     * @param timeout 首字节超时时间（秒）
     * @param lowLatency 是否低延迟模式
     */
    fun setupNetworkSettings(mpv: MPV, timeout: Int = 15, lowLatency: Boolean = false) {
        try {
            if (lowLatency) {
                // 低延迟模式设置
                mpv.setOptionString("network-timeout", timeout.toString())
                mpv.setOptionString("demuxer-readahead-secs", "0.5")
                mpv.setOptionString("hr-seek-framedrop", "no")
                mpv.setOptionString("cache-secs", "1")
                L.d("Network: timeout=${timeout}s, low latency")
            } else {
                // 正常模式：前读 5s、最多缓存 30s 内容，起播后尽快有可播数据
                mpv.setOptionString("network-timeout", timeout.toString())
                mpv.setOptionString("demuxer-readahead-secs", "5")
                mpv.setOptionString("cache-secs", "30")
                L.d("Network: timeout=${timeout}s")
            }
        } catch (e: Exception) {
            L.e("Failed to setup network settings: ${e.message}")
        }
    }
    
    /**
     * 清理资源
     */
    fun release() {
        urlHeadersMap.clear()
    }

}