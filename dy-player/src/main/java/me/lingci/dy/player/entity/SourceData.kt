package me.lingci.dy.player.entity

import android.os.Parcelable
import androidx.core.net.toUri
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import androidx.room3.ColumnTypeConverters
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import me.lingci.dy.player.room.StorageTypeConverter
import me.lingci.lib.base.storage.IStorage
import me.lingci.lib.base.storage.entity.StorageConfig
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.storage.impl.LocalStorage
import me.lingci.lib.base.storage.impl.SmbStorage
import me.lingci.lib.base.storage.impl.WebDavStorage

/**
 *   @author : happyc
 *   time    : 2024/09/21
 *   desc    :
 *   version : 1.0
 */
@OptIn(InternalSerializationApi::class)
@Serializable
@Parcelize
@Entity(tableName = "source_data")
@ColumnTypeConverters(StorageTypeConverter::class)
data class SourceData(

    @PrimaryKey
    var id: String = "",
    var title: String = "",
    @ColumnInfo(name = "site_url")
    var siteUrl: String = "",
    var username: String = "guest",
    var password: String = "guest",
    var schema: String = "",
    var port: String = "",
    var index: Int = 0,
    @ColumnInfo(name = "storage_type", typeAffinity = ColumnInfo.INTEGER)
    var type: StorageType? = null

) : Parcelable {

    constructor() : this(id = "")

    fun baseUrl(): String {
        if (siteUrl.isBlank()) {
            return ""
        }
        val uri = siteUrl.toUri()
        return uri.scheme + "://" + uri.authority
    }

    fun storageType(): StorageType {
        if (type == null) {
            return StorageType.WEBDAV
        }
        return type!!
    }

    fun isCustomType(): Boolean {
        return type != StorageType.LOCAL_STORAGE && type != StorageType.STREAM_LINK
    }

    fun toStorage(): IStorage? {
        return when (type) {
            StorageType.LOCAL_STORAGE -> {
                LocalStorage(
                    StorageConfig.LocalStorageConfig(
                        id = id,
                        name = title,
                        rootPath = siteUrl
                    )
                )
            }

            StorageType.WEBDAV -> {
                WebDavStorage(
                    StorageConfig.WebDavStorageConfig(
                        id = id,
                        name = title,
                        url = siteUrl,
                        username = username,
                        password = password
                    ), ""
                )
            }

            StorageType.SMB -> {
                val rawServer = siteUrl.trim().removePrefix("smb://")
                val hostAndMaybePort = rawServer.substringBefore('/')
                val share = schema.trim().trim('/').ifBlank {
                    rawServer.substringAfter('/', "").trim('/')
                }
                SmbStorage(
                    StorageConfig.SmbStorageConfig(
                        id = id,
                        name = title,
                        server = hostAndMaybePort.substringBefore(':'),
                        port = port.toIntOrNull()
                            ?: hostAndMaybePort.substringAfter(':', "").toIntOrNull()
                            ?: 445,
                        share = share,
                        username = username.takeIf { it.isNotBlank() },
                        password = password.takeIf { it.isNotBlank() },
                        domain = null
                    ), ""
                )
            }

            else -> null
        }
    }

}
