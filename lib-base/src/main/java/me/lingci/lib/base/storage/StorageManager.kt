package me.lingci.lib.base.storage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.storage.entity.StorageConfig
import me.lingci.lib.base.storage.impl.LocalStorage
import me.lingci.lib.base.storage.impl.SmbStorage
import me.lingci.lib.base.storage.impl.WebDavStorage

/**
 * 文件系统管理器，用于管理所有类型的文件系统实例
 */
class StorageManager() {

    // 存储配置列表
    private val storageConfigs = mutableListOf<StorageConfig>()

    // 文件系统实例缓存
    private val fileSystemCache = mutableMapOf<String, IStorage>()

    // 本地缓存目录
    private val cacheDir by lazy {
        "/file_system_cache/"
    }

    /**
     * 添加存储配置
     */
    fun addStorageConfig(config: StorageConfig) {
        if (!storageConfigs.any { it.id == config.id }) {
            storageConfigs.add(config)
            // 创建对应的文件系统实例
            createFileSystem(config)
        }
    }

    /**
     * 移除存储配置
     */
    fun removeStorageConfig(storageId: String) {
        storageConfigs.removeAll { it.id == storageId }
        // 释放并移除文件系统实例
        fileSystemCache[storageId]?.release()
        fileSystemCache.remove(storageId)
    }

    /**
     * 获取所有存储配置
     */
    fun getStorageConfigs(): List<StorageConfig> {
        return storageConfigs.toList()
    }

    /**
     * 获取指定存储的文件系统
     */
    fun getFileSystem(storageId: String): IStorage? {
        return fileSystemCache[storageId] ?: run {
            storageConfigs.find { it.id == storageId }?.let { config ->
                createFileSystem(config)
                fileSystemCache[storageId]
            }
        }
    }

    /**
     * 搜索所有存储中的文件
     */
    fun searchAllStorages(query: String): Flow<List<FileEntity>> {
        // 一个站点断网不应让整次搜索失败，逐流兜底为空列表。
        val flows = storageConfigs.mapNotNull { config ->
            getFileSystem(config.id)?.searchFiles(query)?.catch { emit(emptyList()) }
        }
        // combine 的空集合不会发射任何值，这里显式给一次空结果。
        if (flows.isEmpty()) return flow { emit(emptyList()) }
        return combine(flows) { results -> results.flatMap { it } }
    }

    /**
     * 释放所有资源
     */
    fun releaseAll() {
        fileSystemCache.values.forEach { it.release() }
        fileSystemCache.clear()
    }

    /**
     * 创建文件系统实例
     */
    private fun createFileSystem(config: StorageConfig) {
        val fileSystem = when (config) {
            is StorageConfig.LocalStorageConfig -> {
                LocalStorage(config)
            }
            is StorageConfig.SmbStorageConfig -> {
                SmbStorage(config, cacheDir)
            }
            is StorageConfig.WebDavStorageConfig -> {
                WebDavStorage(config, cacheDir)
            }
            else -> null
        }
        fileSystem?.let {
            fileSystemCache[config.id] = fileSystem
        }
    }

}