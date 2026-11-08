package me.lingci.lib.base.storage.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.lib.base.okhttp.SslManager
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageConfig
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.logD
import me.lingci.lib.base.util.md5
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSession

/**
 * WebDAV文件系统实现
 */
class WebDavStorage(
    private val config: StorageConfig.WebDavStorageConfig,
    private val localCacheDir: String // 本地缓存目录
) : IStorage {

    // OkHttp客户端
    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            // 公网 / 反向代理的 WebDAV 站点 TLS 握手能到 3~5s（实测 alist + Cloudflare 4.8s），
            // 6s 会把「握手慢但能用」的服务器直接判死，这里放宽到 15s。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            //.addInterceptor(AuthInterceptor(encodeBasicAuth(config.username!!, config.password!!)))
            .retryOnConnectionFailure(true)

        // 添加认证拦截器
        if (!config.username.isNullOrBlank() && !config.password.isNullOrBlank()) {
            val credential = Credentials.basic(config.username, config.password)
            builder.addInterceptor { chain ->
                val request = chain.request()
                    .newBuilder()
                    .addHeader("Authorization", credential)
                    .build()
                chain.proceed(request)
            }
        }
        try {
            builder.sslSocketFactory(SslManager.sslContext.socketFactory, SslManager.trustManager)
                .hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            logD(e)
        }
        builder.build()
    }

    private val testBody = """
        <?xml version="1.0" encoding="utf-8" ?>
        <D:propfind xmlns:D="DAV:">
            <D:allprop/>
        </D:propfind>
    """.trimIndent()

    private companion object {
        /**
         * PROPFIND 的最大尝试次数：5xx / 断流时重试，4xx 不重试。
         * 实测 alist + 对象存储后端的单次请求约四到五成概率返回 500，两次都失败还有两成，
         * 给到三次能把「目录明明有内容却显示为空」压到一成以下。
         */
        const val PROPFIND_MAX_ATTEMPTS = 3

        /** 重试间隔：服务器 500 多为瞬时错误，短暂退避比立刻重打更容易命中正常节点。 */
        const val PROPFIND_RETRY_DELAY_MS = 400L

        /**
         * 固定日志 tag。
         *
         * [logD] 的 tag 取自接收者类型，在 suspend lambda 里会变成协程实现类（如 UndispatchedCoroutine），
         * 排查网络问题时按 tag 过滤不到，这里显式指定。
         */
        const val LOG_TAG = "WebDavStorage"
    }

    // WebDAV根URL
    private val rootUrl: String by lazy {
        if (config.url.endsWith("/")) config.url else "${config.url}/"
    }

    private val basePath: String by lazy {
        var base = config.url.substringAfter("://")
        if (base.endsWith("/").not()) {
            base = "$base/"
        }
        "/${base.substringAfter("/")}"
    }

    private val credential: String by lazy {
        if (!config.username.isNullOrBlank() && !config.password.isNullOrBlank()) {
            Credentials.basic(config.username, config.password)
        } else {
            ""
        }
    }

    // 日期格式化器
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)

    fun test(path: String): Pair<Boolean, String> {
        val webdavUrl = getWebDavUrl(path)
        return try {
            val request = Request.Builder()
                .url(webdavUrl)
                .method("PROPFIND", testBody.toRequestBody("text/xml; charset=UTF-8".toMediaTypeOrNull()))
                .addHeader("Depth", 0.toString())
                //.header("Authorization", "")
                .header("Host", webdavUrl.substringAfter("://").substringBefore('/'))
                .build()
            client.newCall(request).execute().use { response ->
                logD(response.code, response.message, response.header("WWW-Authenticate"))
                if (response.isSuccessful) {
                    Pair(true, response.message)
                } else {
                    Pair(false, response.message)
                }
            }
        } catch (e: Exception) {
            logD("test failed", e)
            Pair(false , e.toString())
        }
    }

    override fun rootFile(): FileEntity {
        return FileEntity(
            name = "主页",
            path = "/",
            type = StorageType.WEBDAV
        )
    }

    override fun fullPath(path: String): String {
        return "${rootUrl}${path.trimStart('/')}"
    }

    override fun getToken(): String {
        return credential
    }

    override suspend fun testConnect(): Boolean = withContext(Dispatchers.IO) {
        return@withContext test("/").first
    }

    override suspend fun listFiles(path: String, refresh: Boolean): Flow<List<FileEntity>> = flow {
        emit(fetchEntries(path, "listFiles"))
    }.flowOn(Dispatchers.IO)

    /**
     * 逐条发出目录条目。
     *
     * 旧实现是 channelFlow + `launch { send(it) }`：解析一结束通道就关闭，与子协程的 send 存在竞态，
     * 大目录会整片丢失（表现为「共 N 项」但列表为空）。改成解析完成后再顺序 emit，
     * 流式语义不变，且不会丢项。
     */
    override suspend fun listFile(
        path: String,
        refresh: Boolean
    ): Flow<FileEntity> = flow {
        fetchEntries(path, "listFile").forEach { emit(it) }
    }.flowOn(Dispatchers.IO)

    /**
     * 拉取目录条目（PROPFIND Depth: 1）。
     *
     * 失败语义：4xx（认证失败 / 路径不存在）直接返回空，不重试；5xx 与 IO 异常重试一次 ——
     * 实测部分公网 WebDAV（alist + 对象存储后端）单次请求约三成概率返回 500 或中途断流，
     * 不重试会让列表页整片空白。
     *
     * 无论成功失败都会关闭 [Response]，避免 OkHttp 连接池泄漏。
     */
    private suspend fun fetchEntries(path: String, tag: String): List<FileEntity> =
        withContext(Dispatchers.IO) {
            val webdavUrl = getWebDavUrl(path)
            repeat(PROPFIND_MAX_ATTEMPTS) { attempt ->
                try {
                    propfind(webdavUrl, testBody, 1).use { response ->
                        android.util.Log.d(LOG_TAG, "$tag ${response.code} ${response.message}")
                        if (response.isSuccessful) {
                            val fileList = mutableListOf<FileEntity>()
                            response.body?.byteStream()?.use { steam ->
                                parseListFilesXml(steam, path, fileList, null)
                            }
                            return@withContext fileList
                        }
                        if (response.code < 500) {
                            return@withContext emptyList()
                        }
                        android.util.Log.d(LOG_TAG, "$tag http ${response.code}, retry ${attempt + 1}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w(LOG_TAG, "$tag failed, attempt ${attempt + 1}", e)
                }
                if (attempt < PROPFIND_MAX_ATTEMPTS - 1) {
                    delay(PROPFIND_RETRY_DELAY_MS)
                }
            }
            emptyList()
        }

    override suspend fun getFileInfo(path: String): FileEntity? = withContext(Dispatchers.IO) {
        val webdavUrl = getWebDavUrl(path)
        return@withContext try {
            propfind(webdavUrl, "", 0).use { response ->
                logD("get file", webdavUrl, response.code, response.message)
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { steam ->
                        val fileList = mutableListOf<FileEntity>()
                        parseListFilesXml(steam, getParentPath(path), fileList, null)
                        fileList.firstOrNull()
                    }
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logD("file info failed", e)
            null
        }
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        val webdavUrl = getWebDavUrl(path)
        return@withContext try {
            val request = Request.Builder()
                .url(webdavUrl)
                .method("MKCOL", null)
                .build()
            client.newCall(request).execute().use { response ->
                logD("mkdir", webdavUrl, response.code, response.message)
                if (response.isSuccessful) {
                    true
                } else {
                    // 405表示目录已存在
                    if (response.code == 405) {
                        getFileInfo(path) != null
                    } else {
                        false
                    }
                }
            }
        } catch (e: Exception) {
            logD("mkdir failed", e)
            false
        }
    }

    override suspend fun rename(oldPath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val oldUrl = getWebDavUrl(oldPath)
        val parentPath = getParentPath(oldPath)
        val newUrl = getWebDavUrl("$parentPath/$newName")

        return@withContext try {
            val request = Request.Builder()
                .url(oldUrl)
                .method("MOVE", null)
                .addHeader("Destination", newUrl)
                .addHeader("Overwrite", "F")
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            logD("rename failed", e)
            false
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        val webdavUrl = getWebDavUrl(path)

        return@withContext try {
            val request = Request.Builder()
                .url(webdavUrl)
                .delete()
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            logD("delete failed", e)
            false
        }
    }

    override suspend fun copy(
        sourcePath: String,
        targetPath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val sourceUrl = getWebDavUrl(sourcePath)
        val targetUrl = getWebDavUrl(targetPath)

        return@withContext try {
            val request = Request.Builder()
                .url(sourceUrl)
                .method("COPY", null)
                .addHeader("Destination", targetUrl)
                .addHeader("Overwrite", "F")
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()

            // WebDAV COPY方法不支持进度回调，这里直接返回100%
            progressCallback(1.0f)
            success
        } catch (e: Exception) {
            logD("copy failed", e)
            false
        }
    }

    override suspend fun move(
        sourcePath: String,
        targetPath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val sourceUrl = getWebDavUrl(sourcePath)
        val targetUrl = getWebDavUrl(targetPath)
        println("$sourceUrl $targetUrl")
        return@withContext try {
            val request = Request.Builder()
                .url(sourceUrl)
                .method("MOVE", null)
                .addHeader("Destination", targetUrl)
                .addHeader("Overwrite", "F")
                .build()

            val response = client.newCall(request).execute()
            println("move ${response.code} ${response.message}")
            val success = response.isSuccessful
            response.close()

            // WebDAV MOVE方法不支持进度回调，这里直接返回100%
            progressCallback(1.0f)
            success
        } catch (e: Exception) {
            logD("move failed", e)
            false
        }
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val remoteUrl = getWebDavUrl(remotePath)
        val localFile = File(localPath)

        // 确保父目录存在
        localFile.parentFile?.mkdirs()

        return@withContext try {
            val request = Request.Builder()
                .url(remoteUrl)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return@withContext false
            }

            val body = response.body ?: return@withContext false
            val contentLength = body.contentLength()

            localFile.outputStream().use { output ->
                val source = body.source()
                val buffer = ByteArray(8192)
                var totalRead = 0L

                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break

                    output.write(buffer, 0, read)
                    totalRead += read

                    if (contentLength > 0) {
                        progressCallback((totalRead.toFloat() / contentLength).coerceAtMost(1.0f))
                    }
                }
                output.flush()
            }

            response.close()
            true
        } catch (e: Exception) {
            logD("download failed", e)
            false
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val localFile = File(localPath)
        if (!localFile.exists() || localFile.isDirectory) {
            return@withContext false
        }

        val remoteUrl = getWebDavUrl(remotePath)
        val remoteFolder = remotePath.substringBeforeLast("/").plus("/")

        val createDirectory = createDirectory(remoteFolder)
        println(createDirectory)
        if (!createDirectory) {
            return@withContext false
        }

        val fileLength = localFile.length()
        println(remoteUrl)


        return@withContext try {
            // 创建带进度的请求体
            /*val progressRequestBody = object : RequestBody() {
                override fun contentType(): MediaType? {
                    return "application/octet-stream".toMediaTypeOrNull()
                }

                override fun contentLength(): Long {
                    return fileLength
                }

                override fun writeTo(sink: BufferedSink) {
                    localFile.inputStream().use { source ->
                        sink.use {output ->
                            val buffer = ByteArray(8192)
                            var totalWritten = 0L
                            var read = source.read(buffer)
                            while (read > 0) {
                                output.write(buffer, 0, read)
                                totalWritten += read
                                progressCallback((totalWritten.toFloat() / fileLength).coerceAtMost(1.0f))
                                read = source.read(buffer)
                            }
                            output.flush()
                        }
                    }
                }
            }*/
            val progressRequestBody = localFile.asRequestBody("application/octet-stream".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(remoteUrl)
                .put(progressRequestBody)
                .build()

            val response = client.newCall(request).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            logD("upload failed", e)
            false
        }
    }

    override fun searchFiles(query: String, rootPath: String): Flow<List<FileEntity>> = flow {
        val results = mutableListOf<FileEntity>()
        searchWebDavDirectory(rootPath, query, results)
        emit(results)
    }.flowOn(Dispatchers.IO)

    override fun release() {
        CoroutineScope(Dispatchers.IO).launch {
            // 关闭OkHttp连接池
            client.connectionPool.evictAll()
        }
    }


    // 发送PROPFIND请求
    private fun propfind(url: String, body: String, depth: Int): Response {
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body.toRequestBody("text/xml; charset=UTF-8".toMediaTypeOrNull()))
            .addHeader("Depth", depth.toString())
            .header("Host", url.substringAfter("://").substringAfter(":/").substringBefore('/'))
            .build()
        return client.newCall(request).execute()
    }

    private fun keys() {
        val list : MutableList<String> = mutableListOf()
        list.add("getlastmodified")
        list.add("getcontentlength")
        list.add("creationdate")
        list.add("displayname")
        list.add("getcontentlanguage")
        list.add("getcontenttype")
        list.add("getetag")
        list.add("quota-available-bytes")
        list.add("quota-used-bytes")
        list.add("principal-URL")
        list.add("visible")
        list.add("readonly")
        list.add("hasthumbnail")
        list.add("has-preview")
    }

    // 解析文件列表XML响应
    private fun parseListFilesXml(steam: InputStream, parentPath: String, fileList: MutableList<FileEntity>, onValue:((file: FileEntity) -> Unit)?) {
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xpp = factory.newPullParser()
            xpp.setInput(steam, "UTF-8")

            var eventType = xpp.eventType
            var currentHref: String? = null
            var currentName: String? = null
            var currentIsDir = false
            var currentSize: Long = 0
            var currentLastModified: Long? = null
            var insideResourceType = false
            var propstatHasError = false
            var propstatTouchedFields = mutableSetOf<String>()
            if (eventType == XmlPullParser.START_DOCUMENT) {
                eventType = xpp.next()
            }

            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    when (xpp.name) {
                        "propstat" -> {
                            propstatHasError = false
                            propstatTouchedFields.clear()
                        }
                        "status" -> {
                            eventType = xpp.next()
                            if (eventType == XmlPullParser.TEXT) {
                                val statusText = xpp.text ?: ""
                                propstatHasError = !statusText.contains("200")
                            }
                        }
                        "href" -> {
                            eventType = xpp.next()
                            if (eventType == XmlPullParser.TEXT) {
                                currentHref = xpp.text
                            }
                        }
                        "displayname" -> {
                            eventType = xpp.next()
                            if (eventType == XmlPullParser.TEXT) {
                                currentName = xpp.text
                                propstatTouchedFields.add("name")
                            }
                        }
                        "resourcetype" -> {
                            insideResourceType = true
                        }
                        "collection" -> {
                            if (insideResourceType) {
                                currentIsDir = true
                                propstatTouchedFields.add("isDir")
                            }
                        }
                        "getcontentlength" -> {
                            eventType = xpp.next()
                            if (eventType == XmlPullParser.TEXT) {
                                currentSize = xpp.text.toLongOrNull() ?: 0
                                propstatTouchedFields.add("size")
                            }
                        }
                        "getlastmodified" -> {
                            eventType = xpp.next()
                            if (eventType == XmlPullParser.TEXT) {
                                currentLastModified = try {
                                    dateFormat.parse(xpp.text)?.time
                                } catch (e: Exception) {
                                    0
                                }
                                propstatTouchedFields.add("lastModified")
                            }
                        }
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    when (xpp.name) {
                        "resourcetype" -> {
                            insideResourceType = false
                        }
                        "propstat" -> {
                            if (propstatHasError) {
                                if ("isDir" in propstatTouchedFields) currentIsDir = false
                                if ("name" in propstatTouchedFields) currentName = null
                                if ("size" in propstatTouchedFields) currentSize = 0
                                if ("lastModified" in propstatTouchedFields) currentLastModified = null
                            }
                            propstatTouchedFields.clear()
                        }
                        "response" -> {
                            currentHref?.let { href ->
                                if (!currentIsDir && href.endsWith("/")) {
                                    currentIsDir = true
                                }
                                val filePath = hrefToStoragePath(href, parentPath)
                                val normalizedParent = normalizeStoragePath(parentPath)
                                val isDirectChild = filePath != normalizedParent && getParentPath(filePath) == normalizedParent
                                if (isDirectChild) {
                                    val fileName = currentName.takeIf { it.isNullOrBlank().not() }
                                        ?: filePath.substringAfterLast("/")
                                    val entity = FileEntity(
                                        id = href.md5(),
                                        title = fileName,
                                        name = fileName,
                                        path = filePath,
                                        fullPath = fullPath(filePath),
                                        isFile = !currentIsDir,
                                        size = currentSize,
                                        lastModified = currentLastModified ?: 0,
                                        type = config.type,
                                        storageId = config.id,
                                        mimeType = getMimeType(fileName)
                                    )
                                    if (onValue != null) {
                                        onValue.invoke(entity)
                                    } else {
                                        fileList.add(entity)
                                    }
                                }
                            }

                            currentHref = null
                            currentName = null
                            currentIsDir = false
                            currentSize = 0
                            currentLastModified = null
                            insideResourceType = false
                        }
                    }
                }
                eventType = xpp.next()
            }
        } catch (e: Exception) {
            logD("xml failed", e)
        }
    }

    // 获取WebDAV URL
    private fun getWebDavUrl(relativePath: String): String {
        return if (relativePath.isEmpty() || relativePath == "/") {
            rootUrl
        } else {
            val adjustedPath = relativePath.trimStart('/').replace("//", "/")
            "$rootUrl$adjustedPath"
        }
    }

    private fun hrefToStoragePath(href: String, parentPath: String): String {
        val hrefPath = extractUriPath(href)
        val normalizedHrefPath = if (hrefPath.startsWith("/")) {
            normalizeStoragePath(hrefPath)
        } else {
            normalizeStoragePath("${normalizeStoragePath(parentPath)}/$hrefPath")
        }
        val normalizedBasePath = normalizeStoragePath(extractUriPath(basePath))
        return when {
            normalizedBasePath == "/" -> normalizedHrefPath
            normalizedHrefPath == normalizedBasePath -> "/"
            normalizedHrefPath.startsWith("$normalizedBasePath/") -> {
                normalizeStoragePath(normalizedHrefPath.removePrefix(normalizedBasePath))
            }
            else -> normalizedHrefPath
        }
    }

    private fun extractUriPath(value: String): String {
        return try {
            URI.create(value).path ?: value.substringBefore('?').substringBefore('#')
        } catch (e: Exception) {
            value.substringBefore('?').substringBefore('#')
        }
    }

    private fun normalizeStoragePath(path: String): String {
        if (path.isBlank() || path == "/") return "/"
        var normalized = path.replace('\\', '/')
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/")
        }
        normalized = normalized.trimEnd('/')
        if (normalized.isEmpty()) return "/"
        return if (normalized.startsWith("/")) normalized else "/$normalized"
    }

    // 获取父路径
    private fun getParentPath(path: String): String {
        if (path == "/" || path.isEmpty()) return "/"
        val trimmed = path.trimEnd('/')
        val lastSlash = trimmed.lastIndexOf('/')
        return if (lastSlash <= 0) "/" else trimmed.substring(0, lastSlash)
    }

    // 获取MIME类型
    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            // 文本 / 文档
            "txt", "log", "md" -> "text/plain"
            "srt", "ass", "ssa", "vtt", "sub" -> "text/plain"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            // 图片
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heic"
            // 音频
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "ape" -> "audio/x-ape"
            "wma" -> "audio/x-ms-wma"
            // 视频（补全常见容器，避免 mimeType 为空影响后续判断）
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "mov" -> "video/quicktime"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ts", "m2ts", "mts" -> "video/mp2t"
            "mpg", "mpeg" -> "video/mpeg"
            "3gp" -> "video/3gpp"
            "rm", "rmvb" -> "video/vnd.rn-realvideo"
            "iso" -> "application/x-iso9660-image"
            // 压缩包
            "zip" -> "application/zip"
            "rar" -> "application/vnd.rar"
            "7z" -> "application/x-7z-compressed"
            else -> ""
        }
    }

    // 搜索WebDAV目录
    private suspend fun searchWebDavDirectory(path: String, query: String, results: MutableList<FileEntity>) {
        val fileList = fetchEntries(path, "search")
        logD("search file size", fileList.size)
        results.addAll(fileList.filter { file -> file.name.contains(query, ignoreCase = true) })
    }

}
