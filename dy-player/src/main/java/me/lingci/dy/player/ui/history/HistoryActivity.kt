package me.lingci.dy.player.ui.history

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityHistoryBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.room.entity.HistoryGroupSummary
import me.lingci.dy.player.room.entity.PlayHistory
import me.lingci.dy.player.room.entity.PlaySource
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.media_detail.MediaDetailActivity
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.ui.setEmptyView
import me.lingci.lib.base.util.Log
import me.lingci.lib.base.util.ToastUtil

/**
 * 播放历史页（替代原未挂载的 HistoryFragment）：
 * 双视图展示 —— LIBRARY/PLAYLIST 聚合为组项、STANDALONE 为文件项，
 * 两个 Room Flow 合并渲染，点击进入对应播放入口。
 *
 * 删除入口（组项删整组、文件项删单条）：
 * - 长按：弹出确认框，确认后落库
 * - 工具栏「清空播放历史」：一次性清库
 */
class HistoryActivity : BaseActivity(), MenuProvider {

    companion object {
        private const val TAG = "HistoryActivity"

        fun start(context: Context) {
            context.startActivity(Intent(context, HistoryActivity::class.java))
        }
    }

    private var _binding: ActivityHistoryBinding? = null
    private val binding get() = _binding!!
    private lateinit var historyAdapter: HistoryItemAdapter
    private val spUtil by lazy { SpUtil(this) }

