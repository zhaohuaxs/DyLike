package me.lingci.dy.player.util

import android.content.Context
import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.dao.MediaDataDao
import me.lingci.dy.player.room.entity.MediaShuffleStateEntity
import me.lingci.dy.player.room.entity.Playlist
import me.lingci.dy.player.room.entity.PlaylistItem
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.md5

/**
 * 媒体库兼容门面。
 *
 * Room3 改造（v3 §4.0）：废弃「loadAll → 内存改 → saveAll」整 list 模式，
 * 业务函数改为 suspend + DAO 单条操作；纯函数（id 生成/排序/匹配）保持同步。
 */
object LibraryCompat {

    const val SCHEMA_VERSION = 3
    const val LEGACY_UNBOUND_STORAGE_ID = "__legacy_unbound__"
    private const val BUILTIN_LOCAL_ID = "__builtin_local__"
    private const val BUILTIN_EXTERNAL_ID = "__builtin_external__"
    private const val BUILTIN_STREAM_ID = "__builtin_stream__"

    fun builtinLocalId(): String = BUILTIN_LOCAL_ID

    fun builtinExternalId(): String = BUILTIN_EXTERNAL_ID

    fun builtinStreamId(): String = BUILTIN_STREAM_ID

    /**
     * 内置存储源数量：本地存储 + 串流播放。
     *
     * 本地与外部存储已合并为同一个入口（内部存储 / 存储卡在浏览页内部切换），
     * 所以这个数字不再随是否挂载存储卡变化。
     *
     * 资源库页面的列表由「内置源 + 库内源」拼成（见 SourcesScreen），
     * 统计数字必须同源，否则会出现「页面显示 3 个源、统计显示 0」的矛盾。
     */
    fun builtinSourceCount(@Suppress("UNUSED_PARAMETER") context: Context): Int = 2

    /**
     * SP 数据归一化（v2→v3）。仅在旧备份恢复（写回 SP 后重迁移）与迁移执行器预读时调用；
     * 迁移完成后 SP 的 6 个 JSON 不再读写。
     */
    fun migrateIfNeeded(sp: SpUtil) {
        if (sp.dataSchemaVersion >= SCHEMA_VERSION) {
            return
        }
        val sourceList = JsonUtil.toList<SourceData>(sp.sourceJson!!).toMutableList()
        val migratedSources = migrateSources(sourceList)
        val mediaList = JsonUtil.toList<MediaData>(sp.mediaJson!!).toMutableList()
        val migratedMedia = migrateMedia(mediaList, migratedSources)
        sp.sourceJson = JsonUtil.toJsonString(migratedSources)
        sp.mediaJson = JsonUtil.toJsonString(migratedMedia)
        sp.dataSchemaVersion = SCHEMA_VERSION
    }

    fun sourceId(source: SourceData): String {
        return if (source.id.isNotBlank()) {
            source.id
        } else {
            buildStableSourceId(source)
        }
    }

    fun mediaId(media: MediaData, sources: List<SourceData> = emptyList()): String {
        return if (media.id.isNotBlank()) {
            media.id
        } else {
            buildStableMediaId(media, sources)
        }
    }

    fun effectiveStorageId(media: MediaData, sources: List<SourceData>): String? {
        if (!media.storageId.isNullOrBlank()) {
            return media.storageId
        }
        if (media.type != MediaLibType.WEBDAV) {
            return null
        }
        return resolveLegacyStorageId(media, sources)
    }

    fun mediaBucketStorageId(media: MediaData, sources: List<SourceData>): String? {
        if (!isRemoteMedia(media.type)) {
            return null
        }
        return effectiveStorageId(media, sources)
            ?: if (media.type == MediaLibType.WEBDAV) LEGACY_UNBOUND_STORAGE_ID else null
    }

