package me.lingci.dy.player.migrate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.AppMeta
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.JsonUtil

/**
 * 迁移触发判据（§5.1）：标记读 app_meta，与 SP 完全解耦。
 *
 * - app_meta['data_schema_version'] == 4 → 已迁移，正常启动
 * - 无标记且 SP 有旧数据                 → 走迁移页
 * - 无标记且 SP 全空（全新安装）         → 直接写标记，跳过迁移页
 */
object MigrationChecker {

    /** 迁移目标 schema 版本（SP 时代的 LibraryCompat.SCHEMA_VERSION=3 不再用作迁移开关） */
    const val TARGET_SCHEMA_VERSION = 4

    const val KEY_DATA_SCHEMA_VERSION = "data_schema_version"
    const val KEY_MIGRATED_AT = "migrated_at"
    const val KEY_DB_CREATED_AT = "db_created_at"

    /**
     * 启动时检查是否需要迁移。返回 true 表示需展示 MigrationActivity；
     * 全新安装（6 个 SP JSON 全空）会直接写入完成标记并返回 false。
     */
    suspend fun check(sp: SpUtil): Boolean = withContext(Dispatchers.IO) {
        if (!isMigrationPending()) {
            return@withContext false
        }
        // SP 是否真有数据：6 个 JSON 任一非空 → 需迁移；全空 → 直接落标记
        // 注意：SPManager.string 委托返回可空 String?，需 orEmpty() 后再解析
        val hasData = JsonUtil.toList<SourceData>(sp.sourceJson.orEmpty()).isNotEmpty() ||
                JsonUtil.toList<MediaData>(sp.mediaJson.orEmpty()).isNotEmpty() ||
                JsonUtil.toList<VideoData>(sp.historyJson.orEmpty()).isNotEmpty() ||
                JsonUtil.toList<VideoData>(sp.likeJson.orEmpty()).isNotEmpty() ||
                JsonUtil.toList<MediaData>(sp.playlistJson.orEmpty()).isNotEmpty() ||
                runCatching {
                    JsonUtil.toEntity<Map<String, MediaShuffleState>>(sp.mediaShuffleJson.orEmpty()).isNotEmpty()
                }.getOrDefault(false)
        if (!hasData) {
            markMigrated()
        }
        hasData
    }

    /** 是否尚未完成迁移（实验室入口显示判据：无标记或标记 < 4） */
    suspend fun isMigrationPending(): Boolean {
        val recorded = DatabaseManager.appMetaDao.get(KEY_DATA_SCHEMA_VERSION)?.toIntOrNull()
        return recorded == null || recorded < TARGET_SCHEMA_VERSION
    }

    /** 写入迁移完成标记（供全新安装/迁移执行器复用） */
    suspend fun markMigrated(now: Long = System.currentTimeMillis()) {
        val metaDao = DatabaseManager.appMetaDao
        metaDao.put(AppMeta(KEY_DATA_SCHEMA_VERSION, TARGET_SCHEMA_VERSION.toString()))
        metaDao.put(AppMeta(KEY_MIGRATED_AT, now.toString()))
        metaDao.put(AppMeta(KEY_DB_CREATED_AT, now.toString()))
    }
}
