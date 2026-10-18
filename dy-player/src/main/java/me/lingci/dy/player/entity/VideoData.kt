package me.lingci.dy.player.entity

import android.os.Parcelable
import androidx.core.net.toUri
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Ignore
import androidx.room3.PrimaryKey
import androidx.room3.ColumnTypeConverters
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import me.lingci.dy.player.room.StorageTypeConverter
import me.lingci.lib.base.okhttp.RedirectResolver
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.CodeUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.md5
import java.io.File

/**
 *   @author : happyc
 *   time    : 2024/09/21
 *   desc    :
 *   version : 1.0
 */
@OptIn(InternalSerializationApi::class)
@Serializable
@Parcelize
@Entity(
    tableName = "video_data",
    // 删除媒体时级联删除其全部剧集
    foreignKeys = [ForeignKey(
        entity = MediaData::class,
        parentColumns = ["id"],
        childColumns = ["media_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("media_id"), Index("sort_index")]
)
@ColumnTypeConverters(StorageTypeConverter::class)
data class VideoData(

    @PrimaryKey
    var id: String = "",
    var name: String = "",
    @ColumnInfo(name = "media_id")
    var mediaId: String = "",
    @ColumnInfo(name = "video_url")
    var videoUrl: String = "",
    var dmUrl: String = "",
    @Ignore
    var headers: MutableMap<String, String> = mutableMapOf(),
    var preview: String = "",
    @ColumnInfo(name = "storage_type", typeAffinity = ColumnInfo.INTEGER)
    var type: StorageType = StorageType.WEBDAV,
    // 上次播放标记：不落库，运行时由 mediaData.playLast == videoUrl 派生
    @Ignore
    var lastPlay: Boolean = false,
    @Ignore
    var like: Boolean = false,   // 收藏状态移到 favorite 表，此字段仅运行时使用
    @Ignore
    var selected: Boolean = false,
    @ColumnInfo(name = "parent_path")
    var parentPath: String = "",
    // 同媒体内的排序序号（扫描时按 parentPath 排序写入，查询 ORDER BY sort_index）
    @ColumnInfo(name = "sort_index")
    var sortIndex: Int = 0,

    ) : Parcelable {

    companion object {
        const val TOKEN_KEY: String = "Authorization"
    }

    constructor() : this(id = "")

    constructor(file: File) : this(
        name = file.name,
        videoUrl = file.path,
        type = StorageType.LOCAL_STORAGE,
        parentPath = file.parentFile?.name ?: ""
    )

    fun putToken(token: String) {
        headers[TOKEN_KEY] = token
    }

    fun getToken(): String? {
        return headers[TOKEN_KEY]
    }

    fun md5(): String {
        if (id.isNotBlank()) {
            return id
        }
        return CodeUtil.md5(videoUrl)
    }

    fun beanId(): String {
        if (id.isBlank()) {
            return videoUrl.md5()
        }
        return id
    }

    /**
     * 获取播放URL。当类型为WEBDAV且URL以http开头时，会同步执行网络请求解析重定向。
     * 注意：此方法可能阻塞，禁止在主线程调用，必须在IO线程执行。
     */
    fun playUrl(): String {
        Log.d(this@VideoData, this)
        return if (type == StorageType.WEBDAV && videoUrl.startsWith("http")) {
            RedirectResolver.getRedirectUrl(videoUrl, headers)
        } else {
            videoUrl
        }
    }

    fun is302(url: String): Boolean {
        if (url.startsWith("http")) {
            return try {
                url.toUri().host != videoUrl.toUri().host
            } catch (e: Exception) {
                false
            }
        }
        return false
    }

    val dmLink: String
        get() = "${videoUrl.substringBeforeLast(".")}.xml"

    val dmSubLink: String
        get() = "${videoUrl.substringBeforeLast(".")}_合并.xml"

    val srtLink: String
        get() = "${videoUrl.substringBeforeLast(".")}.srt"

    val assLink: String
        get() = "${videoUrl.substringBeforeLast(".")}.ass"

}