package me.lingci.dy.player.ui.playlist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.recyclerview.widget.LinearLayoutManager
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityPlaylistBinding
import me.lingci.dy.player.databinding.DialogPlaylistCreateBinding
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.ui.media_detail.MediaDetailActivity
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.ui.BaseActivity
import me.lingci.lib.base.ui.setEmptyView
import me.lingci.lib.base.util.ToastUtil

/**
 * 播放列表
 */
class PlaylistActivity : BaseActivity(), MenuProvider {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, PlaylistActivity::class.java))
        }
    }

    private var _binding: ActivityPlaylistBinding? = null
    private val binding get() = _binding!!
    private val spUtil by lazy { SpUtil(this) }
    private lateinit var playlistAdapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _binding = ActivityPlaylistBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        init()
    }

    private fun init() {
        playlistAdapter = PlaylistAdapter(mutableListOf())
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = playlistAdapter
        binding.recyclerView.setEmptyView(emptyText = getString(R.string.hint_playlist_empty))

        playlistAdapter.onItemClick { item, _ ->
            val synced = LibraryCompat.syncPlaylistVideos(spUtil, item.id) ?: item
            MediaDetailActivity.start(this@PlaylistActivity, synced)
        }
        playlistAdapter.onItemLongClick { item, position ->
            showItemMenu(item, position)
        }

        loadPlaylists()
    }

    override fun onStart() {
        super.onStart()
        addMenuProvider(this, this)
    }

    override fun onStop() {
        removeMenuProvider(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        loadPlaylists()
        // 同步启动自动播放播放列表 id，确保从其他页面返回时角标正确
        playlistAdapter.setAutoPlayPlaylistId(spUtil.autoPlayPlaylistId)
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.menu_playlist, menu)
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId == R.id.menu_playlist_add) {
            showCreateDialog()
            return true
        }
        return false
    }

    private fun loadPlaylists() {
        val playlists = LibraryCompat.loadPlaylist(spUtil)
        playlistAdapter.updateData(playlists)
    }

    private fun showCreateDialog() {
        val createBinding = DialogPlaylistCreateBinding.inflate(layoutInflater)
        AlertDialog.Builder(this)
            .setTitle(R.string.hint_playlist_add)
            .setView(createBinding.root)
            .setPositiveButton(R.string.action_confirmed) { dialog, _ ->
                val name = createBinding.textName.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    val playMode = when (createBinding.radioPlayMode.checkedRadioButtonId) {
                        R.id.radio_short -> 1
                        R.id.radio_long -> 2
                        else -> 0
                    }
                    LibraryCompat.createPlaylist(spUtil, name, playMode)
                    ToastUtil.showToast(this, getString(R.string.hint_playlist_created))
                    loadPlaylists()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showItemMenu(item: MediaData, position: Int) {
        // 第三项动态显示：已设为自动播放则显示"取消"，未设则显示"设为"
        val isAutoPlay = spUtil.autoPlayPlaylistId == item.id
        val menuItems = arrayOf(
            getString(R.string.action_rename),
            getString(R.string.action_delete),
            if (isAutoPlay) "取消启动自动播放" else "设为启动自动播放",
            getString(R.string.action_playlist_play_mode)
        )
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(menuItems) { _, which ->
                when (which) {
                    0 -> renamePlaylist(item)
                    1 -> deletePlaylist(item, position)
                    2 -> toggleAutoPlay(item)
                    3 -> showPlayModeDialog(item, position)
                }
            }
            .show()
    }

    /**
     * 切换播放列表的播放类型（全局/短视频/长视频）。
     * 与媒体库 playMode 取值一致：0=全局，1=短视频，2=长视频。
     */
    private fun showPlayModeDialog(item: MediaData, position: Int) {
        val labels = arrayOf(
            getString(R.string.action_model_global),
            getString(R.string.action_model_short),
            getString(R.string.action_model_long)
        )
        val checked = when (item.playMode) {
            1 -> 1
            2 -> 2
            else -> 0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_playlist_play_mode)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val newMode = when (which) {
                    1 -> 1
                    2 -> 2
                    else -> 0
                }
                if (newMode != item.playMode) {
                    LibraryCompat.updatePlaylistMode(spUtil, item.id, newMode)
                    item.playMode = newMode
                    playlistAdapter.notifyItemChanged(position)
                    ToastUtil.showToast(this, getString(R.string.hint_playlist_mode_changed))
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * 切换播放列表的启动自动播放设置。
     * 与媒体库自动播放互斥：设为播放列表时清空媒体库设置，反向亦然。
     */
    private fun toggleAutoPlay(item: MediaData) {
        if (spUtil.autoPlayPlaylistId == item.id) {
            spUtil.autoPlayPlaylistId = ""
            ToastUtil.showToast(this, "已取消启动自动播放")
        } else {
            spUtil.autoPlayPlaylistId = item.id
            // 互斥：清空媒体库自动播放设置
            spUtil.autoPlayMediaId = ""
            ToastUtil.showToast(this, "已设为启动自动播放")
        }
        playlistAdapter.setAutoPlayPlaylistId(spUtil.autoPlayPlaylistId)
    }

    private fun renamePlaylist(playlist: MediaData) {
        val editText = android.widget.EditText(this).apply {
            setText(playlist.title)
            setSelection(text.length)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_rename)
            .setView(editText)
            .setPositiveButton(R.string.action_confirmed) { dialog, _ ->
                val name = (editText.text?.toString()?.trim().orEmpty())
                if (name.isNotBlank()) {
                    LibraryCompat.renamePlaylist(spUtil, playlist.id, name)
                    ToastUtil.showToast(this, getString(R.string.hint_playlist_renamed))
                    loadPlaylists()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deletePlaylist(playlist: MediaData, position: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.action_delete))
            .setMessage("确定删除播放列表「${playlist.title}」？")
            .setPositiveButton(R.string.action_confirmed) { _, _ ->
                LibraryCompat.deletePlaylist(spUtil, playlist.id)
                // 删除的播放列表若被设为启动自动播放，清空设置避免指向无效 id
                if (spUtil.autoPlayPlaylistId == playlist.id) {
                    spUtil.autoPlayPlaylistId = ""
                }
                ToastUtil.showToast(this, getString(R.string.hint_playlist_deleted))
                playlistAdapter.removeItem(position)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

}
