package me.lingci.dy.player.room

import androidx.room3.Room
import androidx.room3.Transactor.SQLiteTransactionType
import androidx.room3.migration.Migration
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import me.lingci.dy.player.PlayerApp
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.util.SeriesKey
import me.lingci.dy.player.util.refreshLibraryMetadata
import me.lingci.dy.player.room.dao.AppMetaDao
import me.lingci.dy.player.room.dao.FavoriteDao
import me.lingci.dy.player.room.dao.MediaDataDao
import me.lingci.dy.player.room.dao.MediaShuffleStateDao
import me.lingci.dy.player.room.dao.PlayHistoryDao
import me.lingci.dy.player.room.dao.PlaylistDao
import me.lingci.dy.player.room.dao.PlaylistItemDao
import me.lingci.dy.player.room.dao.PlayingListDao
import me.lingci.dy.player.room.dao.SourceDataDao
import me.lingci.dy.player.room.dao.VideoDataDao


/**
 * 数据库管理类，用于初始化和获取数据库实例
 *
 * Room 3 改造：
 * - 库名改为 dy_player.db（旧 dy_like_db 从未启用，直接弃用）
 * - 必须显式 setDriver（Room 3 移除了 SupportSQLite API）
 * - setQueryCoroutineContext 取代 Room 2.x 的 setQueryExecutor/setTransactionExecutor
 */
object DatabaseManager {

    private const val DB_NAME = "dy_player.db"

    @Volatile
    private var _db: AppDatabase? = null

    /** 惰性获取库实例：备份恢复 closeAndReset 后再次访问自动重建连接 */
    val db: AppDatabase
        get() = _db ?: Room.databaseBuilder(PlayerApp.getAppContext(), AppDatabase::class.java, DB_NAME)
            .setDriver(AndroidSQLiteDriver()) // 2.7.1 起无参构造，路径由 Room builder 传入 open()
            .setQueryCoroutineContext(Dispatchers.IO)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
            .also { _db = it }

    /** 备份恢复重建专用（§5.5/§5.6）：关闭当前连接并置空，配合 deleteDatabase 使用。
     *  必须先调用本方法再删库文件，否则旧连接继续写已删除的 inode 导致数据丢失 */
    fun closeAndReset() {
        _db?.close()
        _db = null
    }

    // DAO 访问器统一从这里拿，业务层不直接持有 DAO 实例
    val mediaDataDao: MediaDataDao get() = db.mediaDataDao()
    val sourceDataDao: SourceDataDao get() = db.sourceDataDao()
    val videoDataDao: VideoDataDao get() = db.videoDataDao()
    val playHistoryDao: PlayHistoryDao get() = db.playHistoryDao()
    val favoriteDao: FavoriteDao get() = db.favoriteDao()
    val playlistDao: PlaylistDao get() = db.playlistDao()
    val playlistItemDao: PlaylistItemDao get() = db.playlistItemDao()
    val shuffleStateDao: MediaShuffleStateDao get() = db.mediaShuffleStateDao()
    val appMetaDao: AppMetaDao get() = db.appMetaDao()
    val playingListDao: PlayingListDao get() = db.playingListDao()

    /**
     * 写事务包装（Room3）：块内的 suspend DAO 调用自动加入同一事务。
     * 注：Room3.0.3 的 withWriteTransaction 扩展 Kotlin 元数据未导出（字节码存在但不可解析），
     * 因此用等价的 useWriterConnection + Transactor.withTransaction（IMMEDIATE）实现。
     */
    suspend fun <R> inTransaction(block: suspend () -> R): R {
        return db.useWriterConnection { transactor ->
            transactor.withTransaction(SQLiteTransactionType.IMMEDIATE) {
                block()
            }
        }
    }

}

