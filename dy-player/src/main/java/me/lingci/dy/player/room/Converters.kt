package me.lingci.dy.player.room

import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.lib.base.storage.entity.StorageType


/**
 * MediaLibType类型转换器
 */
class MediaLibTypeConverter {
    @androidx.room3.ColumnTypeConverter
    fun fromMediaLibType(type: MediaLibType): Int {
        return type.value
    }

    @androidx.room3.ColumnTypeConverter
    fun toMediaLibType(value: Int): MediaLibType {
        return MediaLibType.fromValue(value)
    }
}

/**
 * StorageType类型转换器
 */
class StorageTypeConverter {
    @androidx.room3.ColumnTypeConverter
    fun fromStorageType(type: StorageType): String {
        return type.name
    }

    @androidx.room3.ColumnTypeConverter
    fun toStorageType(value: String): StorageType {
        return try {
            StorageType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            StorageType.LOCAL_STORAGE
        }
    }
}

/**
 * CoverType类型转换器
 */
class CoverTypeConverter {
    @androidx.room3.ColumnTypeConverter
    fun fromCoverType(type: CoverType): Int {
        return type.value
    }

    @androidx.room3.ColumnTypeConverter
    fun toCoverType(value: Int): CoverType {
        return CoverType.fromValue(value)
    }
}

/**
 * PlaySource类型转换器（播放来源，按 Int 值落库）
 */
class PlaySourceConverter {
    @androidx.room3.ColumnTypeConverter
    fun fromPlaySource(source: PlaySource): Int {
        return source.value
    }

    @androidx.room3.ColumnTypeConverter
    fun toPlaySource(value: Int): PlaySource {
        return PlaySource.fromValue(value)
    }
}