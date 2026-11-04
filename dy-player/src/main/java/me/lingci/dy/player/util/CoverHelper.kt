package me.lingci.dy.player.util

import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import okhttp3.Credentials
import java.io.File

/**
 *   @author : happyc
 *   time    : 2026/01/05
 *   desc    :
 *   version : 1.0
 */
/**
 * 封面加载候选：[path] 为本地绝对路径或 http(s) URL，[headers] 是该 URL 需要的请求头（WebDAV 认证）。
 *
 * 解析与加载分离后，Compose 侧可以先拿到路径再决定用 Composable 渲染还是回落 Glide，
 * 不必为了算一个路径而构造 ImageView。
 */
data class CoverCandidate(
    val path: String,
    val headers: Map<String, String> = emptyMap()
)

object CoverHelper {

    /** 源列表缓存的刷新间隔：既避免每次绑定都查库，也保证编辑资源库后缓存尽快自愈 */
    private const val SOURCE_CACHE_TTL_MS = 5_000L

    // 源列表惰性缓存（Room3：sourceDataDao 为 suspend，图片绑定线程无法同步读库）
    @Volatile
    private var cachedSourceList: List<SourceData> = emptyList()

    @Volatile
    private var cacheAt: Long = 0L

    // 仅用于后台预热源列表缓存的作用域（与 CoverHelper 同生命周期，非 GlobalScope）
    private val cacheScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 缓存过期时后台刷新源列表（结果回填后供后续图片绑定使用） */
    private fun refreshSourceListIfNeeded() {
        if (System.currentTimeMillis() - cacheAt <= SOURCE_CACHE_TTL_MS) {
            return
        }
        cacheScope.launch {
            runCatching { DatabaseManager.sourceDataDao.list() }.onSuccess {
                cachedSourceList = it
                cacheAt = System.currentTimeMillis()
            }
        }
    }

    private fun loadModel(path: String, headers: Map<String, String>): Any {
        if (headers.isEmpty() || !path.startsWith("http")) {
            return path
        }
        val lazyHeaders = LazyHeaders.Builder().apply {
            headers.forEach { (key, value) ->
                addHeader(key, value)
            }
        }.build()
        return GlideUrl(path, lazyHeaders)
    }

    fun loadImage(imageView: ImageView, path: String?, headers: Map<String, String> = emptyMap()): Boolean {
        if (path.isNullOrBlank()) {
            return false
        }
        if (path.startsWith("/")) {
            val file = File(path)
            if (!file.exists()) {
                return false
            }
            Glide.with(imageView.context)
                .load(file)
                .apply(AppUtil.mediaOptions)
                .into(imageView)
            return true
        }
        Glide.with(imageView.context)
            .load(loadModel(path, headers))
            .apply(AppUtil.mediaOptions)
            .into(imageView)
        return true
    }

    private fun resolveSourceList(sourceList: List<SourceData>): List<SourceData> {
        if (sourceList.isNotEmpty()) {
            return sourceList
        }
        // Room3：改为 TTL 惰性缓存——缓存过期触发后台刷新，本次绑定先返回当前缓存。
        // 首次冷启动时缓存为空，该次 WEBDAV 封面可能缺认证头（表现为占位图），回填后自愈。
        refreshSourceListIfNeeded()
        return cachedSourceList
    }

    private fun resolveWebDavHeaders(mediaData: MediaData, sourceList: List<SourceData>): Map<String, String> {
        if (mediaData.type != MediaLibType.WEBDAV) {
            return emptyMap()
        }
        val sources = resolveSourceList(sourceList)
        val storageId = LibraryCompat.effectiveStorageId(mediaData, sources) ?: return emptyMap()
        val source = sources.find { it.id == storageId } ?: return emptyMap()
        if (source.username.isBlank() || source.password.isBlank()) {
            return emptyMap()
        }
        return mapOf(VideoData.TOKEN_KEY to Credentials.basic(source.username, source.password))
    }

    private fun resolveWebDavCoverPath(mediaData: MediaData, sourceList: List<SourceData>): String? {
        if (mediaData.type != MediaLibType.WEBDAV) {
            return null
        }
        val sources = resolveSourceList(sourceList)
        val storageId = LibraryCompat.effectiveStorageId(mediaData, sources) ?: return null
        val source = sources.find { it.id == storageId } ?: return null
        if (source.siteUrl.isBlank()) {
            return null
        }
        val baseUrl = if (source.siteUrl.endsWith('/')) source.siteUrl else "${source.siteUrl}/"
        val path = mediaData.path.trim().trim('/').takeIf { it.isNotBlank() }
        return if (path.isNullOrBlank()) {
            "${baseUrl}cover.jpg"
        } else {
            "${baseUrl}${path}/cover.jpg"
        }
    }

    /**
     * 路径是否值得交给加载器：本地路径要先确认文件真在，否则必然白跑一次解码；
     * 网络地址与 content:// 无法同步探测，一律放行。
     */
    private fun String?.isUsable(): Boolean = when {
        isNullOrBlank() -> false
        startsWith("/") -> File(this).exists()
        else -> true
    }

    private fun firstUsable(vararg paths: String?): String? = paths.firstOrNull { it.isUsable() }

    /**
     * 解析媒体库封面：DEFAULT 只认 cover.jpg（忽略 showFile），CUSTOM/AUTO 优先 showFile 再回退。
     * 返回 null 表示没有任何可加载的路径，调用方应显示占位图。
     */
    fun resolveCover(mediaData: MediaData, sourceList: List<SourceData> = emptyList()): CoverCandidate? {
        val headers = resolveWebDavHeaders(mediaData, sourceList)
        val fallbackPath = if (mediaData.type == MediaLibType.WEBDAV) {
            resolveWebDavCoverPath(mediaData, sourceList)
        } else {
            mediaData.coverPath()
        }
        val showPath = if (mediaData.coverType == CoverType.DEFAULT) null else mediaData.showFile
        val path = firstUsable(showPath, fallbackPath) ?: return null
        return CoverCandidate(path, headers)
    }

    /** 解析「自定义封面 → 同名 cover」两级候选。 */
    fun resolveCover(showPath: String?, coverPath: String?): CoverCandidate? =
        firstUsable(showPath, coverPath)?.let { CoverCandidate(it) }

    fun setCover(imageView: ImageView, mediaData: MediaData, sourceList: List<SourceData> = emptyList()) {
        val candidate = resolveCover(mediaData, sourceList)
        if (candidate == null || !loadImage(imageView, candidate.path, candidate.headers)) {
            imageView.setImageResource(R.drawable.ic_media_default)
        }
    }

    fun setCover(imageView: ImageView, showPath: String?, coverPath: String?) {
        val candidate = resolveCover(showPath, coverPath)
        if (candidate == null || !loadImage(imageView, candidate.path)) {
            imageView.setImageResource(R.drawable.ic_media_default)
        }
    }

}

fun ImageView.setCover(showPath: String?, coverPath: String?) {
    CoverHelper.setCover(this, showPath, coverPath)
}

fun ImageView.setCover(mediaData: MediaData, sourceList: List<SourceData> = emptyList()) {
    CoverHelper.setCover(this, mediaData, sourceList)
}

fun ImageView.loadImage(path: String?, headers: Map<String, String> = emptyMap()): Boolean {
    return CoverHelper.loadImage(this, path, headers)
}