    fun sameMedia(a: MediaData, b: MediaData, sources: List<SourceData>): Boolean {
        if (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) {
            return true
        }
        if (a.type != b.type) {
            return false
        }
        return when (a.type) {
            MediaLibType.DEFAULT, MediaLibType.LOCAL -> normalize(a.path) == normalize(b.path)
            MediaLibType.WEBDAV, MediaLibType.SMB -> {
                mediaBucketStorageId(a, sources) == mediaBucketStorageId(b, sources)
                        && normalize(a.path) == normalize(b.path)
            }
            MediaLibType.ONLINE -> {
                val aUrl = a.items.firstOrNull()?.videoUrl.orEmpty()
                val bUrl = b.items.firstOrNull()?.videoUrl.orEmpty()
                if (aUrl.isNotBlank() && bUrl.isNotBlank()) {
                    a.playType == b.playType && aUrl == bUrl
                } else {
                    a.title.trim() == b.title.trim() && a.playType == b.playType
                }
            }
            else -> normalize(a.path) == normalize(b.path) && a.title.trim() == b.title.trim()
        }
    }

    private fun migrateSources(list: MutableList<SourceData>): MutableList<SourceData> {
        val usedIds = mutableSetOf<String>()
        return list.map { source ->
            if (source.type == null) {
                source.type = StorageType.WEBDAV
            }
            if (source.id.isBlank()) {
                source.id = buildStableSourceId(source)
            }
            source.id = ensureUniqueId(source.id, usedIds)
            source
        }.toMutableList()
    }

    private fun migrateMedia(
        list: MutableList<MediaData>,
        sources: List<SourceData>
    ): MutableList<MediaData> {
        val usedIds = mutableSetOf<String>()
        return list.map { media ->
            // 解析不到保持 null（空串会让 FK(storage_id→source_data) 在落库时违约）
            if (media.type == MediaLibType.WEBDAV && media.storageId.isNullOrBlank()) {
                media.storageId = resolveLegacyStorageId(media, sources)
            }
            if (media.id.isBlank()) {
                media.id = buildStableMediaId(media, sources)
            }
            media.id = ensureUniqueId(media.id, usedIds)
            // v2→v3: 为已有 MediaData 推断 coverType
            // 有 showFile 的推断为 CUSTOM（保守处理，避免被自动匹配覆盖）
            // 无 showFile 的保持 DEFAULT（允许自动匹配）
            if (media.coverType == CoverType.DEFAULT && media.showFile.isNotBlank()) {
                media.coverType = CoverType.CUSTOM
            }
            media
        }.toMutableList()
    }

    private fun resolveLegacyStorageId(media: MediaData, sources: List<SourceData>): String? {
        val webdavSources = sources.filter { it.storageType() == StorageType.WEBDAV }
        return if (webdavSources.size == 1) {
            webdavSources.first().id
        } else {
            null
        }
    }

    private fun buildStableSourceId(source: SourceData): String {
        val raw = listOf(
            "source",
            source.storageType().name,
            normalize(source.siteUrl),
            source.username.trim()
        ).joinToString("|")
        return raw.md5()
    }

    private fun buildStableMediaId(media: MediaData, sources: List<SourceData>): String {
        val raw = when (media.type) {
            MediaLibType.DEFAULT, MediaLibType.LOCAL -> {
                "media|LOCAL|${normalize(media.path)}"
            }
            MediaLibType.WEBDAV, MediaLibType.SMB -> {
                val storageId = mediaBucketStorageId(media, sources) ?: LEGACY_UNBOUND_STORAGE_ID
                "media|${media.type.name}|$storageId|${normalize(media.path)}"
            }
            MediaLibType.ONLINE -> {
                val firstUrl = media.items.firstOrNull()?.videoUrl.orEmpty()
                "media|ONLINE|${media.playType}|$firstUrl|${media.title.trim()}"
            }
            else -> {
                "media|${media.type.name}|${media.title.trim()}|${normalize(media.path)}"
            }
        }
        return raw.md5()
    }

