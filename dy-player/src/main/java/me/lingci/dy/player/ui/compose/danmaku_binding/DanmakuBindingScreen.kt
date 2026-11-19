package me.lingci.dy.player.ui.compose.danmaku_binding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.components.PrimaryButton
import me.lingci.dy.player.ui.compose.components.SearchField
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.theme.findActivity
import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.util.ToastUtil

/** 路由入口：订阅状态与一次性提示，页面本身无状态。 */
@Composable
fun DanmakuBindingRoute(
    mediaId: String,
    tempFilePath: String,
    onBack: () -> Unit,
    vm: DanmakuBindingViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var pendingExit by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(vm::chooseFolder)
        }
    }

    LaunchedEffect(mediaId, tempFilePath) { vm.load(mediaId, tempFilePath) }

    // 资源 id 只能在组合期解析，事件回调里用不了 stringResource
    val tplFolderLoaded = stringResource(R.string.danmaku_binding_hint_folder_loaded)
    val msgNothingToClear = stringResource(R.string.danmaku_binding_hint_nothing_to_clear)
    val msgClearedAll = stringResource(R.string.danmaku_binding_hint_cleared_all)
    val msgReset = stringResource(R.string.danmaku_binding_hint_reset)
    val msgArchive = stringResource(R.string.danmaku_binding_hint_archive_unsupported)
    val msgNoTracks = stringResource(R.string.danmaku_binding_hint_no_bound_tracks)
    val tplOwner = stringResource(R.string.danmaku_binding_hint_owner)
    val tplSaved = stringResource(R.string.danmaku_binding_hint_saved)
    val tplSaveFailed = stringResource(R.string.danmaku_binding_hint_save_failed)
    val msgNoChanges = stringResource(R.string.danmaku_binding_hint_no_changes)

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is DanmakuBindingEvent.Message -> {
                        val text = when (val message = event.message) {
                            is DanmakuBindingMessage.FolderLoaded -> tplFolderLoaded.format(message.count)
                            DanmakuBindingMessage.NothingToClear -> msgNothingToClear
                            DanmakuBindingMessage.ClearedAll -> msgClearedAll
                            DanmakuBindingMessage.Reset -> msgReset
                            DanmakuBindingMessage.ArchiveUnsupported -> msgArchive
                            DanmakuBindingMessage.NoBoundTracks -> msgNoTracks
                            is DanmakuBindingMessage.OwnerInfo -> tplOwner.format(message.title)
                            is DanmakuBindingMessage.Saved -> tplSaved.format(message.count)
                            is DanmakuBindingMessage.SaveFailed -> tplSaveFailed.format(message.count)
                            DanmakuBindingMessage.NoChanges -> msgNoChanges
                        }
                        ToastUtil.showToast(context, text)
                    }
                    DanmakuBindingEvent.SaveFinished -> if (pendingExit) {
                        pendingExit = false
                        onBack()
                    }
                }
            }
        }
    }

    // 退出前自动保存未提交的绑定；保存结束后由 SaveFinished 事件放行返回
    val requestBack = {
        if (!pendingExit) {
            if (vm.hasUnsavedChanges()) {
                pendingExit = true
                vm.save()
            } else {
                onBack()
            }
        }
    }

    BackHandler(onBack = requestBack)

    DanmakuBindingScreen(
        state = state,
        onBack = requestBack,
        onSelectFolder = {
            context.findActivity()?.let { activity ->
                FileSelectorActivity.startFolder(activity, folderPicker)
            }
        },
        onQueryChange = vm::setQuery,
        onSelectMedia = vm::selectMedia,
        onBindFile = vm::bindFile,
        onRebindFile = vm::rebindFile,
        onSetPrimary = vm::setPrimary,
        onRemoveTrack = vm::removeTrack,
        onClearCurrent = vm::clearCurrent,
        onRequestClearAll = vm::requestClearAll,
        onReset = vm::resetMatches,
        onSave = vm::save,
        onNotifyArchive = vm::notifyArchiveUnsupported,
        onNotifyNoTracks = vm::showBoundTracks,
        onNotifyOwner = vm::notifyOwnerInfo,
        onNotifyNothingToClear = vm::notifyNothingToClear
    )
}

