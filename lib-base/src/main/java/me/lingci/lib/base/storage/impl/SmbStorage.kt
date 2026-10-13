package me.lingci.lib.base.storage.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.SmbAuthException
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageConfig
import me.lingci.lib.base.storage.smb.SmbAuthToken
import me.lingci.lib.base.util.md5
import java.io.File
import java.util.Properties

/**
 * SMB文件系统实现
 * 依赖库：jcifs-ng (https://github.com/AgNO3/jcifs-ng)
 */
class SmbStorage(
    private val config: StorageConfig.SmbStorageConfig,
    private val localCacheDir: String // 本地缓存目录
) : IStorage {

    // SMB上下文，带超时配置
    private val cifsContext: CIFSContext by lazy {
        val credentials = SmbAuthToken.normalize(config.username, config.password, config.domain)
        val properties = Properties().apply {
            // 设置连接和响应超时（毫秒）
            setProperty("jcifs.smb.client.responseTimeout", "30000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
            setProperty("jcifs.smb.client.maxConnections", "4")
        }
        val baseCtx = BaseContext(PropertyConfiguration(properties))

        if (credentials.username.isNullOrBlank()) {
            baseCtx.withAnonymousCredentials()
        } else {
            val auth = NtlmPasswordAuthenticator(
                credentials.domain,
                credentials.username,
                credentials.password.orEmpty()
            )
            baseCtx.withCredentials(auth)
        }
    }

    // SMB根路径
    private val rootPath: String by lazy {
        val server = config.server.trim()
            .removePrefix("smb://")
            .substringBefore('/')
            .substringBefore(':')
        "smb://$server:${config.port}/$shareName/"
    }

    private val shareName: String by lazy {
        config.share.trim().trim('/')
    }

    override fun rootFile(): FileEntity {
        return FileEntity(
            id = rootPath.md5(),
            title = config.share,
            name = config.share,
            path = "/",
            isFile = false,
            size = 0,
            lastModified = 0,
            type = config.type,
            storageId = config.id,
            mimeType = ""
        )
    }

    override fun fullPath(path: String): String {
        return getSmbPath(path)
    }

    override fun getToken(): String {
        return SmbAuthToken.encode(config.username, config.password, config.domain)
    }

    override suspend fun testConnect(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val smbFile = SmbFile(rootPath, cifsContext)
            smbFile.exists()
        } catch (ex: SmbAuthException) {
            ex.printStackTrace()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun listFiles(path: String, refresh: Boolean): Flow<List<FileEntity>> = flow {
        val smbPath = getSmbDirectoryPath(path)

        try {
            val smbFile = SmbFile(smbPath, cifsContext)

            if (smbFile.exists() && smbFile.isDirectory) {
                val files = smbFile.listFiles()?.mapNotNull { smbFileToEntity(it) } ?: emptyList()
                emit(files)
            } else {
                emit(emptyList())
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emit(emptyList())
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listFile(
        path: String,
        refresh: Boolean
    ): Flow<FileEntity> = flow {
        val smbPath = getSmbDirectoryPath(path)
        try {
            val smbFile = SmbFile(smbPath, cifsContext)
            if (smbFile.exists() && smbFile.isDirectory) {
                smbFile.listFiles()?.forEach { file ->
                    smbFileToEntity(file)?.let { emit(it) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): FileEntity? = withContext(Dispatchers.IO) {
        return@withContext try {
            val smbFile = SmbFile(getSmbPath(path), cifsContext)
            if (smbFile.exists()) smbFileToEntity(smbFile) else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val smbFile = SmbFile(getSmbPath(path), cifsContext)
            if (smbFile.exists()) {
                smbFile.isDirectory
            } else {
                smbFile.mkdirs()
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun rename(oldPath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val oldFile = SmbFile(getSmbPath(oldPath), cifsContext)
            if (!oldFile.exists()) return@withContext false

            val parentPath = oldFile.parent ?: return@withContext false
            val newFile = SmbFile("$parentPath/$newName", cifsContext)

            oldFile.renameTo(newFile)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun delete(path: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val smbFile = SmbFile(getSmbPath(path), cifsContext)
            if (!smbFile.exists()) return@withContext true

            if (smbFile.isDirectory) {
                deleteDirectory(smbFile)
            } else {
                smbFile.delete()
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun copy(
        sourcePath: String,
        targetPath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val sourceFile = SmbFile(getSmbPath(sourcePath), cifsContext)
            val targetFile = SmbFile(getSmbPath(targetPath), cifsContext)

            if (!sourceFile.exists()) return@withContext false

            if (sourceFile.isDirectory) {
                copyDirectory(sourceFile, targetFile, progressCallback)
            } else {
                copyFile(sourceFile, targetFile, progressCallback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun move(
        sourcePath: String,
        targetPath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        // 先复制再删除实现移动
        val copySuccess = copy(sourcePath, targetPath, progressCallback)
        if (copySuccess) {
            return@withContext delete(sourcePath)
        }
        return@withContext false
    }

    override suspend fun download(
        remotePath: String,
        localPath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val remoteFile = SmbFile(getSmbPath(remotePath), cifsContext)
            val localFile = File(localPath)

            if (!remoteFile.exists() || remoteFile.isDirectory) return@withContext false

            // 确保父目录存在
            localFile.parentFile?.mkdirs()

            copyFromSmbToLocal(remoteFile, localFile, progressCallback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override suspend fun upload(
        localPath: String,
        remotePath: String,
        progressCallback: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val localFile = File(localPath)
            val remoteFile = SmbFile(getSmbPath(remotePath), cifsContext)

            if (!localFile.exists() || localFile.isDirectory) return@withContext false

            copyFromLocalToSmb(localFile, remoteFile, progressCallback)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    override fun searchFiles(query: String, rootPath: String): Flow<List<FileEntity>> = flow {
        val results = mutableListOf<FileEntity>()
        searchSmbDirectory(getSmbDirectoryPath(rootPath), query, results)
        emit(results)
    }.flowOn(Dispatchers.IO)

    override fun release() {
        // jcifs SingletonContext and derived credential contexts are shared; do not close them here.
    }

    // ==================== 辅助方法 ====================

    // 将SmbFile转换为FileEntity
    private fun smbFileToEntity(smbFile: SmbFile): FileEntity? {
        return try {
            val name = smbFile.name.trimEnd('/')
            FileEntity(
                id = smbFile.path.md5(),
                title = name,
                name = name,
                path = getRelativePath(smbFile),
                fullPath = smbFile.path,
                isFile = smbFile.isDirectory.not(),
                size = if (smbFile.isDirectory) 0 else smbFile.length(),
                lastModified = smbFile.lastModified(),
                type = config.type,
                storageId = config.id,
                mimeType = getMimeType(name) ?: ""
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // 获取SMB路径
    private fun getSmbPath(relativePath: String): String {
        val rawPath = relativePath.trim().replace('\\', '/')
        if (rawPath.startsWith("smb://", ignoreCase = true)) {
            return rawPath
        }
        val adjustedPath = normalizeRelativePath(relativePath)
        return if (adjustedPath.isEmpty()) {
            rootPath
        } else {
            "$rootPath$adjustedPath"
        }
    }

    private fun getSmbDirectoryPath(relativePath: String): String {
        val smbPath = getSmbPath(relativePath)
        return if (smbPath.endsWith('/')) smbPath else "$smbPath/"
    }

    private fun normalizeRelativePath(path: String): String {
        val rawPath = path.trim().replace('\\', '/')
        var normalized = if (rawPath.startsWith("smb://", ignoreCase = true)) {
            getRelativePathFromSmbUrl(rawPath)
        } else {
            rawPath
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/")
        }
        return stripSharePrefix(normalized.trim('/'))
    }

    // 获取相对路径
    private fun getRelativePath(smbFile: SmbFile): String {
        val relative = normalizeRelativePath(smbFile.path)
        return if (relative.isEmpty()) "/" else "/$relative"
    }

    private fun getRelativePathFromSmbUrl(smbPath: String): String {
        val pathPart = smbPath
            .substring(6)
            .substringAfter('/', "")
        return stripSharePrefix(pathPart.trim('/'))
    }

    private fun stripSharePrefix(path: String): String {
        val normalized = path.trim('/')
        if (normalized.isEmpty() || shareName.isEmpty()) return normalized

        val firstSegment = normalized.substringBefore('/')
        if (!firstSegment.equals(shareName, ignoreCase = true)) return normalized

        return normalized.substringAfter('/', "").trim('/')
    }

    // 获取MIME类型
    private fun getMimeType(fileName: String): String? {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return when (extension) {
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "mov" -> "video/quicktime"
            "ts" -> "video/mp2t"
            "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "ass", "ssa" -> "text/x-ssa"
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            else -> null
        }
    }

    // 删除目录（递归）
    private fun deleteDirectory(dir: SmbFile): Boolean {
        return try {
            if (!dir.isDirectory) return false

            val files = dir.listFiles() ?: return true
            for (file in files) {
                if (file.isDirectory) {
                    val success = deleteDirectory(file)
                    if (!success) return false
                } else {
                    file.delete()
                }
            }
            dir.delete()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 复制目录（递归）
    private fun copyDirectory(
        source: SmbFile,
        target: SmbFile,
        progressCallback: (Float) -> Unit
    ): Boolean {
        return try {
            if (!source.isDirectory) return false
            if (!target.exists()) {
                target.mkdirs()
            }

            val files = source.listFiles() ?: return true
            var total = files.size.toFloat()
            var copied = 0f

            for (file in files) {
                val targetFile = SmbFile(target.path + "/" + file.name, cifsContext)

                if (file.isDirectory) {
                    if (!copyDirectory(file, targetFile, progressCallback)) return false
                } else {
                    if (!copyFile(file, targetFile) {}) return false
                }

                copied++
                progressCallback(copied / total)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 复制SMB文件
    private fun copyFile(
        source: SmbFile,
        target: SmbFile,
        progressCallback: (Float) -> Unit = {}
    ): Boolean {
        return try {
            source.inputStream.use { input ->
                target.outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    val total = source.length()
                    var copied = 0L

                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        copied += bytesRead
                        progressCallback((copied.toFloat() / total).coerceAtMost(1.0f))
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 从SMB复制到本地
    private fun copyFromSmbToLocal(
        source: SmbFile,
        target: File,
        progressCallback: (Float) -> Unit
    ) {
        source.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(8192)
                val total = source.length()
                var copied = 0L

                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copied += bytesRead
                    progressCallback((copied.toFloat() / total).coerceAtMost(1.0f))
                }
            }
        }
    }

    // 从本地复制到SMB
    private fun copyFromLocalToSmb(
        source: File,
        target: SmbFile,
        progressCallback: (Float) -> Unit
    ) {
        source.inputStream().use { input ->
            target.outputStream.use { output ->
                val buffer = ByteArray(8192)
                val total = source.length()
                var copied = 0L

                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    copied += bytesRead
                    progressCallback((copied.toFloat() / total).coerceAtMost(1.0f))
                }
            }
        }
    }

    // 搜索SMB目录
    private fun searchSmbDirectory(smbPath: String, query: String, results: MutableList<FileEntity>) {
        try {
            val dir = SmbFile(smbPath, cifsContext)
            if (!dir.exists() || !dir.isDirectory) return

            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.name.contains(query, ignoreCase = true)) {
                    smbFileToEntity(file)?.let { results.add(it) }
                }

                if (file.isDirectory) {
                    searchSmbDirectory(file.path, query, results)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
