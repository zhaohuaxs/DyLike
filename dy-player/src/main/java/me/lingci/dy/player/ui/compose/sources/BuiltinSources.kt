package me.lingci.dy.player.ui.compose.sources

import android.content.Context
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.ui.compose.browser.StorageVolumes
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.storage.entity.StorageType

/**
 * 内置资源库构造器（本地存储 / 串流播放）。
 *
 * 本地存储与外部存储在资源库列表里合并为一个入口：内部存储与存储卡的差异交给浏览页的
 * 卷切换器（见 StorageVolumes），不再各自占一行。资源库页与导航还原共用这里的构造逻辑，
 * 保证「列表里看到的入口」与「按 id 还原出来的入口」是同一个根路径。
 */
object BuiltinSources {

    /**
     * 本地存储入口，根路径取设备主存储。
     *
     * 这里刻意不枚举所有卷：卷检测要读 StatFs（磁盘 IO），属于浏览页的职责；
     * 本方法可能在组合期被调用，只做纯内存的路径计算。
     */
    fun local(context: Context): SourceData {
        val root = StorageVolumes.primaryRoot()
        return SourceData().apply {
            id = LibraryCompat.builtinLocalId()
            type = StorageType.LOCAL_STORAGE
            title = context.getString(R.string.title_local_storage)
            siteUrl = root
            schema = root
        }
    }

    fun stream(context: Context): SourceData = SourceData().apply {
        id = LibraryCompat.builtinStreamId()
        type = StorageType.STREAM_LINK
        title = context.getString(R.string.title_stream_link)
        siteUrl = "https?://media.mp4|.m3u8"
        schema = "https?://media.mp4|.m3u8"
    }

    fun all(context: Context): List<SourceData> = listOf(local(context), stream(context))

    /**
     * 按 id 还原内置源（内置源不落库，库内查不到时用它现场重建）。
     *
     * `builtinExternalId` 是合并前「外部存储」那一行的 id，老备份 / 老书签里可能还带着，
     * 这里映射到合并后的本地入口，而不是让页面退化成「资源已删除」。
     */
    fun byId(id: String, context: Context): SourceData? = when (id) {
        LibraryCompat.builtinLocalId(), LibraryCompat.builtinExternalId() -> local(context)
        else -> null
    }
}