    private fun ensureUniqueId(id: String, usedIds: MutableSet<String>): String {
        if (usedIds.add(id)) {
            return id
        }
        var index = 1
        while (true) {
            val candidate = "$id#$index"
            if (usedIds.add(candidate)) {
                return candidate
            }
            index++
        }
    }

    private fun normalize(value: String): String {
        return value.trim().replace('\\', '/').trimEnd('/')
    }

    private fun isRemoteMedia(type: MediaLibType): Boolean {
        return type == MediaLibType.WEBDAV || type == MediaLibType.SMB
    }

    // ---- 媒体入库（新增 / 编辑 / 扫描共用唯一入口）----

    enum class MediaSaveResult {
        /** 新增成功 */
        Inserted,

        /** 编辑成功 */
        Updated,

        /** 库内已存在同一媒体（新增场景） */
        Duplicate
    }

    /**
     * 媒体库写入唯一入口。
     *
     * 取代此前散落在各处的「list 前 200 条去重 + delete 再 insert」写法，统一处理三件事：
     * ① 去重覆盖**全库**（`listLibrary()`），不受分页影响；
     * ② 编辑走 upsert 而非 delete+insert —— `video_data.media_id` 是 FK CASCADE，
     *    先删父行会把该媒体的分集全部级联删除，而编辑场景没有 items 可重建；
     * ③ 分集重建只删「确实消失」的条目（按 beanId 比对），避免 favorite / playlist_item
     *    的外键 CASCADE 连带清空用户收藏。
     *
     * @param items 需要落库的分集（扫描/导入场景）；为空表示不动分集
     * @param isEdit true 表示编辑已有媒体，命中另一条同源记录时归并到该记录 id
     */
    suspend fun saveMedia(
        media: MediaData,
        sources: List<SourceData>,
        items: List<VideoData> = emptyList(),
        isEdit: Boolean = false
    ): MediaSaveResult {
        val mediaDao = DatabaseManager.mediaDataDao
        val videoDao = DatabaseManager.videoDataDao

        if (media.id.isBlank()) {
            media.id = buildStableMediaId(media, sources)
        }

        // 全库比对，排除自身；不能再用「前 N 条」快照，否则超出部分永远判不出重复
        val duplicate = mediaDao.listLibrary().firstOrNull {
            it.id != media.id && sameMedia(it, media, sources)
        }
        if (!isEdit && duplicate != null) {
            return MediaSaveResult.Duplicate
        }
        if (isEdit && duplicate != null) {
            // 改名/改路径后稳定 id 变化，归并到库内既有那条，避免同一媒体出现两行
            media.id = duplicate.id
        }

        val normalized = if (items.isEmpty()) {
            emptyList()
        } else {
            sortByParentPath(items.toMutableList()).mapIndexed { index, video ->
                video.apply {
                    sortIndex = index
                    if (id.isBlank()) id = beanId()
                    mediaId = media.id
                }
            }
        }
        val staleIds = if (normalized.isEmpty()) {
            emptyList()
        } else {
            val incoming = normalized.mapTo(mutableSetOf()) { it.id }
            videoDao.listByMedia(media.id).map { it.id }.filterNot { it in incoming }
        }

        DatabaseManager.inTransaction {
            mediaDao.upsert(media)
            if (staleIds.isNotEmpty()) {
                videoDao.deleteByIds(staleIds)
            }
            if (normalized.isNotEmpty()) {
                videoDao.upsertAll(normalized)
            }
        }

        return if (isEdit) MediaSaveResult.Updated else MediaSaveResult.Inserted
    }

    /** 按同源规则在**全库**中定位已存在的媒体（编辑页回填、导入查重共用） */
    suspend fun findMedia(media: MediaData, sources: List<SourceData>): MediaData? {
        return DatabaseManager.mediaDataDao.listLibrary().firstOrNull {
            it.id == media.id || sameMedia(it, media, sources)
        }
    }

    // ---- 视频落库（video_data.media_id 是 FK → media_data，写入前必须兜底）----

