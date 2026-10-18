package me.lingci.dy.player.room.entity

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 应用元数据表（键值对）
 *
 * 迁移标记与 SP 完全解耦（决策 7）：
 * - data_schema_version：SP 数据 schema 版本（迁移目标 4）
 * - migrated_at：迁移完成时间戳 (ms)
 * - db_created_at：Room 库首次创建时间
 *
 * 备份恢复删 DB 文件时本表随之删除，必然触发重新迁移，不会重复插入。
 */
@Entity(tableName = "app_meta")
@Serializable
data class AppMeta(
    @PrimaryKey var key: String,
    var value: String,
    @ColumnInfo(name = "updated_at") var updatedAt: Long = System.currentTimeMillis()
)
