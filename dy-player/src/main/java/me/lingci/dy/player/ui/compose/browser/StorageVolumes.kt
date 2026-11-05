package me.lingci.dy.player.ui.compose.browser

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.model.formatFileSize
import java.io.File

/**
 * 一个可浏览的本地存储卷。
 *
 * [label] 给人看（内部存储 / 存储卡 / U 盘），[capacity] 是「可用 x · 共 y」的快照，
 * [path] 是切换后作为浏览根目录的那个路径。
 */
@Immutable
data class StorageVolume(
    val id: String,
    val label: String,
    val path: String,
    val capacity: String,
    val removable: Boolean
)

/**
 * 本地存储卷检测。
 *
 * 资源库里的「本地存储」只有一个入口，内部存储 / 存储卡在这里一起交给浏览页做内部切换，
 * 所以这里返回的是同一个入口下的多个根目录，而不是多个资源库。
 *
 * Android 11+ 用 [StorageManager.getStorageVolumes] 拿系统卷描述（带友好名称与可移动标记）；
 * 10 及以下没有该 API，退回 `getExternalFilesDirs` 反推挂载点——路径形如
 * `/storage/XXXX-XXXX/Android/data/<pkg>/files`，剥掉后半段就是该卷的根。
 *
 * 注意：本方法会读 `StatFs` 拿容量，属于磁盘 IO，只应在 ViewModel / IO 协程里调用。
 */
object StorageVolumes {

    fun detect(context: Context): List<StorageVolume> = runCatching {
        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fromManager(context)
        } else {
            emptyList()
        }
        volumes.ifEmpty { fromFilesDirs(context) }
    }.getOrDefault(emptyList())

    /** 主存储（内部存储）根目录；卷检测失败时作为兜底根路径。 */
    @Suppress("DEPRECATION")
    fun primaryRoot(): String = Environment.getExternalStorageDirectory().absolutePath

    @RequiresApi(Build.VERSION_CODES.R)
    private fun fromManager(context: Context): List<StorageVolume> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes
            .mapNotNull { volume ->
                val dir = volume.directory ?: return@mapNotNull null
                if (volume.state != Environment.MEDIA_MOUNTED) return@mapNotNull null
                StorageVolume(
                    id = volume.uuid ?: dir.absolutePath,
                    label = if (volume.isPrimary) {
                        context.getString(R.string.storage_internal)
                    } else {
                        volume.getDescription(context).ifBlank {
                            context.getString(R.string.storage_device)
                        }
                    },
                    path = dir.absolutePath,
                    capacity = capacityText(context, dir),
                    removable = !volume.isPrimary
                )
            }
            // 内部存储恒定在最上，其余按路径稳定排序
            .sortedWith(compareBy({ it.removable }, { it.path }))
    }

    @Suppress("DEPRECATION")
    private fun fromFilesDirs(context: Context): List<StorageVolume> {
        val primary = File(primaryRoot())
        val result = mutableListOf(
            StorageVolume(
                id = "primary",
                label = context.getString(R.string.storage_internal),
                path = primary.absolutePath,
                capacity = capacityText(context, primary),
                removable = false
            )
        )
        ContextCompat.getExternalFilesDirs(context, null).filterNotNull().forEach { dir ->
            val rootPath = dir.absolutePath.substringBefore("/Android/", "")
            if (rootPath.isBlank()) return@forEach
            if (rootPath == primary.absolutePath) return@forEach
            if (result.any { it.path == rootPath }) return@forEach
            val root = File(rootPath)
            result += StorageVolume(
                id = rootPath,
                label = context.getString(R.string.storage_external),
                path = rootPath,
                capacity = capacityText(context, root),
                removable = true
            )
        }
        return result
    }

    private fun capacityText(context: Context, dir: File): String = runCatching {
        val stat = StatFs(dir.absolutePath)
        val total = stat.blockCountLong * stat.blockSizeLong
        if (total <= 0L) return@runCatching ""
        val free = stat.availableBlocksLong * stat.blockSizeLong
        context.getString(R.string.storage_capacity, formatFileSize(free), formatFileSize(total))
    }.getOrDefault("")
}
