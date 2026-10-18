package me.lingci.dy.player.room.dao

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Upsert
import me.lingci.dy.player.room.entity.AppMeta

/**
 * AppMeta DAO接口（迁移标记/元数据，与 SP 解耦）
 */
@Dao
interface AppMetaDao {

    @Query("SELECT value FROM app_meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(meta: AppMeta)

    /** 批量写入（备份恢复用） */
    @Insert
    suspend fun insertAll(metas: List<AppMeta>)

    @Query("SELECT * FROM app_meta")
    suspend fun list(): List<AppMeta>
}
