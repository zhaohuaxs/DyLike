package me.lingci.dy.player.migrate

import android.content.Context
import androidx.room3.Transactor.SQLiteTransactionType
import androidx.room3.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.entity.MediaShuffleState
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.AppMeta
import me.lingci.dy.player.room.entity.Favorite
import me.lingci.dy.player.room.entity.PlayHistory
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.dy.player.room.entity.Playlist
import me.lingci.dy.player.room.entity.PlaylistItem
import me.lingci.dy.player.room.entity.MediaShuffleStateEntity
import me.lingci.dy.player.ui.long_video.PlayHelper
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.md5

/**
 * SP→Room 迁移执行器（§5.2）。
 *
 * 关键约束：
 * - 全部写入包在单个写事务中，任一步失败整体回滚（宁可失败不留脏数据）
 * - 播放进度回填的同步文件读必须在事务外预读成 Map（避免持写锁做 IO）
 * - video_data 三个来源（媒体 items / 历史 / 收藏 / 播放列表 items）必须全部落库，
 *   否则 playlist_item.video_id 等子表 FK 违约
 * - 孤儿视频按 §1.5 四档规则归属，最终回退虚拟媒体 id，绝不写空串/悬空 mediaId
 * - ★ 合并语义：迁移可被「稍后再说」推迟，期间用户照常写 Room（加媒体/播放/收藏），
 *   重跑时同 id 行一律以库内为准（IGNORE 跳过而非主键冲突整批回滚），SP 快照只补空；
 *   写入前先按 id 去重，避免 SP 自身的重复记录互相冲突
 */
object MigrationExecutor {

    /** 孤儿分桶阈值：桶内超过该数量时按 parentPath 二次分桶，避免单个虚拟媒体挂超大列表 */
    private const val VIRTUAL_BUCKET_LIMIT = 500

