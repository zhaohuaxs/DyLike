package me.lingci.dy.player.ui.compose.backup

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.core.DyPlayerCore
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.migrate.MigrationChecker
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.AppMeta
import me.lingci.dy.player.room.entity.Favorite
import me.lingci.dy.player.room.entity.MediaShuffleStateEntity
import me.lingci.dy.player.room.entity.PlayHistory
import me.lingci.dy.player.room.entity.Playlist
import me.lingci.dy.player.room.entity.PlaylistItem
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.dy.player.util.refreshLibraryMetadata
import me.lingci.lib.base.json.JSONObject
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.createNew
import java.io.File

enum class BackupsTask { Backup, Restore }

@Immutable
data class BackupsUiState(
    val busy: Boolean = false,
    val task: BackupsTask? = null
)

sealed interface BackupsEvent {
    data class BackupDone(val fileName: String) : BackupsEvent
    data object BackupFailed : BackupsEvent
    data object RestoreDone : BackupsEvent
    data object RestoreInvalid : BackupsEvent
    /** 旧备份需要重建数据库，交给 UI 启动迁移页 */
    data object RestoreNeedsMigration : BackupsEvent
    data class RestoreFailed(val detail: String?) : BackupsEvent
}

/**
 * 备份还原页 ViewModel。
 *
 * 备份内容 = SP 整体快照（`spAll`，见 [SpBackupCodec]）+ info 目录文件 + Room 9 表快照。
 * SP 走整份导出/导入，新增偏好字段不需要改这里；旧备份（逐 key 格式、无 `spAll`）仍能还原，
 * 但只覆盖它当年支持的那部分字段。
 *
 * 旧备份（低 schema）仍走「删库 + 迁移页重建」路径。
 */
class BackupsViewModel(app: Application) : AndroidViewModel(app) {

    private val context: Context get() = getApplication()
    private val sp: SpUtil by lazy { SpUtil(context) }

    private val _state = MutableStateFlow(BackupsUiState())
    val state: StateFlow<BackupsUiState> = _state.asStateFlow()

    private val _events = Channel<BackupsEvent>(Channel.BUFFERED)
    val events: Flow<BackupsEvent> = _events.receiveAsFlow()

    fun backup() {
        if (_state.value.busy) return
        _state.value = BackupsUiState(busy = true, task = BackupsTask.Backup)
        viewModelScope.launch {
            val fileName = runCatching {
                withContext(Dispatchers.IO) { performBackup() }
            }.getOrElse { error ->
                Log.d(this@BackupsViewModel, "backup failed", error)
                null
            }
            _state.value = BackupsUiState()
            _events.send(
                if (fileName != null) BackupsEvent.BackupDone(fileName) else BackupsEvent.BackupFailed
            )
        }
    }

    fun restore(filePath: String) {
        if (_state.value.busy || filePath.isBlank()) return
        _state.value = BackupsUiState(busy = true, task = BackupsTask.Restore)
        viewModelScope.launch {
            val event = runCatching { performRestore(filePath) }.getOrElse { error ->
                Log.d(this@BackupsViewModel, "restore failed", error)
                BackupsEvent.RestoreFailed(error.message)
            }
            _state.value = BackupsUiState()
            _events.send(event)
        }
    }

