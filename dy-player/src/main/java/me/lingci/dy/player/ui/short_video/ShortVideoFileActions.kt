package me.lingci.dy.player.ui.short_video

import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.media_detail.RenameDialog
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.ShortTitleFormatter
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.view.ShortVideoControlView
import me.lingci.dy.player.view.ShortVideoController
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.util.logD
import java.io.File

/**
 * 短视频文件操作入口。
 *
 * 这里封装重命名、删除和分享，统一处理本地文件与媒体库存储源解析。Activity 通过回调
 * 提供当前播放位置和 UI 刷新动作，避免文件 IO 逻辑和页面播放编排混在一起。
 *
 * 列表/adapter/controller 均以函数形式注入，保证页面重建或字段重新赋值后仍能读到最新引用。
 */
class ShortVideoFileActions(
    private val activity: FragmentActivity,
    private val scope: CoroutineScope,
    private val spUtil: SpUtil,
    private val mediaData: () -> MediaData?,
    private val videoList: () -> MutableList<VideoData>,
    private val currentPosition: () -> Int,
    private val adapter: () -> ShortVideoAdapter,
    private val controller: () -> ShortVideoController,
    private val currentControlView: () -> ShortVideoControlView?,
    private val removeItem: (Int) -> Unit
) {

    /** 打开重命名弹窗，并在确认后执行本地或远程存储重命名。 */
    fun showRenameDialog() {
        val position = currentPosition()
        val videoList = videoList()
        if (position !in videoList.indices) return
        val videoData = videoList[position]
        val extension = videoData.videoUrl.substringAfterLast(".", "")
        val renameDialog = RenameDialog()
        renameDialog.arguments = RenameDialog.buildData(videoData.name)
        renameDialog.onRenameListener { newName ->
            renameVideo(position, "${newName}.${extension}")
        }
        renameDialog.show(activity.supportFragmentManager, renameDialog.tag)
    }

    /** 打开删除确认框，确认后删除当前视频并回调 Activity 移除页面数据。 */
    fun showDeleteConfirmDialog() {
        val position = currentPosition()
        if (position !in videoList().indices) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.hint_delete_video)
            .setMessage(R.string.hint_delete_video_desc)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                deleteVideo(position)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 分享当前视频；远程文件会先下载到外部缓存目录再通过 FileProvider 暴露。 */
    fun shareVideo() {
        val position = currentPosition()
        val videoList = videoList()
        if (position !in videoList.indices) return
        val videoData = videoList[position]

        scope.launch(Dispatchers.IO) {
            try {
                val file = if (videoData.videoUrl.startsWith("/") || videoData.videoUrl.startsWith("file://")) {
                    File(videoData.videoUrl)
                } else {
                    val cacheFile = File(activity.externalCacheDir, "share/${videoData.name}")
                    if (!cacheFile.exists()) {
                        cacheFile.parentFile?.mkdirs()
                        val storage = resolveStorage(videoData)
                        if (storage != null) {
                            storage.download(videoData.videoUrl, cacheFile.absolutePath)
                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(activity, "无法分享远程文件", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                    }
                    cacheFile
                }

                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(activity, "文件不存在", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.provider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) {
                    activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.hint_share_video)))
                }
            } catch (e: Exception) {
                logD("shareVideo failed", e.message)
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, activity.getString(R.string.action_share) + "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 在 IO 线程执行重命名，并在主线程同步列表标题和控制器标题。 */
    private fun renameVideo(position: Int, newName: String) {
        val videoList = videoList()
        if (position !in videoList.indices) return
        val videoData = videoList[position]
        val oldPath = videoData.videoUrl

        scope.launch(Dispatchers.IO) {
            val success = try {
                val storage = resolveStorage(videoData)
                if (storage != null) {
                    storage.rename(storage.toRelativePath(oldPath), newName)
                } else {
                    val oldFile = File(oldPath)
                    if (oldFile.exists()) {
                        val parent = oldFile.parentFile
                        if (parent != null) {
                            oldFile.renameTo(File(parent, newName))
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            } catch (e: Exception) {
                logD("renameVideo failed", e.message)
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    // oldPath 对 WebDav 是完整 URL（含 https:// 前缀），这里只替换末段文件名，
                    // 前缀保持不变，确保 videoUrl 仍是完整 URL，下次操作仍能被 toRelativePath 正确处理。
                    val newPath = oldPath.substring(0, oldPath.lastIndexOf("/") + 1) + newName
                    videoData.name = newName
                    videoData.videoUrl = newPath
                    adapter().notifyItemChanged(position)
                    val displayTitle = ShortTitleFormatter.format(newName)
                    currentControlView()?.setTitle(displayTitle)
                    controller().setTitle(displayTitle)
                    Toast.makeText(activity, activity.getString(R.string.action_rename) + "成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, activity.getString(R.string.action_rename) + "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 在 IO 线程执行删除，并在成功后移除当前 ViewPager 数据项。 */
    private fun deleteVideo(position: Int) {
        val videoList = videoList()
        if (position !in videoList.indices) return
        val videoData = videoList[position]
        val path = videoData.videoUrl

        scope.launch(Dispatchers.IO) {
            val success = try {
                val storage = resolveStorage(videoData)
                if (storage != null) {
                    storage.delete(storage.toRelativePath(path))
                } else {
                    val file = File(path)
                    if (file.exists()) file.delete() else true
                }
            } catch (e: Exception) {
                logD("deleteVideo failed", e.message)
                false
            }

            withContext(Dispatchers.Main) {
                if (success) {
                    removeItem(position)
                    Toast.makeText(activity, activity.getString(R.string.action_delete) + "成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, activity.getString(R.string.action_delete) + "失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 根据媒体库记录优先解析 storageId，兼容旧数据只按视频类型匹配存储源。 */
    private fun resolveStorage(videoData: VideoData): IStorage? {
        val sourceList = LibraryCompat.loadSources(spUtil)
        val storageId = mediaData()?.let { LibraryCompat.effectiveStorageId(it, sourceList) }
        val source = if (storageId != null) {
            sourceList.find { it.id == storageId }
        } else {
            sourceList.find { it.type == videoData.type }
        }
        return source?.toStorage()
    }

    /**
     * 将 videoData.videoUrl 转成 storage.delete/rename 期望的相对路径。
     *
     * 背景：WebDav 的 videoUrl 是完整 URL（如 https://dav.example.com/dav/movies/a.mp4），
     * 但 WebDavStorage.delete/rename 内部又会拼一次 rootUrl，必须先剥离掉前缀。
     * - WebDav：fullPath("") 返回 rootUrl，命中 startsWith → 剥离得到 "/movies/a.mp4"。
     * - 本地：LocalStorage.fullPath("") 返回 ""，root 为空 → 原样返回。
     *
     * 同时幂等：传入已经是相对路径的字符串也能正确处理（startsWith 不命中 → 原样返回）。
     */
    private fun IStorage.toRelativePath(fullUrl: String): String {
        val root = fullPath("")
        return if (root.isNotEmpty() && fullUrl.startsWith(root)) {
            fullUrl.removePrefix(root)
        } else {
            fullUrl
        }
    }
}
