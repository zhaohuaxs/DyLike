package me.lingci.dy.player.room

import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.ColumnTypeConverters
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.dao.AppMetaDao
import me.lingci.dy.player.room.dao.FavoriteDao
import me.lingci.dy.player.room.dao.MediaDataDao
import me.lingci.dy.player.room.dao.MediaShuffleStateDao
import me.lingci.dy.player.room.dao.PlayHistoryDao
import me.lingci.dy.player.room.dao.PlaylistDao
import me.lingci.dy.player.room.dao.PlaylistItemDao
import me.lingci.dy.player.room.dao.SourceDataDao
import me.lingci.dy.player.room.dao.VideoDataDao
import me.lingci.dy.player.room.entity.AppMeta
import me.lingci.dy.player.room.entity.Favorite
import me.lingci.dy.player.room.entity.MediaShuffleStateEntity
import me.lingci.dy.player.room.entity.PlayHistory
import me.lingci.dy.player.room.entity.Playlist
import me.lingci.dy.player.room.entity.PlaylistItem

/**
 * Room数据库抽象类
 *
 * 7 张表：media_data / video_data / source_data / play_history / favorite /
 * playlist + playlist_item / media_shuffle_state / app_meta（键值表）
 */
@Database(
    entities = [
        MediaData::class,
        SourceData::class,
        VideoData::class,
        PlayHistory::class,
        Favorite::class,
        Playlist::class,
        PlaylistItem::class,
        MediaShuffleStateEntity::class,
        AppMeta::class
    ],
    version = 1,
    exportSchema = true
)
@ColumnTypeConverters(
    MediaLibTypeConverter::class,
    StorageTypeConverter::class,
    CoverTypeConverter::class,
    PlaySourceConverter::class
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDataDao(): MediaDataDao
    abstract fun sourceDataDao(): SourceDataDao
    abstract fun videoDataDao(): VideoDataDao
    abstract fun playHistoryDao(): PlayHistoryDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistItemDao(): PlaylistItemDao
    abstract fun mediaShuffleStateDao(): MediaShuffleStateDao
    abstract fun appMetaDao(): AppMetaDao
}