    private suspend fun performBackup(): String {
        val data = JSONObject()
        data.putValue("backupType", "dy_like")
        // v3 备份：媒体库/历史/收藏/播放列表走 Room 表快照导出（schema_version 对齐当前目标版本），
        // 旧的 4 个 SP JSON 字段不再导出（迁移后 SP 停写，仅剩回滚价值，不可作为数据源）
        data.putValue("schema_version", MigrationChecker.TARGET_SCHEMA_VERSION)
        data.putValue("exported_at", System.currentTimeMillis())
        // 播放内核单列保留：只认这两个顶层字段的旧版本恢复端还能读回内核设置，
        // 其余配置一律以 spAll 为准，不再逐 key 双写
        // 先走一次 getter，把 videoPlayerExo → videoPlayerCore 的一次性迁移落进 SP，保证快照完整
        val longCore = sp.videoPlayerCore
        val shortCore = sp.shortVideoPlayerCore
        data.putValue("videoPlayerCore", longCore)
        data.putValue("shortVideoPlayerCore", shortCore)
        // SP 整体快照：整份 preferences 带类型标注导出。
        // 新增偏好字段自动纳入，不需要再到备份代码里补一行（旧实现要改导出+导入两处）
        data.putValue("spAll", SpBackupCodec.encode(sp))
        // 弹幕绑定等 per-video 数据在 info/*.json 里，按原文打包
        context.getExternalFilesDir("info")?.list()?.let { list ->
            val playInfoList = JSONObject()
            for (path in list) {
                File(context.getExternalFilesDir("info"), path).let { file ->
                    if (file.exists()) {
                        playInfoList.putValue(file.name, file.readText())
                    }
                }
            }
            data.putValue("playInfoList", playInfoList)
        }
        // Room 表快照：9 表全量导出，逐表 JSON 字符串存储（恢复端 JsonUtil.toList 还原）
        val tables = JSONObject()
        tables.putValue("source_data", JsonUtil.toJsonString(DatabaseManager.sourceDataDao.list()))
        tables.putValue("media_data", JsonUtil.toJsonString(DatabaseManager.mediaDataDao.list()))
        tables.putValue("video_data", JsonUtil.toJsonString(DatabaseManager.videoDataDao.list()))
        tables.putValue("play_history", JsonUtil.toJsonString(DatabaseManager.playHistoryDao.list()))
        tables.putValue("favorite", JsonUtil.toJsonString(DatabaseManager.favoriteDao.list()))
        tables.putValue("playlist", JsonUtil.toJsonString(DatabaseManager.playlistDao.list()))
        tables.putValue("playlist_item", JsonUtil.toJsonString(DatabaseManager.playlistItemDao.list()))
        tables.putValue("media_shuffle_state", JsonUtil.toJsonString(DatabaseManager.shuffleStateDao.list()))
        tables.putValue("app_meta", JsonUtil.toJsonString(DatabaseManager.appMetaDao.list()))
        data.putValue("tables", tables)

        val filename = "dy_backup_${AppUtil.formatNow()}.json"
        FileOperator.writeText(FileOperator.buildDownFile("DyLike", filename), data.toJsonStr())
        return filename
    }

    private suspend fun performRestore(filePath: String): BackupsEvent {
        val data = JSONObject(FileOperator.readText(filePath))
        if (data.getString("backupType") != "dy_like") {
            return BackupsEvent.RestoreInvalid
        }
        withContext(Dispatchers.IO) { restorePlayInfo(data) }
        // SP 整体写回是一次 editor 提交，走 IO 线程，避免在主线程上碰磁盘
        withContext(Dispatchers.IO) { restoreSp(data) }

        val schema = data.getInt("schema_version")
        if (schema >= MigrationChecker.TARGET_SCHEMA_VERSION) {
            // 新备份：删库重建 + 按 FK 依赖序单事务导入
            return runCatching {
                withContext(Dispatchers.IO) { restoreRoomTables(data.getJSONObject("tables")) }
            }.fold(
                onSuccess = { BackupsEvent.RestoreDone },
                onFailure = { error ->
                    Log.d(this@BackupsViewModel, "restoreRoomTables failed", error)
                    BackupsEvent.RestoreFailed(error.message)
                }
            )
        }

        // 旧备份：SP JSON 已写回 → 删库 → 交迁移页按 SP 重建
        withContext(Dispatchers.Main) {
            LibraryCompat.migrateIfNeeded(sp)
            DatabaseManager.closeAndReset()
            context.deleteDatabase("dy_player.db")
        }
        return BackupsEvent.RestoreNeedsMigration
    }

    private fun restorePlayInfo(data: JSONObject) {
        if (!data.hasKey("playInfoList")) return
        val playInfo = data.getJSONObject("playInfoList")
        val infoDir = context.getExternalFilesDir("info") ?: return
        for (name in playInfo.keys()) {
            File(infoDir, name).apply {
                if (!exists()) createNew()
                writeText(playInfo.getString(name))
            }
        }
    }

    /**
     * 还原 SP：新备份带 `spAll`，整份写回；旧备份没有该字段，走下面的逐 key 兼容分支。
     *
     * 整体写回后不再调用 `LibraryCompat.migrateIfNeeded`：新备份同时带了 Room 表快照，
     * 数据源已从 SP 迁到 Room，再按 SP 版本号归一化旧 JSON 既无意义也会改脏刚还原的偏好。
     */
    private fun restoreSp(data: JSONObject) {
        if (data.hasKey("spAll")) {
            val spAll = data.getJSONObject("spAll")
            if (spAll.isNotEmpty()) {
                val count = SpBackupCodec.apply(sp, spAll)
                Log.d(this, "restored $count preference entries")
                return
            }
        }
        restoreLegacySp(data)
    }