    /**
     * 确保视频可安全落库（video_data 的 media_id 是 FK → media_data）：
     * 父行已存在则以库内 media_id 为权威值回填内存对象；mediaId 无效（空或不存在）时归入
     * 按存储类型分桶的虚拟宿主媒体（与迁移 §1.5 规则 4 同公式，跨会话复用同一桶，不展示在媒体库列表）
     *
     * 写入方（播放历史 / 收藏 / 播放列表条目）共用本方法，保证：
     * ① video.id 归一为 beanId()，与 playlist_item.video_id 等外键取值同源；
     * ② 调用方拿到的 videoData.mediaId 绝不写空串/悬空 id
     *
     * 注意（短视频「添加喜欢」静默失败根因）：方法返回后调用方会把 videoData.mediaId 直接
     * 写进 favorite.media_id 等带 FK 的列，因此**早退分支同样必须回填**——文件扫描出来的
     * 视频 mediaId 恒为空串（VideoData(file) 构造器不设该字段），若库内已有该行而直接 return，
     * 调用方就会拿空串去撞 media_data 外键，抛 FOREIGN KEY constraint failed。
     */
    suspend fun ensureVideoParent(videoData: VideoData) {
        videoData.id = videoData.beanId()
        val videoDao = DatabaseManager.videoDataDao
        val mediaDao = DatabaseManager.mediaDataDao
        val existing = videoDao.getById(videoData.id)
        if (existing != null) {
            // 父行已存在：以库内 media_id 为权威值回填内存对象
            val resolved = resolveMediaId(mediaDao, existing.mediaId, videoData.mediaId, videoData.type)
            if (resolved != existing.mediaId) {
                // 库内 media_id 本身为空/悬空（历史脏数据）：纠正并回写
                videoDao.updateMediaId(videoData.id, resolved)
            }
            videoData.mediaId = resolved
            return
        }
        val mediaId = videoData.mediaId
        if (mediaId.isNotBlank() && mediaDao.getById(mediaId) != null) {
            videoDao.upsert(videoData)
            return
        }
        // mediaId 无效 → 归入虚拟宿主（迁移器用相同 id 公式，保证跨会话复用同一桶）
        videoData.mediaId = ensureVirtualMedia(mediaDao, videoData.type)
        videoDao.upsert(videoData)
    }

    /**
     * 解析 video_data.media_id 的权威值：优先库内值（库内行才是权威），
     * 其次内存对象带的有效值，最后兜底虚拟宿主。
     * 返回值必定是 media_data 中真实存在的主键——**绝不会返回空串**（空串去撞外键必违约）。
     */
    private suspend fun resolveMediaId(
        mediaDao: MediaDataDao,
        dbMediaId: String,
        memoryMediaId: String,
        storageType: StorageType
    ): String {
        if (dbMediaId.isNotBlank() && mediaDao.getById(dbMediaId) != null) {
            return dbMediaId
        }
        if (memoryMediaId.isNotBlank() && mediaDao.getById(memoryMediaId) != null) {
            return memoryMediaId
        }
        return ensureVirtualMedia(mediaDao, storageType)
    }

    /** 找/建按存储类型分桶的虚拟宿主媒体，返回其 id（与迁移 §1.5 规则 4 同公式） */
    private suspend fun ensureVirtualMedia(mediaDao: MediaDataDao, storageType: StorageType): String {
        val virtualId = "virtual|${storageType.name}|0".md5()
        if (mediaDao.getById(virtualId) == null) {
            mediaDao.insert(
                MediaData(
                    id = virtualId,
                    title = "未归类",
                    type = MediaLibType.fromStorage(storageType),
                    storageType = storageType,
                    isVirtual = true
                )
            )
        }
        return virtualId
    }

    // ---- 播放列表（suspend + DAO 单条操作，替代 SP 整 list 模式）----