    suspend fun perform(context: Context, sp: SpUtil): MigrationResult = withContext(Dispatchers.IO) {
        val startAt = System.currentTimeMillis()
        // SP JSON 归一化（补 id/storageId/coverType），保证解析出的数据满足 FK 约束
        // 注意：dataSchemaVersion 已 ≥3 时本调用直接短路（补 id/去重都不做），
        // 故下面写入前仍需自行补 id 并按 id 去重
        LibraryCompat.migrateIfNeeded(sp)

        val result = MigrationResult()
        val db = DatabaseManager.db

        // ★ 进度预读必须在事务外（避免持写锁做文件 IO）
        val historyVideosPre = normalizeIds(JsonUtil.toList<VideoData>(sp.historyJson.orEmpty()))
        val playSeekMap = historyVideosPre.associate { it.id to readPlaySeek(context, it) }

        // Room3：withWriteTransaction 扩展在 3.0.3 元数据中未导出，
        // 等价写法 = useWriterConnection + Transactor.withTransaction(IMMEDIATE)，
        // 块内 suspend DAO 调用自动加入当前事务上下文
        db.useWriterConnection { transactor ->
            transactor.withTransaction(SQLiteTransactionType.IMMEDIATE) {
            // 事务内统一从 DatabaseManager 取 DAO（suspend DAO 会自动加入当前事务上下文）
            val sourceDao = DatabaseManager.sourceDataDao
            val mediaDao = DatabaseManager.mediaDataDao
            val videoDao = DatabaseManager.videoDataDao
            val playHistoryDao = DatabaseManager.playHistoryDao
            val favoriteDao = DatabaseManager.favoriteDao
            val playlistDao = DatabaseManager.playlistDao
            val itemDao = DatabaseManager.playlistItemDao
            val shuffleDao = DatabaseManager.shuffleStateDao

            // 0. 合并模式判定：用户在迁移页点「稍后再说」后仍可正常用机（增删媒体、播放、收藏），
            //    Room 里已有同名主键行；此时 SP 是旧快照，一律以库内行为准，重复行跳过而非整批回滚。
            val preExisting = mediaDao.count() + sourceDao.count() + videoDao.count()
            if (preExisting > 0) {
                Log.d(this, "migration in merge mode, db already holds $preExisting rows")
            }

            // 1. source_data —— 补 id + 按 id 去重（SP 里可能存在多条空白 id 的同源记录）
            val sources = JsonUtil.toList<SourceData>(sp.sourceJson.orEmpty()).onEach { s ->
                if (s.id.isBlank()) s.id = LibraryCompat.sourceId(s)
            }.distinctBy { it.id }
            run {
                val inserted = sourceDao.insertAllIgnore(sources).insertedCount()
                result.sources = inserted
                result.skippedExisting += sources.size - inserted
            }

            // 2. media_data —— mediaJson 中只会出现 5 种媒体库类型（HISTORY/LIKE/PLAYLIST 是视图模式）
            val allMedia = JsonUtil.toList<MediaData>(sp.mediaJson.orEmpty()).onEach { m ->
                if (m.id.isBlank()) {
                    m.id = LibraryCompat.mediaId(m, sources)
                }
            }
            //    distinctBy：SP 里同路径/同类型记录可能被重复写入（dataSchemaVersion 已 ≥3 时
            //    migrateIfNeeded 会短路、不再补做去重），批内重复主键只保留第一条
            val libraryMedia = allMedia.filter { it.type.isLibraryType }.distinctBy { it.id }
            //    storageId 悬空会让 media_data 的 FK 违约，而 IGNORE 下表现为「静默丢数据」，
            //    故先清成 null（storage_id 可空，与删除存储源的 SET_NULL 语义一致）
            val knownSourceIds = sources.map { it.id }.toSet()
            var clearedStorageIds = 0
            allMedia.forEach { m ->
                val sid = m.storageId
                if (sid != null && sid !in knownSourceIds && sourceDao.getById(sid) == null) {
                    m.storageId = null
                    clearedStorageIds++
                }
            }
            if (clearedStorageIds > 0) {
                Log.d(this, "cleared $clearedStorageIds dangling storage_id reference(s)")
            }
            // 按 parentPath 排序写入 sort_index（查询时 ORDER BY sort_index，不再内存排序）
            libraryMedia.forEach { m ->
                LibraryCompat.sortByParentPath(m.items).forEachIndexed { idx, v ->
                    v.sortIndex = idx
                    if (v.id.isBlank()) v.id = v.beanId()
                    if (v.mediaId.isBlank()) v.mediaId = m.id
                }
            }
            run {
                val inserted = mediaDao.insertAllIgnore(libraryMedia).insertedCount()
                result.media = inserted
                result.skippedExisting += libraryMedia.size - inserted
            }

            // 3. 收集 video_data —— 三个来源必须全部落库（缺一即 FK 违约）
            //    ① mediaJson 各媒体的 items ② historyJson / likeJson ③ playlistJson 各列表的 items
            val historyVideos = historyVideosPre   // 事务外已预读（同时用于 playSeekMap）
            val likeVideos = normalizeIds(JsonUtil.toList<VideoData>(sp.likeJson.orEmpty()))
            //    distinctBy：playlistJson 里同 id（含空白 id）的列表若重复，playlist 主键会冲突
            val playlists = JsonUtil.toList<MediaData>(sp.playlistJson.orEmpty()).distinctBy { it.id }
            playlists.forEach { p -> normalizeIds(p.items) }
            val playlistItems = playlists.flatMap { it.items }  // 不继承 playlist.id（PLAYLIST 非媒体）
            val mediaItems = libraryMedia.flatMap { m -> m.items } // mediaId 已指向所属媒体

            // 4. 孤儿处理（§1.5 四档规则）
            val mediaById = libraryMedia.associateBy { it.id }.toMutableMap()
            //    候选 = 三来源合并按 id 去重；mediaItems 排最前 → 同 id 冲突时以真实媒体归属为准
            val candidates = (mediaItems + historyVideos + likeVideos + playlistItems).distinctBy { it.id }

            // 规则2：mediaId 非空但媒体不存在 → 按 storageType 推断补建宿主媒体
            candidates.forEach { v ->
                val mid = v.mediaId
                if (mid.isNotBlank() && !mediaById.containsKey(mid)) {
                    val rebuilt = MediaData(
                        id = mid,
                        title = v.parentPath.ifBlank { v.name },
                        type = MediaLibType.fromStorage(v.type),
                        storageType = v.type,
                        isVirtual = false
                    )
                    //    库内可能已有同 id 媒体（迁移被推迟期间用户重新添加过该目录）→ 跳过而非冲突
                    val rowId = mediaDao.insertIgnore(rebuilt)
                    if (rowId == -1L) {
                        result.skippedExisting++
                        // 取库内真实行（isVirtual 等字段可能与推断值不同），供后续归属判定使用
                        mediaById[mid] = mediaDao.getById(mid) ?: rebuilt
                    } else {
                        result.rebuiltMedia++
                        mediaById[mid] = rebuilt
                    }
                }
            }

            // 规则3：mediaId 为空且路径可定位 → 按媒体 path 前缀包含关系匹配
            val orphans = mutableListOf<VideoData>()
            candidates.forEach { v ->
                if (v.mediaId.isNotBlank()) return@forEach
                val hit = mediaById.values.firstOrNull { m ->
                    !m.isVirtual && m.path.isNotBlank() && v.videoUrl.contains(m.path)
                }
                if (hit != null) {
                    v.mediaId = hit.id
                } else {
                    orphans += v
                }
            }

            // 规则4：仍无法归属 → 按 storageType 分桶虚拟媒体；桶超阈值按 parentPath 二次分桶
            orphans.groupBy { it.type }.forEach { (storageType, vids) ->
                val chunks = if (vids.size > VIRTUAL_BUCKET_LIMIT) {
                    vids.groupBy { it.parentPath }.values.fold(mutableListOf<List<VideoData>>()) { acc, g ->
                        g.chunked(VIRTUAL_BUCKET_LIMIT).forEach { acc += it }
                        acc
                    }
                } else {
                    listOf(vids)
                }
                chunks.forEachIndexed { chunkIdx, chunk ->
                    val virtual = MediaData(
                        id = "virtual|${storageType.name}|$chunkIdx".md5(),
                        title = if (chunks.size > 1) "未归类(${storageType.name}#${chunkIdx + 1})" else "未归类",
                        type = MediaLibType.fromStorage(storageType),
                        storageType = storageType,
                        isVirtual = true
                    )
                    //    虚拟桶 id 与 LibraryCompat.ensureVideoParent 同公式：迁移被推迟期间播放过的
                    //    视频已在库里建过同一桶，此处必须跳过而非主键冲突
                    if (mediaDao.insertIgnore(virtual) == -1L) {
                        result.skippedExisting++
                        mediaById[virtual.id] = mediaDao.getById(virtual.id) ?: virtual
                    } else {
                        mediaById[virtual.id] = virtual
                    }
                    chunk.forEach { it.mediaId = virtual.id }
                    result.virtualMedia = true
                }
            }

            // ★ 契约：resolveMediaId 失败必须回退虚拟媒体 id，绝不写空串/悬空 id（NOT NULL FK）
            val allVideos = candidates.onEach { v ->
                v.mediaId = mediaById[v.mediaId]?.id
                    ?: error("孤儿 video ${v.id} 无法归属 media，且虚拟媒体未创建")
            }
            //    IGNORE 只会静默「已存在的行」：上面 mediaById 已保证每个 mediaId 都有父行
            //    （新插或库内已有），故不会掩盖 FK 违约
            run {
                val inserted = videoDao.insertAllIgnore(allVideos).insertedCount()
                result.videos = inserted
                result.skippedExisting += allVideos.size - inserted
            }

            // video_id → media_id 归属表：play_history / favorite / playlist_item 统一同源取值
            val mediaIdByVideo = allVideos.associate { it.id to it.mediaId }

            // 5. play_history —— 进度回填 + sourceType 推断（能定位到真实媒体 → LIBRARY）
            //    合并语义：库内已有同 (video_id, source_key) 行时跳过，避免 SP 快照（duration=0）覆盖真实进度
            val now = System.currentTimeMillis()
            val realMediaIds = mediaById.filterValues { !it.isVirtual }.keys
            val distinctHistory = historyVideos.distinctBy { it.id }
            var historySkipped = 0
            distinctHistory.forEachIndexed { idx, v ->
                val inferredMediaId = mediaIdByVideo[v.id]
                val isLibrary = inferredMediaId != null && inferredMediaId in realMediaIds
                if (playHistoryDao.insertIgnore(
                    PlayHistory(
                        videoId = v.id,
                        sourceType = if (isLibrary) PlaySource.LIBRARY else PlaySource.STANDALONE,
                        sourceKey = if (isLibrary) {
                            PlaySource.libraryKey(inferredMediaId!!)
                        } else {
                            PlaySource.STANDALONE_KEY
                        },
                        progress = playSeekMap[v.id] ?: 0L,   // 事务外预读的 playSeek Map
                        duration = 0L,                        // info 文件无总时长，播放后由 updateProgress 补
                        createdAt = now - (distinctHistory.size - idx) * 1000L, // 列表序号反推，越靠后越新
                        updatedAt = now
                    )
                ) == -1L) {
                    historySkipped++
                }
            }
            result.history = distinctHistory.size - historySkipped
            result.skippedExisting += historySkipped

            // 6. favorite —— video_id 单列唯一索引，库内已收藏则跳过；distinctBy 仅为减少插入次数
            val distinctLikes = likeVideos.distinctBy { it.id }
            var likeSkipped = 0
            distinctLikes.forEach { v ->
                if (favoriteDao.insertIgnore(
                    Favorite(
                        mediaId = mediaIdByVideo[v.id],
                        videoId = v.id
                    )
                ) == -1L) {
                    likeSkipped++
                }
            }
            result.favorites = distinctLikes.size - likeSkipped
            result.skippedExisting += likeSkipped

            // 7. playlist + playlist_item —— 父行 video_data 已在第 3/4 步落库
            playlists.forEach { p ->
                val isNew = playlistDao.insertIgnore(
                    Playlist(id = p.id, title = p.title, playMode = p.playMode)
                ) != -1L
                if (!isNew) result.skippedExisting++
                // 列表已存在时只补库内没有的条目，序号接在现有条目之后（不重排用户手动顺序）
                val existingVideoIds =
                    if (isNew) emptySet() else itemDao.listByPlaylist(p.id).map { it.videoId }.toSet()
                var order = if (isNew) 0 else itemDao.countByPlaylist(p.id)
                p.items.distinctBy { it.id }.forEach { v ->
                    if (v.id in existingVideoIds) return@forEach
                    itemDao.insert(
                        PlaylistItem(
                            playlistId = p.id,
                            mediaId = mediaIdByVideo[v.id],
                            videoId = v.id,
                            sortOrder = order
                        )
                    )
                    order++
                    result.playlistItems++
                }
            }
            result.playlists = playlists.size

            // 8. media_shuffle_state —— 关联媒体不存在则跳过并计数；库内已有状态不覆盖
            runCatching { JsonUtil.toEntity<Map<String, MediaShuffleState>>(sp.mediaShuffleJson.orEmpty()) }
                .getOrDefault(emptyMap())
                .forEach { (mediaId, state) ->
                    if (!mediaById.containsKey(mediaId)) {
                        result.skippedShuffles++
                        return@forEach
                    }
                    if (shuffleDao.insertIgnore(
                        MediaShuffleStateEntity(
                            mediaId = mediaId,
                            selectedIds = JsonUtil.toJsonString(state.selectedIds),
                            currentDisplayIds = JsonUtil.toJsonString(state.currentDisplayIds)
                        )
                    ) == -1L) {
                        result.skippedExisting++
                    } else {
                        result.shuffles++
                    }
                }

            // 9. 迁移标记写入 app_meta（不再写 SP 的 dataSchemaVersion）
            MigrationChecker.markMigrated(now)
            } // withTransaction
        } // useWriterConnection

        result.elapsedMs = System.currentTimeMillis() - startAt
        Log.d(this, "migration done: $result")
        result
    }

    /** 补齐空 id（videoUrl 的 md5，与播放进度文件 info/{videoUrl.md5}.json 同源） */
    private fun normalizeIds(videos: List<VideoData>): List<VideoData> {
        videos.forEach { v ->
            if (v.id.isBlank()) {
                v.id = v.beanId()
            }
        }
        return videos
    }

    /** IGNORE 批量插入的返回值 → 实际写入的行数（-1 表示该行主键已存在被跳过） */
    private fun LongArray.insertedCount(): Int = count { it != -1L }

    /** 从 info/{videoUrl.md5}.json 读 PlayInfo.playSeek（同步文件读，仅限事务外的预读阶段调用） */
    private fun readPlaySeek(context: Context, v: VideoData): Long =
        runCatching { PlayHelper.loadInfoSync(context, v)?.playSeek ?: 0L }.getOrDefault(0L)
}
