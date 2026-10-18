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

    private fun resolveSourceList(imageView: ImageView, sourceList: List<SourceData>): List<SourceData> {
        if (sourceList.isNotEmpty()) {
            return sourceList
        }
        // Room3：改为 TTL 惰性缓存——缓存过期触发后台刷新，本次绑定先返回当前缓存。
        // 首次冷启动时缓存为空，该次 WEBDAV 封面可能缺认证头（表现为占位图），回填后自愈。
        refreshSourceListIfNeeded()
        return cachedSourceList
    }

    private fun resolveWebDavHeaders(imageView: ImageView, mediaData: MediaData, sourceList: List<SourceData>): Map<String, String> {
        if (mediaData.type != MediaLibType.WEBDAV) {
            return emptyMap()
        }
        val sources = resolveSourceList(imageView, sourceList)
        val storageId = LibraryCompat.effectiveStorageId(mediaData, sources) ?: return emptyMap()
        val source = sources.find { it.id == storageId } ?: return emptyMap()
        if (source.username.isBlank() || source.password.isBlank()) {
            return emptyMap()
        }
        return mapOf(VideoData.TOKEN_KEY to Credentials.basic(source.username, source.password))
    }

    private fun resolveWebDavCoverPath(imageView: ImageView, mediaData: MediaData, sourceList: List<SourceData>): String? {
        if (mediaData.type != MediaLibType.WEBDAV) {
            return null
        }
        val sources = resolveSourceList(imageView, sourceList)
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

    fun setCover(imageView: ImageView, mediaData: MediaData, sourceList: List<SourceData> = emptyList()) {
        val headers = resolveWebDavHeaders(imageView, mediaData, sourceList)
        val fallbackPath = if (mediaData.type == MediaLibType.WEBDAV) {
            resolveWebDavCoverPath(imageView, mediaData, sourceList)
        } else {
            mediaData.coverPath()
        }
        // DEFAULT: 忽略 showFile，只查 cover.jpg，失败后显示占位图
        if (mediaData.coverType == CoverType.DEFAULT) {
            if (!loadImage(imageView, fallbackPath, headers)) {
                imageView.setImageResource(R.drawable.ic_media_default)
            }
            return
        }
        // CUSTOM/AUTO: 优先加载 showFile，再回退到 cover.jpg，最终显示占位图
        if (loadImage(imageView, mediaData.showFile, headers)) {
            return
        }
        if (!loadImage(imageView, fallbackPath, headers)) {
            imageView.setImageResource(R.drawable.ic_media_default)
        }
    }

    fun setCover(imageView: ImageView, showPath: String?, coverPath: String?) {
        if (showPath.isNullOrBlank() && coverPath.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_media_default)
            return
        }
        val success = loadImage(imageView, showPath)
        if (success.not()) {
            if (!loadImage(imageView, coverPath)) {
                imageView.setImageResource(R.drawable.ic_media_default)
            }
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