@Composable
fun DanmakuBindingScreen(
    state: DanmakuBindingUiState,
    onBack: () -> Unit = {},
    onSelectFolder: () -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onSelectMedia: (Int) -> Unit = {},
    onBindFile: (Int) -> Unit = {},
    onRebindFile: (Int) -> Unit = {},
    onSetPrimary: (Int, String) -> Unit = { _, _ -> },
    onRemoveTrack: (Int, String) -> Unit = { _, _ -> },
    onClearCurrent: () -> Unit = {},
    onRequestClearAll: () -> Unit = {},
    onReset: () -> Unit = {},
    onSave: () -> Unit = {},
    onNotifyArchive: () -> Unit = {},
    onNotifyNoTracks: (Int) -> Unit = {},
    onNotifyOwner: (String) -> Unit = {},
    onNotifyNothingToClear: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current

    var boundTracksFor by remember { mutableStateOf<DanmakuMediaUiModel?>(null) }
    var trackActionFor by remember { mutableStateOf<Pair<DanmakuMediaUiModel, DanmakuTrackUiModel>?>(null) }
    var fileMenuFor by remember { mutableStateOf<DanmakuFileUiModel?>(null) }
    var rebindFor by remember { mutableStateOf<DanmakuFileUiModel?>(null) }
    var clearAllConfirm by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().background(c.background)) {
        // 顶栏：返回 / 标题 / 还原 / 选择目录
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Kicker("DANMAKU")
                Text(
                    stringResource(R.string.title_danmaku_binding),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AppIconButton(Icons.Default.Refresh, stringResource(R.string.action_danmaku_binding_reset), onReset)
            FolderButton(onClick = onSelectFolder)
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                stringResource(
                    R.string.hint_danmaku_binding_summary,
                    state.summary.totalMedia,
                    state.summary.boundMedia,
                    state.summary.trackCount,
                    state.summary.changedCount
                ),
                style = DyLikeType.meta,
                color = c.textSecondary
            )
            if (state.folderPath.isNotBlank()) {
                Text(
                    stringResource(R.string.hint_danmaku_binding_folder, state.folderPath),
                    style = DyLikeType.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(8.dp))
            SearchField(
                value = state.query,
                onValueChange = onQueryChange,
                placeholder = stringResource(R.string.hint_danmaku_binding_search),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }

        when (state.phase) {
            DanmakuBindingPhase.Loading -> CenterHint(
                stringResource(R.string.danmaku_binding_loading),
                Modifier.weight(1f).fillMaxWidth()
            )
            DanmakuBindingPhase.Error -> CenterHint(
                stringResource(R.string.danmaku_binding_error),
                Modifier.weight(1f).fillMaxWidth()
            )
            DanmakuBindingPhase.Empty -> CenterHint(
                stringResource(R.string.danmaku_binding_empty_media),
                Modifier.weight(1f).fillMaxWidth()
            )
            DanmakuBindingPhase.Content -> Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.media.isEmpty()) {
                        ListHint(stringResource(R.string.danmaku_binding_empty_search))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            items(state.media, key = { it.key }) { item ->
                                MediaRow(
                                    item = item,
                                    onClick = { onSelectMedia(item.sourceIndex) },
                                    onShowTracks = {
                                        if (item.tracks.isEmpty()) {
                                            onNotifyNoTracks(item.sourceIndex)
                                        } else {
                                            boundTracksFor = item
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.hairline))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (state.files.isEmpty()) {
                        ListHint(stringResource(R.string.danmaku_binding_empty_files))
                    } else {
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                            items(state.files, key = { it.key }) { item ->
                                FileRow(
                                    item = item,
                                    onClick = {
                                        when {
                                            item.isArchive -> onNotifyArchive()
                                            item.owner is DanmakuFileOwner.Other -> rebindFor = item
                                            item.owner is DanmakuFileOwner.Current ->
                                                onSetPrimary(state.selectedIndex, item.key)
                                            else -> onBindFile(item.sourceIndex)
                                        }
                                    },
                                    onLongClick = {
                                        if (item.isArchive) onNotifyArchive() else fileMenuFor = item
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        BottomBar(
            onClearCurrent = onClearCurrent,
            onRequestClearAll = {
                if (state.summary.trackCount == 0) {
                    onNotifyNothingToClear()
                } else {
                    clearAllConfirm = true
                }
            },
            onSave = onSave
        )
    }

    if (clearAllConfirm) {
        ClearAllBindingDialog(
            onDismiss = { clearAllConfirm = false },
            onConfirm = {
                clearAllConfirm = false
                onRequestClearAll()
            }
        )
    }

    fileMenuFor?.let { file ->
        FileActionDialog(
            file = file,
            onDismiss = { fileMenuFor = null },
            onBind = {
                fileMenuFor = null
                onBindFile(file.sourceIndex)
            },
            onSetPrimary = {
                fileMenuFor = null
                onSetPrimary(state.selectedIndex, file.key)
            },
            onUnbind = {
                fileMenuFor = null
                onRemoveTrack(state.selectedIndex, file.key)
            },
            onRebind = {
                fileMenuFor = null
                rebindFor = file
            },
            onViewOwner = { ownerTitle ->
                fileMenuFor = null
                onNotifyOwner(ownerTitle)
            }
        )
    }

    rebindFor?.let { file ->
        val ownerTitle = (file.owner as? DanmakuFileOwner.Other)?.title.orEmpty()
        val currentTitle = state.media.firstOrNull { it.selected }?.title.orEmpty()
        RebindDialog(
            fileName = file.title,
            ownerTitle = ownerTitle,
            currentTitle = currentTitle,
            onDismiss = { rebindFor = null },
            onConfirm = {
                rebindFor = null
                onRebindFile(file.sourceIndex)
            }
        )
    }

    boundTracksFor?.let { media ->
        BoundTracksDialog(
            media = media,
            onDismiss = { boundTracksFor = null },
            onTrackClick = { track ->
                boundTracksFor = null
                trackActionFor = media to track
            }
        )
    }

    trackActionFor?.let { (media, track) ->
        TrackActionDialog(
            track = track,
            onDismiss = { trackActionFor = null },
            onSetPrimary = {
                trackActionFor = null
                onSetPrimary(media.sourceIndex, track.key)
            },
            onRemove = {
                trackActionFor = null
                onRemoveTrack(media.sourceIndex, track.key)
            }
        )
    }
}

@Composable
private fun FolderButton(onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Add, null, tint = c.textSecondary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(R.string.action_danmaku_binding_select_folder),
            style = DyLikeType.meta,
            color = c.textPrimary,
            maxLines = 1
        )
    }
}

@Composable
private fun MediaRow(
    item: DanmakuMediaUiModel,
    onClick: () -> Unit,
    onShowTracks: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (item.selected) c.accent.copy(alpha = .12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                // 同剧文件名前缀高度重复，两行才能露出集数等区分信息
                item.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (item.selected) c.accent else c.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                mediaSummary(item),
                style = DyLikeType.micro,
                color = if (item.selected) c.textSecondary else c.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(enabled = item.tracks.isNotEmpty(), onClick = onShowTracks),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(R.drawable.ic_dm_track),
                stringResource(R.string.cd_danmaku_binding_tracks),
                tint = if (item.tracks.isNotEmpty()) c.textSecondary else c.textTertiary.copy(alpha = .35f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    item: DanmakuFileUiModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    val dimmed = item.owner is DanmakuFileOwner.Other
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) .55f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (item.owner is DanmakuFileOwner.Current) c.accent.copy(alpha = .10f) else Color.Transparent
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (item.owner is DanmakuFileOwner.Current) c.accent else c.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val summary = fileSummary(item)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    summary,
                    style = DyLikeType.micro,
                    color = c.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            null,
            tint = c.textTertiary,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun BottomBar(
    onClearCurrent: () -> Unit,
    onRequestClearAll: () -> Unit,
    onSave: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TextButton(onClick = onRequestClearAll) {
            Text(
                stringResource(R.string.action_danmaku_binding_clear_all),
                style = DyLikeType.meta,
                color = c.textSecondary
            )
        }
        TextButton(onClick = onClearCurrent) {
            Text(
                stringResource(R.string.action_danmaku_binding_clear_current),
                style = DyLikeType.meta,
                color = c.textSecondary
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = stringResource(R.string.action_danmaku_binding_save),
            onClick = onSave
        )
    }
}

@Composable
private fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(text, style = DyLikeType.meta, color = LocalDyLikeColors.current.textTertiary)
    }
}

@Composable
private fun ListHint(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = DyLikeType.micro,
            color = LocalDyLikeColors.current.textTertiary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

@Composable
private fun mediaSummary(item: DanmakuMediaUiModel): String {
    val parts = buildList {
        add(stringResource(R.string.danmaku_binding_bound_count, item.boundCount))
        // 数量已表达 Bound/Unbound，这两种默认态不再重复拼提示
        when (item.hint) {
            is DanmakuMatchHint.Bound, is DanmakuMatchHint.Unbound -> Unit
            else -> add(hintText(item.hint, item.boundCount))
        }
        item.primaryTitle?.let { add(stringResource(R.string.danmaku_binding_primary, it)) }
        if (item.hasChanges) add(stringResource(R.string.danmaku_binding_pending_save))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun hintText(hint: DanmakuMatchHint, boundCount: Int): String = when (hint) {
    is DanmakuMatchHint.Bound -> stringResource(R.string.danmaku_binding_bound_count, boundCount)
    is DanmakuMatchHint.Unbound -> stringResource(R.string.danmaku_binding_unbound)
    is DanmakuMatchHint.UnresolvedArchive ->
        stringResource(R.string.danmaku_binding_hint_unresolved_archive, hint.count)
    is DanmakuMatchHint.Unmatched -> stringResource(R.string.danmaku_binding_hint_unmatched)
    is DanmakuMatchHint.PrimarySwitched ->
        stringResource(R.string.danmaku_binding_hint_primary_switched, hint.title)
    is DanmakuMatchHint.ManualBound ->
        stringResource(R.string.danmaku_binding_hint_manual_bound, hint.title)
    is DanmakuMatchHint.AutoMatched ->
        stringResource(R.string.danmaku_binding_hint_auto_matched, hint.title)
    is DanmakuMatchHint.Scored -> {
        val episode = when (hint.episodeSame) {
            true -> stringResource(R.string.danmaku_binding_hint_episode_same)
            false -> stringResource(R.string.danmaku_binding_hint_episode_diff)
            null -> ""
        }
        val score = stringResource(R.string.danmaku_binding_hint_score, hint.score)
        listOf(episode, score).filter { it.isNotBlank() }.joinToString(" ")
    }
}

@Composable
private fun fileSummary(item: DanmakuFileUiModel): String {
    val parts = buildList {
        if (item.score > 0) add(stringResource(R.string.danmaku_binding_hint_score, item.score))
        when (val owner = item.owner) {
            is DanmakuFileOwner.None -> Unit
            is DanmakuFileOwner.Current -> add(
                stringResource(
                    if (item.isPrimaryForCurrent) {
                        R.string.danmaku_binding_primary_for_current
                    } else {
                        R.string.danmaku_binding_bound_to_current
                    }
                )
            )
            is DanmakuFileOwner.Other ->
                add(stringResource(R.string.danmaku_binding_bound_to_owner, owner.title))
        }
    }
    return parts.joinToString(" · ")
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 640)
@Composable
private fun DanmakuBindingContentPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        DanmakuBindingScreen(
            state = DanmakuBindingUiState(
                phase = DanmakuBindingPhase.Content,
                media = listOf(
                    DanmakuMediaUiModel(
                        sourceIndex = 0,
                        key = "v1",
                        title = "进击的巨人 第一季 01.mkv",
                        boundCount = 2,
                        primaryTitle = "进击的巨人 S01E01.ass",
                        hint = DanmakuMatchHint.Bound,
                        hasChanges = true,
                        selected = true,
                        tracks = emptyList()
                    ),
                    DanmakuMediaUiModel(
                        sourceIndex = 1,
                        key = "v2",
                        title = "进击的巨人 第一季 02.mkv",
                        boundCount = 0,
                        primaryTitle = null,
                        hint = DanmakuMatchHint.Unbound,
                        hasChanges = false,
                        selected = false,
                        tracks = emptyList()
                    )
                ),
                files = listOf(
                    DanmakuFileUiModel(
                        sourceIndex = 0,
                        key = "/dm/E01.ass",
                        title = "进击的巨人 S01E01.ass",
                        score = 92,
                        isArchive = false,
                        owner = DanmakuFileOwner.Current,
                        isPrimaryForCurrent = true
                    ),
                    DanmakuFileUiModel(
                        sourceIndex = 1,
                        key = "/dm/E02.ass",
                        title = "进击的巨人 S01E02.ass",
                        score = 78,
                        isArchive = false,
                        owner = DanmakuFileOwner.None,
                        isPrimaryForCurrent = false
                    )
                ),
                folderPath = "/storage/emulated/0/Danmaku",
                summary = DanmakuBindingSummary(2, 1, 2, 1)
            )
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 400)
@Composable
private fun DanmakuBindingEmptyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        DanmakuBindingScreen(state = DanmakuBindingUiState(phase = DanmakuBindingPhase.Empty))
    }
}
