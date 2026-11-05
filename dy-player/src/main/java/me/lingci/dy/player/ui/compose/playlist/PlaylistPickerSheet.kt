package me.lingci.dy.player.ui.compose.playlist

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.room.DatabaseManager
import me.lingci.dy.player.ui.compose.components.GhostButton
import me.lingci.dy.player.ui.compose.components.PrimaryButton
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.lib.base.util.ToastUtil
import me.lingci.lib.base.util.logD

/** 播放列表选择弹层的状态：列表来自 Room Flow，加载态只用于首帧。 */
@Immutable
data class PlaylistPickerState(
    val loading: Boolean = true,
    val playlists: List<PlaylistUiModel> = emptyList()
)

enum class PlaylistPickerMessage { Added, Created, Failed }

/**
 * 「添加到播放列表」弹层的 ViewModel。
 *
 * 待添加的视频只在内存里过一手（[start]），弹层关闭即随组合销毁；
 * 落库沿用 [LibraryCompat] 的既有实现，与旧的 AlertDialog 行为一致。
 */
class PlaylistPickerViewModel(app: Application) : AndroidViewModel(app) {

    private val playlistDao = DatabaseManager.playlistDao
    private val itemDao = DatabaseManager.playlistItemDao

    private val videos = MutableStateFlow<List<VideoData>>(emptyList())

    private val _messages = Channel<PlaylistPickerMessage>(Channel.BUFFERED)
    val messages: Flow<PlaylistPickerMessage> = _messages.receiveAsFlow()

    val state: StateFlow<PlaylistPickerState> = combine(
        playlistDao.observeAll(),
        itemDao.observeItemCounts()
    ) { playlists, counts ->
        val countById = counts.associate { it.playlistId to it.itemCount }
        PlaylistPickerState(
            loading = false,
            playlists = playlists.map { playlist ->
                PlaylistUiModel(
                    id = playlist.id,
                    title = playlist.title,
                    itemCount = countById[playlist.id] ?: 0,
                    playMode = playlist.playMode,
                    isAutoPlay = false
                )
            }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlaylistPickerState()
    )

    fun start(list: List<VideoData>) {
        videos.value = list
    }

    fun addTo(playlistId: String) {
        val pending = videos.value
        if (pending.isEmpty()) return
        viewModelScope.launch {
            runCatching { LibraryCompat.addToPlaylist(playlistId, pending) }
                .onSuccess { _messages.send(PlaylistPickerMessage.Added) }
                .onFailure { e ->
                    // 写入异常（如 FK 违约）降级为提示，避免直接崩主线程
                    logD("addToPlaylist failed", e)
                    _messages.send(PlaylistPickerMessage.Failed)
                }
        }
    }

    fun createAndAdd(name: String) {
        val pending = videos.value
        if (pending.isEmpty() || name.isBlank()) return
        viewModelScope.launch {
            runCatching {
                val playlist = LibraryCompat.createPlaylist(name.trim())
                LibraryCompat.addToPlaylist(playlist.id, pending)
            }.onSuccess {
                _messages.send(PlaylistPickerMessage.Created)
            }.onFailure { e ->
                logD("createPlaylistAndAdd failed", e)
                _messages.send(PlaylistPickerMessage.Failed)
            }
        }
    }
}

/**
 * 「添加到播放列表」弹层。
 *
 * 迁移前走的是 `AlertDialog.Builder.setItems`，与新的 Compose 视觉不一致；
 * 这里改成底部弹层：列表带条目数，末尾一行可直接新建并添加。
 * 成功提示后自动收起，失败保持打开，方便重试。
 */
@Composable
fun PlaylistPickerSheet(
    videos: List<VideoData>,
    onDismiss: () -> Unit,
    vm: PlaylistPickerViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(videos) { vm.start(videos) }

    val messageTexts = mapOf(
        PlaylistPickerMessage.Added to stringResource(R.string.hint_playlist_video_added),
        PlaylistPickerMessage.Created to stringResource(R.string.hint_playlist_created),
        PlaylistPickerMessage.Failed to stringResource(R.string.hint_playlist_video_add_failed)
    )

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.messages.collect { message ->
                ToastUtil.showToast(context, messageTexts.getValue(message))
                if (message != PlaylistPickerMessage.Failed) onDismiss()
            }
        }
    }

    PlaylistPickerSheetContent(
        state = state,
        videoCount = videos.size,
        onPick = vm::addTo,
        onCreate = vm::createAndAdd,
        onDismiss = onDismiss
    )
}

/** 无状态内容：列表 / 新建两步都在这里，预览可直接渲染。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheetContent(
    state: PlaylistPickerState,
    videoCount: Int,
    onPick: (String) -> Unit = {},
    onCreate: (String) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = c.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                stringResource(R.string.hint_add_to_playlist),
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    if (creating) R.string.hint_playlist_create_desc else R.string.hint_playlist_picker_count,
                    videoCount
                ),
                style = DyLikeType.meta,
                color = c.textTertiary
            )
            Spacer(Modifier.height(14.dp))

            if (creating) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.hint_playlist_name)) },
                    singleLine = true
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GhostButton(
                        text = stringResource(R.string.action_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { creating = false }
                    )
                    PrimaryButton(
                        text = stringResource(R.string.action_create_add),
                        modifier = Modifier.weight(1f),
                        enabled = name.isNotBlank(),
                        onClick = { onCreate(name.trim()) }
                    )
                }
            } else {
                if (state.loading) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = c.accent, modifier = Modifier.size(22.dp))
                    }
                } else if (state.playlists.isEmpty()) {
                    Text(
                        stringResource(R.string.hint_playlist_picker_empty),
                        style = DyLikeType.meta,
                        color = c.textTertiary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    state.playlists.forEachIndexed { index, playlist ->
                        PlaylistPickRow(
                            playlist = playlist,
                            onClick = { onPick(playlist.id) }
                        )
                        if (index < state.playlists.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 52.dp),
                                color = c.hairline
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                NewPlaylistRow(
                    enabled = !state.loading,
                    onClick = {
                        name = ""
                        creating = true
                    }
                )
            }
        }
    }
}

@Composable
private fun PlaylistPickRow(
    playlist: PlaylistUiModel,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                .background(c.accent.copy(alpha = .12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                null,
                tint = c.accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleMedium,
                color = c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.hint_playlist_item_count, playlist.itemCount),
                style = DyLikeType.micro,
                color = c.textTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            Icons.Default.Add,
            contentDescription = stringResource(R.string.cd_playlist_pick),
            tint = c.textTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun NewPlaylistRow(enabled: Boolean, onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .border(1.dp, c.hairline, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            stringResource(R.string.hint_playlist_create_new),
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) c.textPrimary else c.textTertiary
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 560)
@Composable
private fun PlaylistPickerPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        PlaylistPickerSheetContent(
            state = PlaylistPickerState(
                loading = false,
                playlists = listOf(
                    PlaylistUiModel("p1", "周末片单", 12, 2, false),
                    PlaylistUiModel("p2", "随手刷", 3, 1, false)
                )
            ),
            videoCount = 8
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 420)
@Composable
private fun PlaylistPickerEmptyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        PlaylistPickerSheetContent(
            state = PlaylistPickerState(loading = false, playlists = emptyList()),
            videoCount = 1
        )
    }
}
