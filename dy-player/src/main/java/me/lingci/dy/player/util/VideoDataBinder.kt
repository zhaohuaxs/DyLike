package me.lingci.dy.player.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.logD

/**
 * 播放前凭据绑定器（SP→Room3 迁移决策 8）。
 *
 * 背景：VideoData.headers 保持 @Ignore 不落库，WebDAV/SMB 凭据只存于 source_data 表。
 * 从 Room 查询出来的视频（如播放列表、历史记录、媒体库扫描落库）headers 为空，
 * 播放入口需在此现算凭据回填 headers，避免起播 401。
 * 收益：凭据不随每集视频落库，迁移页与备份导出均不含敏感信息。
 *
 * token 计算复用 SourceData.toStorage().getToken()，与各存储实现运行时编码完全一致
 * （WebDav=Basic 认证、SMB=SmbAuthToken、本地=空串），避免重复实现凭据拼装逻辑。
 */
object VideoDataBinder {

    /**
     * 为单个视频绑定凭据（幂等：已有 token 或本地/在线流类型直接跳过）。
     *
     * @param videoData 待绑定的视频（通常来自 Room 查询，headers 为空）
     * @param mediaData 所属媒体库（可空：独立视频/历史单集场景），用于优先按 storageId 解析来源
     *
     * 内部读取 Room，必须在 IO 线程调用（调用方播放流程均已在 IO 协程内）。
     */
    suspend fun bindTokens(videoData: VideoData, mediaData: MediaData? = null) {
        // 仅远程存储需要凭据，本地文件/在线流直接跳过
        if (videoData.type != StorageType.WEBDAV && videoData.type != StorageType.SMB) {
            return
        }
        // 已有凭据不覆盖：调用方可能已通过 storage.getToken() 现填（如媒体详情扫描路径）
        if (!videoData.getToken().isNullOrBlank()) {
            return
        }
        val source = resolveSource(videoData, mediaData) ?: return
        val token = try {
            source.toStorage()?.getToken().orEmpty()
        } catch (e: Exception) {
            logD("bindTokens failed", e.message)
            ""
        }
        if (token.isNotBlank()) {
            videoData.putToken(token)
        }
    }

    /**
     * 解析视频对应的存储源：优先按媒体库记录的 storageId，
     * 兼容旧数据（无 storageId）按视频存储类型匹配——与 ShortVideoFileActions.resolveStorage 规则一致。
     */
    private suspend fun resolveSource(videoData: VideoData, mediaData: MediaData?): SourceData? {
        return withContext(Dispatchers.IO) {
            // 单次查库同时服务 storageId 解析与类型兜底匹配
            val sourceList = runCatching { DatabaseManager.sourceDataDao.list() }
                .getOrDefault(emptyList())
            if (sourceList.isEmpty()) {
                return@withContext null
            }
            val storageId = mediaData?.let { LibraryCompat.effectiveStorageId(it, sourceList) }
            if (storageId != null) {
                sourceList.find { it.id == storageId }
            } else {
                sourceList.find { it.type == videoData.type }
            }
        }
    }

}