    /** 新建播放列表 */
    suspend fun createPlaylist(title: String, playMode: Int = 0): Playlist {
        val playlist = Playlist(
            id = "playlist|${title.trim()}|${System.currentTimeMillis()}".md5(),
            title = title.trim(),
            playMode = playMode
        )
        DatabaseManager.playlistDao.insert(playlist)
        return playlist
    }

    /** 更新播放列表播放模式 */
    suspend fun updatePlaylistMode(playlistId: String, playMode: Int) {
        DatabaseManager.playlistDao.updatePlayMode(playlistId, playMode, System.currentTimeMillis())
    }

    /** 删除播放列表（playlist_item 由 FK CASCADE 级联清理） */
    suspend fun deletePlaylist(playlistId: String) {
        val playlist = DatabaseManager.playlistDao.getById(playlistId) ?: return
        DatabaseManager.playlistDao.delete(playlist)
    }

    /**
     * 向播放列表添加视频（按 beanId 查重；PlaylistItem.video_id 是 FK → video_data，
     * 故插入条目前先 ensureVideoParent 兜底 media 父行并 upsert video_data）
     */
    suspend fun addToPlaylist(playlistId: String, videos: List<VideoData>) {
        val playlistDao = DatabaseManager.playlistDao
        if (playlistDao.getById(playlistId) == null) {
            return
        }
        val itemDao = DatabaseManager.playlistItemDao
        val existingIds = itemDao.listByPlaylist(playlistId).map { it.videoId }.toMutableSet()
        var order = itemDao.countByPlaylist(playlistId)
        DatabaseManager.inTransaction {
            videos.forEach { video ->
                val vid = video.beanId()
                if (vid !in existingIds) {
                    // mediaId 为空/悬空时兜底虚拟宿主，并把 video.id 归一为 vid
                    ensureVideoParent(video)
                    itemDao.insert(
                        PlaylistItem(
                            playlistId = playlistId,
                            mediaId = video.mediaId.ifBlank { null },
                            videoId = vid,
                            sortOrder = order
                        )
                    )
                    existingIds.add(vid)
                    order++
                }
            }
        }
    }

    /** 从播放列表移除指定视频条目 */
    suspend fun removeFromPlaylist(playlistId: String, videoIds: List<String>) {
        val itemDao = DatabaseManager.playlistItemDao
        DatabaseManager.inTransaction {
            videoIds.forEach { itemDao.deleteByPlaylistAndVideo(playlistId, it) }
        }
    }

    /**
     * 同步播放列表：清理 LOCAL_STORAGE 类型但本地文件已不存在的条目。
     * 返回删除的条目数（0 表示列表已干净，无需刷新）。
     */
    suspend fun syncPlaylistVideos(playlistId: String): Int {
        val itemDao = DatabaseManager.playlistItemDao
        val videoDao = DatabaseManager.videoDataDao
        val items = itemDao.listByPlaylist(playlistId)
        val staleIds = items.filter { item ->
            // 条目对应视频为本地存储且文件不存在才清理（类型取自视频父行）
            val video = videoDao.getById(item.videoId)
            video != null && video.type == StorageType.LOCAL_STORAGE &&
                    !java.io.File(video.videoUrl).exists()
        }.map { it.videoId }
        if (staleIds.isEmpty()) {
            return 0
        }
        DatabaseManager.inTransaction {
            staleIds.forEach { itemDao.deleteByPlaylistAndVideo(playlistId, it) }
        }
        return staleIds.size
    }

    /** 播放列表重命名 */
    suspend fun renamePlaylist(playlistId: String, newTitle: String) {
        DatabaseManager.playlistDao.updateTitle(playlistId, newTitle.trim(), System.currentTimeMillis())
    }

    // ---- 随机播放状态（suspend + DAO，替代 SP 的 mediaShuffleJson）----

