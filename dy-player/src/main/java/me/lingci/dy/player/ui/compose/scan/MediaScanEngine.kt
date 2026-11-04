package me.lingci.dy.player.ui.compose.scan

import android.content.Context
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.core.database.getStringOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.lingci.lib.base.util.isVideo
import java.io.File

/**
 * 扫描本地媒体的纯计算部分：目录遍历、MediaStore 查询、过滤规则。
 *
 * 这里不碰数据库、不碰 Compose，输入是根目录与过滤参数，输出是「目录 → 视频数」。
 * 与库内比对（需要 DAO）和入库写库都在 `MediaScanViewModel` 里。
 */
@Immutable
data class StorageRootOption(val title: String, val rootPath: String)

@Immutable
data class ScanFolderResult(
    val path: String,
    val name: String,
    val videoCount: Int,
    val existsInLibrary: Boolean = false,
    val selected: Boolean = false
)

data class ScanSummary(val totalVideoCount: Int, val folders: List<ScanFolderResult>)

/** 可扫描的存储根目录：主存储 + 外置存储（去重后按出现顺序） */
fun getStorageOptions(context: Context): List<StorageRootOption> {
    val options = mutableListOf<StorageRootOption>()
    val distinctRoots = linkedSetOf<String>()
    var externalIndex = 0
    context.getExternalFilesDirs(null).forEachIndexed { index, dir ->
        val rootPath = dir?.path?.substringBefore("/Android/data")?.normalizedPath().orEmpty()
        if (rootPath.isBlank() || !distinctRoots.add(rootPath)) {
            return@forEachIndexed
        }
        val title = if (index == 0) {
            "本地存储"
        } else {
            externalIndex += 1
            if (externalIndex == 1) "外部存储" else "外部存储 $externalIndex"
        }
        options.add(StorageRootOption(title, rootPath))
    }
    if (options.isEmpty()) {
        options.add(StorageRootOption("本地存储", "/storage/emulated/0"))
    }
    return options
}

suspend fun scanWithMediaStore(
    context: Context,
    rootPath: String?,
    skipNoMedia: Boolean,
    minFileSizeBytes: Long
): ScanSummary {
    val normalizedRoot = rootPath?.normalizedPath()
    val rootPrefix = normalizedRoot?.let { "$it/" }
    val folderCounter = linkedMapOf<String, Int>()
    var totalVideoCount = 0
    val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.SIZE)
    context.contentResolver.query(
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        projection, null, null,
        "${MediaStore.Video.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
        val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
        while (cursor.moveToNext()) {
            currentCoroutineContext().ensureActive()
            val fileSize = cursor.getLong(sizeIndex)
            if (fileSize < minFileSizeBytes) continue
            val filePath = cursor.getStringOrNull(dataIndex).orEmpty().normalizedPath()
            if (normalizedRoot != null && rootPrefix != null &&
                !filePath.startsWith(rootPrefix) && filePath != normalizedRoot
            ) {
                continue
            }
            val file = File(filePath)
            val parent = file.parentFile ?: continue
            if (shouldSkipFolder(parent, normalizedRoot, includeHidden = false, skipNoMedia = skipNoMedia)) continue
            val key = parent.path.normalizedPath()
            folderCounter[key] = (folderCounter[key] ?: 0) + 1
            totalVideoCount++
        }
    }
    return ScanSummary(totalVideoCount, folderCounter.toFolderResults())
}

suspend fun scanWithFileTree(
    rootPath: String,
    skipNoMedia: Boolean,
    minFileSizeBytes: Long
): ScanSummary {
    val normalizedRoot = rootPath.normalizedPath()
    val root = File(normalizedRoot)
    if (!root.exists() || !root.canRead()) return ScanSummary(0, emptyList())
    val stack = ArrayDeque<File>()
    val folderCounter = linkedMapOf<String, Int>()
    var totalVideoCount = 0
    stack.add(root)
    while (stack.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        val current = stack.removeLast()
        if (!current.exists() || !current.isDirectory || !current.canRead()) continue
        val children = current.listFiles() ?: continue
        var currentVideoCount = 0
        for (child in children) {
            currentCoroutineContext().ensureActive()
            if (child.isDirectory) {
                if (shouldSkipFolder(child, normalizedRoot, includeHidden = true, skipNoMedia = skipNoMedia)) continue
                stack.add(child)
            } else if (child.isVideo() && child.length() >= minFileSizeBytes) {
                currentVideoCount++
            }
        }
        if (currentVideoCount > 0 && current.path.normalizedPath() != normalizedRoot) {
            folderCounter[current.path.normalizedPath()] = currentVideoCount
            totalVideoCount += currentVideoCount
        }
    }
    return ScanSummary(totalVideoCount, folderCounter.toFolderResults())
}

private fun shouldSkipFolder(
    folder: File,
    rootPath: String?,
    includeHidden: Boolean,
    skipNoMedia: Boolean
): Boolean {
    val path = folder.path.normalizedPath()
    if (path.contains("/Android/") || path.endsWith("/Android")) return true
    if (!includeHidden && isHiddenFolder(folder, rootPath)) return true
    if (skipNoMedia && hasNoMediaFile(folder, rootPath)) return true
    return false
}

private fun isHiddenFolder(folder: File, rootPath: String?): Boolean {
    val normalizedRoot = rootPath?.normalizedPath()
    var current: File? = folder
    while (current != null) {
        val currentPath = current.path.normalizedPath()
        if (normalizedRoot != null && !currentPath.startsWith(normalizedRoot)) break
        if (current.name.startsWith(".") || current.isHidden) return true
        if (normalizedRoot != null && currentPath == normalizedRoot) break
        current = current.parentFile
    }
    return false
}

private fun hasNoMediaFile(folder: File, rootPath: String?): Boolean {
    val normalizedRoot = rootPath?.normalizedPath()
    var current: File? = folder
    while (current != null) {
        val currentPath = current.path.normalizedPath()
        if (normalizedRoot != null && !currentPath.startsWith(normalizedRoot)) break
        if (File(current, ".nomedia").exists()) return true
        if (normalizedRoot != null && currentPath == normalizedRoot) break
        current = current.parentFile
    }
    return false
}

private fun Map<String, Int>.toFolderResults(): List<ScanFolderResult> {
    return entries.map { entry ->
        val dir = File(entry.key)
        ScanFolderResult(
            path = dir.path.normalizedPath(),
            name = dir.name.ifBlank { dir.path.normalizedPath() },
            videoCount = entry.value
        )
    }
}

internal fun String.normalizedPath(): String = replace('\\', '/').trimEnd('/')
