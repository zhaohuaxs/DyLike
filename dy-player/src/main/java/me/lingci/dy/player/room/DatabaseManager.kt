package me.lingci.dy.player.room

import androidx.room3.Room
import androidx.room3.Transactor.SQLiteTransactionType
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers
import me.lingci.dy.player.PlayerApp
import me.lingci.dy.player.room.dao.AppMetaDao
import me.lingci.dy.player.room.dao.FavoriteDao
import me.lingci.dy.player.room.dao.MediaDataDao
import me.lingci.dy.player.room.dao.MediaShuffleStateDao
import me.lingci.dy.player.room.dao.PlayHistoryDao
import me.lingci.dy.player.room.dao.PlaylistDao
import me.lingci.dy.player.room.dao.PlaylistItemDao
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
