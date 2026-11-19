package me.lingci.dy.player.ui.compose.danmaku_binding

import android.content.Context
import me.lingci.dy.player.entity.VideoData
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import java.io.File
import java.util.UUID

/**
 * 详情页到绑定页的视频列表传递。
 *
 * `List<VideoData>` 远超路由/Bundle 的承载能力，这里沿用旧页面的做法：落一份临时 JSON，
 * 路由只传 mediaId 与临时文件路径；绑定页读完即删，避免缓存堆积。
 */
internal object DanmakuBindingTransfer {

    fun writeTempVideos(context: Context, videos: List<VideoData>): String {
        val file = AppFile(context).buildCache(".data/${UUID.randomUUID()}.json")
        file.parentFile?.mkdirs()
        FileOperator.writeText(file, JsonUtil.toJsonString(videos))
        return file.path
    }

    fun readTempVideos(path: String): List<VideoData> {
        if (path.isBlank()) return emptyList()
        val file = File(path)
        if (!file.exists()) return emptyList()
        return runCatching {
            JsonUtil.toList<VideoData>(FileOperator.readText(file))
        }.getOrDefault(emptyList()).also {
            runCatching { file.delete() }
        }
    }
}
