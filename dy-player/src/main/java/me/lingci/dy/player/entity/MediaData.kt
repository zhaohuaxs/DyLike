package me.lingci.dy.player.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import android.os.Parcelable
import androidx.room3.ColumnInfo
import androidx.room3.Ignore
import androidx.room3.ColumnTypeConverters
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import me.lingci.dy.player.room.CoverTypeConverter
import me.lingci.dy.player.room.MediaLibTypeConverter
import me.lingci.dy.player.room.StorageTypeConverter
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.storage.entity.StorageType
import java.io.File
import java.util.Locale

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
    tableName = "media_data",
    // 删除存储源时媒体保留，storage_id 置空（SET_NULL）
    foreignKeys = [ForeignKey(
        entity = SourceData::class,
        parentColumns = ["id"],
        childColumns = ["storage_id"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("storage_id"), Index("media_type")]
)
@ColumnTypeConverters(MediaLibTypeConverter::class, StorageTypeConverter::class, CoverTypeConverter::class)
data class MediaData(

    @PrimaryKey
    var id: String = "",
    var title: String = "",
    @ColumnInfo(name = "show_file")
    var showFile: String = "",
    // 封面类型：DEFAULT-默认(允许自动匹配) CUSTOM-用户自选 AUTO-系统自动匹配
    @ColumnInfo(name = "cover_type", defaultValue = "0")
    @Serializable(with = CoverTypeSerializer::class)
    var coverType: CoverType = CoverType.DEFAULT,
    var path: String = "",
    // 历史遗留，实则为来源类型
    @ColumnInfo(name = "play_type")
    var playType: String = "",
    @ColumnInfo(name = "play_mode")
    var playMode: Int = 0,
    var index: Int = 0,
    // 0 历史记录 1 默认媒体库 2 本地自定义媒体库 3 在线媒体库
    @ColumnInfo(name = "media_type", typeAffinity = ColumnInfo.INTEGER)
    var type: MediaLibType = MediaLibType.DEFAULT,
    @ColumnInfo(name = "op_offset")
    var opOffset: Int = 0,
    @ColumnInfo(name = "ed_offset")
    var edOffset: Int = 0,
    @ColumnInfo(name = "storage_type", typeAffinity = ColumnInfo.INTEGER)
    var storageType: StorageType = StorageType.LOCAL_STORAGE,
    @Ignore
    var items: MutableList<VideoData> = mutableListOf(),
    @Ignore
    var selected: Boolean = false,
    @ColumnInfo(name = "play_last")
    var playLast: String = "",
    // 可空：无存储上下文的媒体（本地/在线）必须存 NULL 而非空串，
    // 否则 FK(storage_id→source_data, SET_NULL) 会因找不到父行而在插入时违约
    @ColumnInfo(name = "storage_id")
    var storageId: String? = null,
    @Ignore
    var dmFolder: String = "",
    @ColumnInfo(name = "pinned")
    var pinned: Boolean = false,
    @ColumnInfo(name = "pinned_at")
    var pinnedAt: Long = 0L,
    @ColumnInfo(name = "last_played_at")
    var lastPlayedAt: Long = 0L,
    // 孤儿视频归并产生的虚拟媒体（不独立展示在媒体库列表，仅作历史/收藏关联宿主）
    @ColumnInfo(name = "is_virtual")
    var isVirtual: Boolean = false

) : Parcelable {

    constructor() : this(id = "")

    constructor(file: File) : this(
        id = LibraryCompat.mediaId(MediaData(path = file.path, type = MediaLibType.LOCAL, storageType = StorageType.LOCAL_STORAGE)),
        title = file.name,
        path = file.path,
        type = MediaLibType.LOCAL,
        storageType = StorageType.LOCAL_STORAGE
    )

    fun isHistory(): Boolean {
        return type == MediaLibType.HISTORY
    }

    fun opValue(): String {
        if (opOffset == 0) {
            return "未设置"
        }
        return opOffset.formatTime()
    }

    fun edValue(): String {
        if (edOffset == 0) {
            return "未设置"
        }
        return edOffset.formatTime()
    }

    fun coverPath(): String {
        return if (path.startsWith("/")) {
            if (path.endsWith("/")) {
                "${path}cover.jpg"
            } else {
                "${path}/cover.jpg"
            }
        } else {
            ""
        }
    }

}

@OptIn(InternalSerializationApi::class)
@Serializable
@Parcelize
data class MediaTypeEntity(
    var storageId: String = "",
    var type: MediaLibType = MediaLibType.DEFAULT,
    @Ignore
    var title: String = "",
    var size: Int = 0,
    @Ignore
    var mediaList: MutableList<MediaData> = mutableListOf(),
    @Ignore
    var allMediaList: MutableList<MediaData> = mutableListOf()
): Parcelable {
    constructor() : this(title = "")
}

@OptIn(InternalSerializationApi::class)
@Serializable
data class MediaShuffleState(
    val selectedIds: Set<String> = emptySet(),
    val currentDisplayIds: List<String> = emptyList()
)

/**
 * 媒体库类型
 *
 * HISTORY/LIKE/PLAYLIST 三个值仅作为「视图模式」在 UI 层使用（虚拟页），
 * 不作为存储类型落库；可落库的媒体库类型为 DEFAULT/LOCAL/ONLINE/WEBDAV/SMB。
 */
@Serializable(with = MediaLibTypeSerializer::class) // 指定自定义序列化器
enum class MediaLibType(val value: Int) {

    HISTORY(0),
    DEFAULT(1),
    LOCAL(2),
    ONLINE(3),
    WEBDAV(4),
    LIKE(5),
    PLAYLIST(6),
    SMB(7);

    /** 是否为可落库的媒体库类型（排除 HISTORY/LIKE/PLAYLIST 三个视图模式） */
    val isLibraryType: Boolean
        get() = this != HISTORY && this != LIKE && this != PLAYLIST

    companion object {
        fun fromValue(value: Int): MediaLibType {
            return entries.firstOrNull { it.value == value } ?: DEFAULT
        }

        fun fromStorage(storageType: StorageType): MediaLibType {
            return when(storageType) {
                StorageType.WEBDAV -> WEBDAV
                StorageType.SMB -> SMB
                else -> LOCAL
            }
        }
    }

}

object MediaLibTypeSerializer : KSerializer<MediaLibType> {
    // 描述序列化的类型（这里是Int）
    override val descriptor =
        PrimitiveSerialDescriptor("MediaLibType", PrimitiveKind.INT)

    // 反序列化：从Int转为LibType
    override fun deserialize(decoder: Decoder): MediaLibType {
        val intValue = decoder.decodeInt() // 读取整数
        return MediaLibType.fromValue(intValue) // 转换为枚举
    }

    // 序列化：从LibType转为Int
    override fun serialize(encoder: Encoder, value: MediaLibType) {
        encoder.encodeInt(value.value) // 写入枚举对应的整数
    }
}

// 封面类型枚举
@Serializable(with = CoverTypeSerializer::class)
enum class CoverType(val value: Int) {
    DEFAULT(0),  // 默认封面，允许自动匹配缩略图
    CUSTOM(1),   // 用户自选封面（本地/网络），不自动匹配
    AUTO(2);     // 系统自动匹配的缩略图，不重复匹配

    companion object {
        fun fromValue(value: Int): CoverType {
            return entries.firstOrNull { it.value == value } ?: AUTO
        }
    }
}

object CoverTypeSerializer : KSerializer<CoverType> {
    override val descriptor =
        PrimitiveSerialDescriptor("CoverType", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): CoverType {
        return CoverType.fromValue(decoder.decodeInt())
    }

    override fun serialize(encoder: Encoder, value: CoverType) {
        encoder.encodeInt(value.value)
    }
}

fun Int.formatTime(): String {
    val seconds = this % 60
    val minutes = (this / 60) % 60
    return if (minutes > 9) {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
