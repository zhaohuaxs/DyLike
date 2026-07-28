package me.lingci.dy.player.util

import me.lingci.dy.player.entity.CoverType
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.md5

object LibraryCompat {

    const val SCHEMA_VERSION = 3
    const val LEGACY_UNBOUND_STORAGE_ID = "__legacy_unbound__"
    private const val BUILTIN_LOCAL_ID = "__builtin_local__"
    private const val BUILTIN_EXTERNAL_ID = "__builtin_external__"
    private const val BUILTIN_STREAM_ID = "__builtin_stream__"

    fun builtinLocalId(): String = BUILTIN_LOCAL_ID

    fun builtinExternalId(): String = BUILTIN_EXTERNAL_ID

    fun builtinStreamId(): String = BUILTIN_STREAM_ID

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

    fun loadSources(sp: SpUtil): MutableList<SourceData> {
        migrateIfNeeded(sp)
        return JsonUtil.toList<SourceData>(sp.sourceJson!!).toMutableList()
    }

    fun saveSources(sp: SpUtil, list: List<SourceData>) {
        val normalized = migrateSources(list.toMutableList())
        sp.sourceJson = JsonUtil.toJsonString(normalized)
        sp.dataSchemaVersion = SCHEMA_VERSION
    }

    fun loadMedia(sp: SpUtil): MutableList<MediaData> {
        migrateIfNeeded(sp)
        return JsonUtil.toList<MediaData>(sp.mediaJson!!).toMutableList()
    }

    fun saveMedia(sp: SpUtil, list: List<MediaData>) {
        val sources = loadSources(sp)
        val normalized = migrateMedia(list.toMutableList(), sources)
        sp.mediaJson = JsonUtil.toJsonString(normalized)
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
        if (media.storageId.isNotBlank()) {
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
            if (media.type == MediaLibType.WEBDAV && media.storageId.isBlank()) {
                media.storageId = resolveLegacyStorageId(media, sources).orEmpty()
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

    fun loadPlaylist(sp: SpUtil): MutableList<MediaData> {
        return JsonUtil.toList<MediaData>(sp.playlistJson!!).toMutableList()
    }

    fun savePlaylist(sp: SpUtil, list: List<MediaData>) {
        sp.playlistJson = JsonUtil.toJsonString(list)
    }

    fun createPlaylist(sp: SpUtil, title: String, playMode: Int = 0): MediaData {
        val playlist = MediaData(
            id = "playlist|${title.trim()}|${System.currentTimeMillis()}".md5(),
            title = title.trim(),
            type = MediaLibType.PLAYLIST,
            storageType = StorageType.LOCAL_STORAGE,
            playMode = playMode
        )
        val list = loadPlaylist(sp)
        list.add(playlist)
        savePlaylist(sp, list)
        return playlist
    }

    fun updatePlaylistMode(sp: SpUtil, playlistId: String, playMode: Int) {
        val list = loadPlaylist(sp)
        val playlist = list.find { it.id == playlistId } ?: return
        playlist.playMode = playMode
        savePlaylist(sp, list)
    }

    fun deletePlaylist(sp: SpUtil, playlistId: String) {
        val list = loadPlaylist(sp)
        list.removeAll { it.id == playlistId }
        savePlaylist(sp, list)
    }

    fun addToPlaylist(sp: SpUtil, playlistId: String, videos: List<VideoData>) {
        val list = loadPlaylist(sp)
        val playlist = list.find { it.id == playlistId } ?: return
        val existingIds = playlist.items.map { it.beanId() }.toSet()
        videos.forEach { video ->
            if (video.beanId() !in existingIds) {
                playlist.items.add(video)
            }
        }
        savePlaylist(sp, list)
    }

    fun removeFromPlaylist(sp: SpUtil, playlistId: String, videoIds: List<String>) {
        val list = loadPlaylist(sp)
        val playlist = list.find { it.id == playlistId } ?: return
        playlist.items.removeAll { it.beanId() in videoIds }
        savePlaylist(sp, list)
    }

    fun syncPlaylistVideos(sp: SpUtil, playlistId: String): MediaData? {
        val list = loadPlaylist(sp)
        val playlist = list.find { it.id == playlistId } ?: return null
        val before = playlist.items.size
        playlist.items.removeAll { video ->
            video.type == StorageType.LOCAL_STORAGE && !java.io.File(video.videoUrl).exists()
        }
        if (playlist.items.size != before) {
            savePlaylist(sp, list)
        }
        return playlist
    }

    fun renamePlaylist(sp: SpUtil, playlistId: String, newTitle: String) {
        val list = loadPlaylist(sp)
        val playlist = list.find { it.id == playlistId } ?: return
        playlist.title = newTitle.trim()
        savePlaylist(sp, list)
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

    fun loadShuffleStates(sp: SpUtil): MutableMap<String, MediaShuffleState> {
        val json = sp.mediaShuffleJson ?: "{}"
        return try {
            JsonUtil.toEntity<Map<String, MediaShuffleState>>(json).toMutableMap()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    fun saveShuffleStates(sp: SpUtil, states: Map<String, MediaShuffleState>) {
        sp.mediaShuffleJson = JsonUtil.toJsonString(states)
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

    fun togglePin(sp: SpUtil, mediaId: String): Boolean {
        val list = loadMedia(sp)
        val media = list.find { it.id == mediaId } ?: return false
        media.pinned = !media.pinned
        media.pinnedAt = if (media.pinned) System.currentTimeMillis() else 0L
        saveMedia(sp, list)
        return media.pinned
    }
}