/**
 * v1 → v2：多季分组列 + 播放队列暂存表。
 *
 * 只加列 / 加表 / 加索引，不改任何既有数据与外键，老行 series_key 取默认空串
 * （按普通单级库处理），因此不需要回填，也不能用 destructive migration。
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec("ALTER TABLE media_data ADD COLUMN series_key TEXT NOT NULL DEFAULT ''")
        connection.exec("CREATE INDEX IF NOT EXISTS index_media_data_series_key ON media_data(series_key)")
        connection.exec(
            """
            CREATE TABLE IF NOT EXISTS playing_list_item (
                rowId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                session_id TEXT NOT NULL,
                sort_in_session INTEGER NOT NULL,
                season_label TEXT NOT NULL DEFAULT '',
                id TEXT NOT NULL,
                name TEXT NOT NULL,
                media_id TEXT NOT NULL,
                video_url TEXT NOT NULL,
                dm_url TEXT NOT NULL,
                preview TEXT NOT NULL,
                storage_type TEXT NOT NULL,
                parent_path TEXT NOT NULL,
                headers_json TEXT NOT NULL
            )
            """.trimIndent()
        )
        connection.exec(
            "CREATE INDEX IF NOT EXISTS index_playing_list_item_session_id " +
                "ON playing_list_item(session_id)"
        )
        connection.exec(
            "CREATE INDEX IF NOT EXISTS index_playing_list_item_session_id_sort_in_session " +
                "ON playing_list_item(session_id, sort_in_session)"
        )
        backfillSeriesKeys(connection)
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.exec(
            "ALTER TABLE media_data ADD COLUMN season_number INTEGER NOT NULL DEFAULT 2147483647"
        )
        connection.exec("ALTER TABLE media_data ADD COLUMN search_title TEXT NOT NULL DEFAULT ''")
        backfillLibraryMetadata(connection)
    }
}

/** 回填 SQL 分组、代表行排序和搜索解码文本。 */
private fun backfillLibraryMetadata(connection: SQLiteConnection) {
    val pending = mutableListOf<Pair<String, MediaData>>()
    val select = connection.prepare(
        "SELECT id, title, path, media_type, storage_id, series_key FROM media_data"
    )
    try {
        while (select.step()) {
            val id = select.getText(0)
            val media = MediaData(
                id = id,
                title = select.getText(1),
                path = select.getText(2),
                type = MediaLibType.fromValue(select.getInt(3)),
                storageId = if (select.isNull(4)) null else select.getText(4),
                seriesKey = select.getText(5)
            )
            media.refreshLibraryMetadata(preserveExistingKey = true)
            pending += id to media
        }
    } finally {
        select.close()
    }
    if (pending.isEmpty()) return
    val update = connection.prepare(
        "UPDATE media_data SET series_key = ?, season_number = ?, search_title = ? WHERE id = ?"
    )
    try {
        pending.forEach { (id, media) ->
            update.reset()
            update.clearBindings()
            update.bindText(1, media.seriesKey)
            update.bindLong(2, media.seasonNumber.toLong())
            update.bindText(3, media.searchTitle)
            update.bindText(4, id)
            update.step()
        }
    } finally {
        update.close()
    }
}

/**
 * 回填老数据的多季分组键。
 *
 * 加列只能让新入库的行打标；用户库里已存在的 `剧集/S01`、`剧集/S02` 两行若保持空串，
 * 升级后不会聚合成一剧一卡。这里按 path 的季目录名重新推导，无需用户重新扫描。
 */
private fun backfillSeriesKeys(connection: SQLiteConnection) {
    val pending = mutableListOf<Pair<String, String>>()
    val select = connection.prepare(
        "SELECT id, path, media_type, storage_id FROM media_data WHERE series_key = ''"
    )
    try {
        while (select.step()) {
            val id = select.getText(0)
            val path = select.getText(1)
            val type = MediaLibType.fromValue(select.getInt(2))
            val storageId = if (select.isNull(3)) null else select.getText(3)
            val key = SeriesKey.seriesKeyOf(path, type, storageId)
            if (key.isNotBlank()) pending += id to key
        }
    } finally {
        select.close()
    }
    if (pending.isEmpty()) return
    val update = connection.prepare("UPDATE media_data SET series_key = ? WHERE id = ?")
    try {
        pending.forEach { (id, key) ->
            update.reset()
            update.clearBindings()
            update.bindText(1, key)
            update.bindText(2, id)
            update.step()
        }
    } finally {
        update.close()
    }
}

private fun SQLiteConnection.exec(sql: String) {
    val statement = prepare(sql)
    try {
        statement.step()
    } finally {
        statement.close()
    }
}
