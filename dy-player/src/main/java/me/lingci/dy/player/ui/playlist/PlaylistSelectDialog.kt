package me.lingci.dy.player.ui.playlist

import android.content.Context
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.logD

object PlaylistSelectDialog {

    fun show(context: Context, videos: List<VideoData>) {
        // Room3：播放列表改为 Room 异步加载后再弹窗（现有调用方均为 Activity/Fragment，可安全转型 LifecycleOwner）
        val owner = context as? LifecycleOwner ?: return
        val scope = owner.lifecycleScope
        scope.launch {
            // 播放列表与条目数均为 Room 异步查询（suspend DAO 自带 IO 调度）
            val playlists = DatabaseManager.playlistDao.list()
            // Playlist 实体无 items 字段，条目数经 playlist_item 异步计数
            val counts = playlists.associate { it.id to DatabaseManager.playlistItemDao.countByPlaylist(it.id) }
            val playlistNames = playlists.map { "${it.title} (${counts[it.id] ?: 0})" }
                .plus(context.getString(R.string.hint_playlist_create_new))
                .toTypedArray()

            AlertDialog.Builder(context)
                .setTitle(R.string.hint_playlist_select)
                .setItems(playlistNames) { _, which ->
                    if (which == playlists.size) {
                        showCreateAndAdd(context, scope, videos)
                    } else {
                        val playlist = playlists[which]
                        // Room3：添加条目改为 suspend 调用，fire-and-forget
                        scope.launch {
                            runCatching {
                                LibraryCompat.addToPlaylist(playlist.id, videos)
                            }.onSuccess {
                                ToastUtil.showToast(context, context.getString(R.string.hint_playlist_video_added))
                            }.onFailure { e ->
                                // 写入异常（如 FK 违约）降级为提示，避免直接崩主线程
                                logD("addToPlaylist failed", e)
                                ToastUtil.showToast(context, context.getString(R.string.hint_playlist_video_add_failed))
                            }
                        }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun showCreateAndAdd(context: Context, scope: CoroutineScope, videos: List<VideoData>) {
        val editText = EditText(context).apply {
            hint = context.getString(R.string.hint_playlist_name)
            isSingleLine = true
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.hint_playlist_add)
            .setView(editText)
            .setPositiveButton(R.string.action_confirmed) { dialog, _ ->
                val name = editText.text?.toString()?.trim().orEmpty()
                if (name.isNotBlank()) {
                    // Room3：新建播放列表并加入条目，两个 suspend 调用串行执行
                    scope.launch {
                        runCatching {
                            val playlist = LibraryCompat.createPlaylist(name)
                            LibraryCompat.addToPlaylist(playlist.id, videos)
                        }.onSuccess {
                            ToastUtil.showToast(context, context.getString(R.string.hint_playlist_created))
                        }.onFailure { e ->
                            logD("createPlaylistAndAdd failed", e)
                            ToastUtil.showToast(context, context.getString(R.string.hint_playlist_video_add_failed))
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

}