    /** 读取全部随机播放状态（entity 的 JSON 列 → 业务模型） */
    suspend fun loadShuffleStates(): MutableMap<String, MediaShuffleState> {
        return DatabaseManager.shuffleStateDao.list().mapNotNull { entity ->
            runCatching {
                val selected = JsonUtil.toEntity<Set<String>>(entity.selectedIds).orEmpty()
                val display = JsonUtil.toEntity<List<String>>(entity.currentDisplayIds).orEmpty()
                entity.mediaId to MediaShuffleState(selected, display)
            }.getOrNull()
        }.toMap().toMutableMap()
    }

    /** 批量保存随机播放状态（@Upsert 覆盖同 mediaId；残留行由媒体删除时 FK CASCADE 清理） */
    suspend fun saveShuffleStates(states: Map<String, MediaShuffleState>) {
        val entities = states.map { (mediaId, state) ->
            MediaShuffleStateEntity(
                mediaId = mediaId,
                selectedIds = JsonUtil.toJsonString(state.selectedIds),
                currentDisplayIds = JsonUtil.toJsonString(state.currentDisplayIds)
            )
        }
        DatabaseManager.shuffleStateDao.upsertAll(entities)
    }

    fun sortByParentPath(videoData: MutableList<VideoData>): MutableList<VideoData> {
        val pattern = java.util.regex.Pattern.compile("(\\d+)|([^\\d]+)")
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINA).apply {
            strength = java.text.Collator.PRIMARY
        }
        videoData.sortWith { v1, v2 ->
            val parentCompare = compareSegments(v1.parentPath, v2.parentPath, pattern, collator)
            if (parentCompare != 0) {
                parentCompare
            } else {
                compareSegments(v1.name, v2.name, pattern, collator)
            }
        }
        return videoData
    }

    private fun compareSegments(s1: String, s2: String, pattern: java.util.regex.Pattern, collator: java.text.Collator): Int {
        val matcher1 = pattern.matcher(s1)
        val matcher2 = pattern.matcher(s2)
        while (matcher1.find() && matcher2.find()) {
            val seg1 = matcher1.group()
            val seg2 = matcher2.group()
            val comparison = if (seg1[0].isDigit() && seg2[0].isDigit()) {
                try {
                    seg1.toLong().compareTo(seg2.toLong())
                } catch (e: Exception) {
                    collator.compare(seg1, seg2)
                }
            } else {
                collator.compare(seg1, seg2)
            }
            if (comparison != 0) return comparison
        }
        return matcher1.find().compareTo(matcher2.find())
    }

    fun selectRandomMedia(
        allMedia: List<MediaData>,
        categoryKey: String,
        currentState: MediaShuffleState
    ): Pair<List<MediaData>, MediaShuffleState> {
        var selectedIds = currentState.selectedIds
        val currentDisplayIds = currentState.currentDisplayIds

        var candidates = allMedia.filter { it.id !in selectedIds }

        if (candidates.isEmpty()) {
            selectedIds = emptySet()
            candidates = allMedia.filter { it.id !in currentDisplayIds.toSet() }
            if (candidates.isEmpty()) {
                candidates = allMedia
            }
        }

        val count = minOf(6, candidates.size)
        val selected = candidates.shuffled().take(count)

        selectedIds = selectedIds + selected.map { it.id }.toSet()
        val newDisplayIds = selected.map { it.id }

        return Pair(selected, MediaShuffleState(selectedIds, newDisplayIds))
    }

    fun sortMediaByDefault(media: List<MediaData>): List<MediaData> {
        return media.sortedWith(
            compareByDescending<MediaData> { it.pinned }
                .thenByDescending { it.pinnedAt }
                .thenByDescending { it.lastPlayedAt }
                .thenByDescending { it.index }
        )
    }

    /** 置顶切换（单条读取 + 字段级更新，替代 loadAll→改→saveAll） */
    suspend fun togglePin(mediaId: String): Boolean {
        val dao = DatabaseManager.mediaDataDao
        val media = dao.getById(mediaId) ?: return false
        val newPinned = !media.pinned
        dao.updatePinned(mediaId, newPinned, if (newPinned) System.currentTimeMillis() else 0L)
        return newPinned
    }
}