    // 两组展示数据缓存：任一 Flow 发射后合并渲染，避免只重查单侧
    private var groupItems: List<HistoryDisplayItem.HistoryGroup> = emptyList()
    private var singleItems: List<HistoryDisplayItem.HistorySingle> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        init()
    }

    private fun init() {
        historyAdapter = HistoryItemAdapter(mutableListOf())
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = historyAdapter
        binding.recyclerView.setEmptyView(emptyText = getString(R.string.hint_history_empty))

        historyAdapter.onItemClick { item, _ ->
            openHistory(item)
        }
        historyAdapter.onItemLongClick { item, _ ->
            showDeleteConfirm(item)
        }

        showDeleteTip()

        // 溢出菜单（清空播放历史）
        addMenuProvider(this, this)

        observeHistory()
    }

    // ---- 删除入口 ----

    /** 首次进入提示删除方式（只提示一次，SharedPreferences 记忆） */
    private fun showDeleteTip() {
        if (spUtil.historyDeleteTipShown) return
        spUtil.historyDeleteTipShown = true
        ToastUtil.showToast(this, getString(R.string.hint_history_delete_tip))
    }

    /** 工具栏菜单：清空全部播放历史 */
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_history, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.menu_history_clear_all) {
            showClearAllConfirm()
            return true
        }
        return false
    }

    /** 清空全部播放历史（play_history 整表 DELETE，video_data 不受影响） */
    private fun showClearAllConfirm() {
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_history_clear_all)
            .setMessage(getString(R.string.hint_history_clear_all))
            .setPositiveButton(R.string.action_confirmed) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        DatabaseManager.playHistoryDao.deleteAll()
                    }.onFailure { e -> Log.d(TAG, "clear history failed: ${e.message}", e) }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** 收集两个 Room Flow：组项聚合 + 单文件历史，各自富化后合并渲染 */
    private fun observeHistory() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    DatabaseManager.playHistoryDao.observeGroupedHistory().collect { summaries ->
                        // 富化失败不中断 Flow（runCatching 保留上次数据）
                        groupItems = runCatching {
                            withContext(Dispatchers.IO) { buildGroupItems(summaries) }
                        }.getOrElse {
                            Log.d(TAG, "buildGroupItems failed: ${it.message}", it)
                            groupItems
                        }
                        render()
                    }
                }
                launch {
                    DatabaseManager.playHistoryDao.observeStandaloneHistory().collect { records ->
                        singleItems = runCatching {
                            withContext(Dispatchers.IO) { buildSingleItems(records) }
                        }.getOrElse {
                            Log.d(TAG, "buildSingleItems failed: ${it.message}", it)
                            singleItems
                        }
                        render()
                    }
                }
            }
        }
    }

    /** 组项富化：source_key 查媒体/播放列表标题 + 最后播放集的进度与实体（容错"已删除"） */
    private suspend fun buildGroupItems(
        summaries: List<HistoryGroupSummary>
    ): List<HistoryDisplayItem.HistoryGroup> {
        val deleted = getString(R.string.history_deleted)
        return summaries.map { summary ->
            val sourceType = PlaySource.fromValue(summary.sourceType)
            val sourceKey = summary.sourceKey
            // source_key 去前缀还原实体 id（"lib:{mediaId}" / "pl:{playlistId}"）
            val mediaId = sourceKey.removePrefix("lib:")
            val playlistId = sourceKey.removePrefix("pl:")
            // 最后播放的集：videoId → 记录（进度）+ 实体（集名/缩略图回退）
            val lastVideoId = DatabaseManager.playHistoryDao.lastPlayedVideoId(sourceKey)
            val lastRecord = lastVideoId?.let {
                DatabaseManager.playHistoryDao.getByVideoAndSource(it, sourceKey)
            }
            val lastVideo = lastVideoId?.let { DatabaseManager.videoDataDao.getById(it) }
            val media = if (sourceType == PlaySource.LIBRARY) {
                DatabaseManager.mediaDataDao.getById(mediaId)
            } else {
                null
            }
            val playlist = if (sourceType == PlaySource.PLAYLIST) {
                DatabaseManager.playlistDao.getById(playlistId)
            } else {
                null
            }
            HistoryDisplayItem.HistoryGroup(
                sourceType = sourceType,
                sourceKey = sourceKey,
                mediaId = mediaId,
                playlistId = playlistId,
                media = media,
                title = media?.title ?: playlist?.title ?: deleted,
                lastVideoName = lastVideo?.name ?: deleted,
                lastVideoUrl = lastVideo?.videoUrl.orEmpty(),
                progress = lastRecord?.progress ?: 0L,
                duration = lastRecord?.duration ?: 0L,
                epCount = summary.epCount,
                lastPlayedAt = summary.lastPlayedAt
            )
        }
    }

    /** 文件项富化：join video_data（FK CASCADE 保证实体存在，缺失时跳过该条） */
    private suspend fun buildSingleItems(
        records: List<PlayHistory>
    ): List<HistoryDisplayItem.HistorySingle> {
        return records.mapNotNull { record ->
            val video = DatabaseManager.videoDataDao.getById(record.videoId) ?: return@mapNotNull null
            HistoryDisplayItem.HistorySingle(
                videoData = video,
                sourceKey = record.sourceKey,
                progress = record.progress,
                duration = record.duration,
                lastPlayedAt = record.updatedAt
            )
        }
    }

    /** 合并渲染：两组数据按最后播放时间倒序 */
    private fun render() {
        val merged = (groupItems + singleItems).sortedByDescending { it.lastPlayedAt }
        historyAdapter.updateData(merged.toMutableList())
    }

    /** 点击行为（方案 §5.7.3）：组项进媒体库/播放列表详情，文件项单文件续播 */
    private fun openHistory(item: HistoryDisplayItem) {
        when (item) {
            is HistoryDisplayItem.HistoryGroup -> when (item.sourceType) {
                PlaySource.LIBRARY -> {
                    lifecycleScope.launch {
                        val media = withContext(Dispatchers.IO) {
                            DatabaseManager.mediaDataDao.getById(item.mediaId)
                        }
                        if (media != null) {
                            MediaDetailActivity.start(this@HistoryActivity, media)
                        } else {
                            ToastUtil.showToast(this@HistoryActivity, getString(R.string.history_deleted))
                        }
                    }
                }

                PlaySource.PLAYLIST -> {
                    lifecycleScope.launch {
                        val mediaData = withContext(Dispatchers.IO) {
                            buildPlaylistMediaData(item.playlistId)
                        }
                        if (mediaData != null) {
                            MediaDetailActivity.start(this@HistoryActivity, mediaData)
                        } else {
                            ToastUtil.showToast(this@HistoryActivity, getString(R.string.history_deleted))
                        }
                    }
                }

                // 组项仅聚合 LIBRARY/PLAYLIST，理论上不会出现
                PlaySource.STANDALONE -> Unit
            }

            is HistoryDisplayItem.HistorySingle -> {
                // 单文件历史：直接续播（history=true 走进度恢复）
                LongVideoActivity.start(this, ArrayList(listOf(item.videoData)), 0, true)
            }
        }
    }

    /** 长按删除：组项删整组，文件项删单条（Room3 Flow 发射后列表自动刷新） */
    private fun showDeleteConfirm(item: HistoryDisplayItem) {
        val messageRes = if (item is HistoryDisplayItem.HistoryGroup) {
            R.string.hint_history_delete_group
        } else {
            R.string.hint_history_delete_single
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(getString(messageRes))
            .setPositiveButton(R.string.action_confirmed) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        when (item) {
                            is HistoryDisplayItem.HistoryGroup ->
                                DatabaseManager.playHistoryDao.deleteBySource(item.sourceType, item.sourceKey)

                            is HistoryDisplayItem.HistorySingle ->
                                DatabaseManager.playHistoryDao.deleteByVideoAndSource(
                                    item.videoData.id, item.sourceKey
                                )
                        }
                    }.onFailure { e -> Log.d(TAG, "delete history failed: ${e.message}", e) }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * 由 Playlist 实体 + playlist_item/video_data 还原详情页所需的 PLAYLIST 类型 MediaData。
     * （与 PlaylistActivity.buildPlaylistMediaData 同构：MediaData.items 为 @Ignore 字段，需手动填充）
     */
    private suspend fun buildPlaylistMediaData(playlistId: String): MediaData? {
        val playlist = DatabaseManager.playlistDao.getById(playlistId) ?: return null
        val videos = DatabaseManager.playlistItemDao.listByPlaylist(playlistId)
            .mapNotNull { DatabaseManager.videoDataDao.getById(it.videoId) }
        return MediaData(
            id = playlist.id,
            title = playlist.title,
            type = MediaLibType.PLAYLIST,
            storageType = StorageType.LOCAL_STORAGE,
            playMode = playlist.playMode,
            items = videos.toMutableList()
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

}