    /** 旧备份（v3 之前 / 逐 key 格式）的兼容分支，等兼容窗口关闭后可整体删除 */
    private fun restoreLegacySp(data: JSONObject) {
        if (data.hasKey("debugMode")) sp.debugMode = data.getBoolean("debugMode")
        if (data.hasKey("showDm")) sp.showDm = data.getBoolean("showDm")
        if (data.hasKey("dmBold")) sp.dmBold = data.getBoolean("dmBold")
        if (data.hasKey("dmConf")) sp.dmConf = data.getString("dmConf")
        if (data.hasKey("seSsData")) sp.seSsData = data.getString("seSsData")
        if (data.hasKey("lastFolder")) sp.lastFolder = data.getString("lastFolder")
        if (data.hasKey("useFolders")) sp.useFolders = data.getString("useFolders")
        if (data.hasKey("passStroke")) sp.passStroke = data.getBoolean("passStroke")
        if (data.hasKey("downFolder")) sp.downFolder = data.getString("downFolder")
        if (data.hasKey("customColor")) sp.customColor = data.getString("customColor")
        if (data.hasKey("customGradient")) sp.customGradient = data.getString("customGradient")
        if (data.hasKey("customColorScheme")) sp.customColorScheme = data.getString("customColorScheme")
        if (data.hasKey("paletteOptions")) sp.paletteOptions = data.getString("paletteOptions")
        if (data.hasKey("downPalette")) sp.downPalette = data.getBoolean("downPalette")
        if (data.hasKey("showDmFps")) sp.showDmFps = data.getBoolean("showDmFps")
        if (data.hasKey("iconDefault")) sp.iconDefault = data.getBoolean("iconDefault")
        if (data.hasKey("labSurfaceRgba")) sp.labSurfaceRgba = data.getBoolean("labSurfaceRgba")
        if (data.hasKey("labSurfaceZOrder")) sp.labSurfaceZOrder = data.getBoolean("labSurfaceZOrder")
        if (data.hasKey("labMpvSpecialRender")) sp.labMpvSpecialRender = data.getBoolean("labMpvSpecialRender")
        if (data.hasKey("labMpvSuperResolution")) sp.labMpvSuperResolution = data.getBoolean("labMpvSuperResolution")
        // app
        if (data.hasKey("isFirst")) sp.isFirst = data.getBoolean("isFirst")
        if (data.hasKey("dataSchemaVersion")) {
            sp.dataSchemaVersion = data.getInt("dataSchemaVersion")
        } else {
            sp.dataSchemaVersion = 0
        }
        if (data.hasKey("sourceJson")) sp.sourceJson = data.getString("sourceJson")
        if (data.hasKey("mediaJson")) sp.mediaJson = data.getString("mediaJson")
        if (data.hasKey("historyJson")) sp.historyJson = data.getString("historyJson")
        if (data.hasKey("likeJson")) sp.likeJson = data.getString("likeJson")
        if (data.hasKey("dayStr")) sp.dayStr = data.getString("dayStr")
        if (data.hasKey("videoDetailMode")) sp.videoDetailMode = data.getBoolean("videoDetailMode")
        if (data.hasKey("videoPlayerCore")) {
            sp.videoPlayerCore = data.getInt("videoPlayerCore")
        } else if (data.hasKey("videoPlayerExo")) {
            sp.videoPlayerCore = DyPlayerCore.fromLegacyExo(data.getBoolean("videoPlayerExo")).value
        }
        if (data.hasKey("shortVideoPlayerCore")) sp.shortVideoPlayerCore = data.getInt("shortVideoPlayerCore")
        if (data.hasKey("longVideoMode")) sp.longVideoMode = data.getBoolean("longVideoMode")
        if (data.hasKey("shortRandom")) sp.shortRandom = data.getBoolean("shortRandom")
        if (data.hasKey("dmGradientMode")) sp.dmGradientMode = data.getBoolean("dmGradientMode")
        if (data.hasKey("dmGradientRatio")) sp.dmGradientRatio = data.getInt("dmGradientRatio")
        if (data.hasKey("dmGradientWithTextColor")) sp.dmGradientWithTextColor = data.getBoolean("dmGradientWithTextColor")
        if (data.hasKey("dmStrokeMultipleMode")) sp.dmStrokeMultipleMode = data.getBoolean("dmStrokeMultipleMode")
        if (data.hasKey("dmStrokeMultiple")) sp.dmStrokeMultiple = data.getDouble("dmStrokeMultiple").toFloat()
        if (data.hasKey("dmFontMode")) sp.dmFontMode = data.getBoolean("dmFontMode")
        if (data.hasKey("dmCurrentFont")) sp.dmCurrentFont = data.getString("dmCurrentFont")
        if (data.hasKey("firstScanMovie")) sp.firstScanMovie = data.getBoolean("firstScanMovie")
        if (data.hasKey("surfaceRender")) sp.surfaceRender = data.getBoolean("surfaceRender")
        if (data.hasKey("dmMergeMode")) sp.dmMergeMode = data.getBoolean("dmMergeMode")
        if (data.hasKey("dmShowTime")) sp.dmShowTime = data.getBoolean("dmShowTime")
        if (data.hasKey("dmMergeTop")) sp.dmMergeTop = data.getInt("dmMergeTop")
        if (data.hasKey("dmMergeShow")) sp.dmMergeShow = data.getInt("dmMergeShow")
        if (data.hasKey("browserUsedAll")) sp.browserUsedAll = data.getBoolean("browserUsedAll")
        if (data.hasKey("browserSort")) sp.browserSort = data.getInt("browserSort")
        if (data.hasKey("browserShowHide")) sp.browserShowHide = data.getBoolean("browserShowHide")
        if (data.hasKey("newHome")) sp.newHome = data.getBoolean("newHome")
        if (data.hasKey("launchComposeHome")) sp.launchComposeHome = data.getBoolean("launchComposeHome")
        if (data.hasKey("sortRender")) sp.sortRender = data.getBoolean("sortRender")
        if (data.hasKey("useOkhttp")) sp.useOkhttp = data.getBoolean("useOkhttp")
        if (data.hasKey("autoNext")) sp.autoNext = data.getBoolean("autoNext")
        if (data.hasKey("loopList")) sp.loopList = data.getBoolean("loopList")
        if (data.hasKey("showShortTitle")) sp.showShortTitle = data.getBoolean("showShortTitle")
        if (data.hasKey("showShortLike")) sp.showShortLike = data.getBoolean("showShortLike")
        if (data.hasKey("showShortComment")) sp.showShortComment = data.getBoolean("showShortComment")
        if (data.hasKey("showShortPager")) sp.showShortPager = data.getBoolean("showShortPager")
        if (data.hasKey("shortPlayNext")) sp.shortPlayNext = data.getBoolean("shortPlayNext")
        if (data.hasKey("shortLifeSpeed")) sp.shortLifeSpeed = data.getFloat("shortLifeSpeed")
        if (data.hasKey("shortRightSpeed")) sp.shortRightSpeed = data.getFloat("shortRightSpeed")
        if (data.hasKey("shortTitleStrategy")) sp.shortTitleStrategy = data.getInt("shortTitleStrategy")
        if (data.hasKey("shortTitleDelimiter")) sp.shortTitleDelimiter = data.getString("shortTitleDelimiter")
        if (data.hasKey("shortTitleRegex")) sp.shortTitleRegex = data.getString("shortTitleRegex")
        if (data.hasKey("shortTitleMaxLines")) sp.shortTitleMaxLines = data.getInt("shortTitleMaxLines")
    }

    /**
     * 新备份恢复：Room 快照按 FK 依赖序导入。
     *
     * 这里用 [androidx.room3.RoomDatabase.clearAllTables] 而不是「关连接 + 删库」：
     * Compose 的 NavHost 里其它页面（媒体库/我的）的 Flow 仍在订阅，关库后它们下一次查询
     * 会直接抛 `Database is closed`；clearAllTables 不关连接，且会让 Room 通知这些 Flow 失效重查。
     */
    private suspend fun restoreRoomTables(tables: JSONObject) {
        val sources = JsonUtil.toList<SourceData>(tables.getString("source_data"))
        val medias = JsonUtil.toList<MediaData>(tables.getString("media_data"))
            .onEach { it.refreshLibraryMetadata(preserveExistingKey = true) }
        val videos = JsonUtil.toList<VideoData>(tables.getString("video_data"))
        val histories = JsonUtil.toList<PlayHistory>(tables.getString("play_history"))
        val favorites = JsonUtil.toList<Favorite>(tables.getString("favorite"))
        val playlists = JsonUtil.toList<Playlist>(tables.getString("playlist"))
        val playlistItems = JsonUtil.toList<PlaylistItem>(tables.getString("playlist_item"))
        val shuffles = JsonUtil.toList<MediaShuffleStateEntity>(tables.getString("media_shuffle_state"))
        val metas = JsonUtil.toList<AppMeta>(tables.getString("app_meta"))
        DatabaseManager.db.clearAllTables()
        // FK 依赖序：source → media → video → playlist → item → history/favorite → shuffle → meta
        DatabaseManager.inTransaction {
            DatabaseManager.sourceDataDao.insertAll(sources)
            DatabaseManager.mediaDataDao.insertAll(medias)
            DatabaseManager.videoDataDao.insertAll(videos)
            DatabaseManager.playlistDao.insertAll(playlists)
            DatabaseManager.playlistItemDao.insertAll(playlistItems)
            DatabaseManager.playHistoryDao.insertAll(histories)
            DatabaseManager.favoriteDao.insertAll(favorites)
            DatabaseManager.shuffleStateDao.insertAll(shuffles)
            DatabaseManager.appMetaDao.insertAll(metas)
        }
    }
}
