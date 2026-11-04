package me.lingci.dy.player.ui.compose.media_add

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.lingci.dy.player.R
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.ui.compose.components.ThumbImage
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.util.ToastUtil

/**
 * 路由入口：取 ViewModel、订阅状态与一次性事件，表单本身无状态。
 *
 * [editingMediaId] 为 null 是新增；表单不再接收实体，编辑态由 ViewModel 自己按 id 读库。
 */
@Composable
fun MediaLinkAddSheet(
    editingMediaId: String? = null,
    onSave: (media: MediaData, updated: Boolean) -> Unit,
    onDismiss: () -> Unit,
    vm: MediaLinkAddViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    LaunchedEffect(editingMediaId, vm) { vm.start(editingMediaId) }

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is MediaLinkEvent.Message ->
                        ToastUtil.showToast(context, context.getString(event.textRes))
                }
            }
        }
    }

    MediaLinkAddSheetContent(
        state = state,
        onLinkChange = vm::onLinkChange,
        onParse = vm::parse,
        onRemove = vm::removeAt,
        onClearAll = vm::clearAll,
        onSave = {
            vm.save()?.let { media -> onSave(media, state.isEdit) }
            onDismiss()
        },
        onDismiss = onDismiss
    )
}

/** 无状态表单：所有输入都是参数，预览可以直接渲染。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MediaLinkAddSheetContent(
    state: MediaLinkUiState,
    onLinkChange: (String) -> Unit = {},
    onParse: () -> Unit = {},
    onRemove: (Int) -> Unit = {},
    onClearAll: () -> Unit = {},
    onSave: () -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val c = LocalDyLikeColors.current
    val sheetState = rememberModalBottomSheetState()
    // 长按菜单的目标位置：逐项持有布尔量的话，删掉一项后其余项的状态会串位
    var menuIndex by remember { mutableIntStateOf(-1) }

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
                if (state.isEdit) "在线媒体库修改" else "添加在线媒体库",
                style = MaterialTheme.typography.titleLarge,
                color = c.textPrimary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = state.linkText,
                onValueChange = onLinkChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("链接列表") },
                supportingText = { Text("输入可播放的媒体列表，示例 #EP 第1话;https?://media.m3u8") },
                trailingIcon = {
                    if (state.linkText.isNotEmpty()) {
                        IconButton(onClick = { onLinkChange("") }) {
                            Icon(Icons.Default.Clear, "清除", tint = c.textSecondary)
                        }
                    }
                },
                minLines = 3,
                maxLines = 6
            )
            Spacer(Modifier.height(8.dp))

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(c.surfaceRaised)
                    .padding(8.dp)
            ) {
                LinkThumb(
                    state.coverPath,
                    Modifier.width(96.dp).aspectRatio(3f / 4f).clip(RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(8.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        state.title.ifBlank { if (state.isEdit) "在线媒体库" else "新增媒体库" },
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
                        color = c.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.playType.ifBlank {
                            if (state.itemNames.isNotEmpty()) {
                                "包含 ${state.itemNames.size} 条媒体"
                            } else if (state.isEdit) {
                                "暂无分集"
                            } else {
                                "待输入"
                            }
                        },
                        style = DyLikeType.meta,
                        color = c.textSecondary,
                        maxLines = 3
                    )
                    Spacer(Modifier.weight(1f))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onParse, enabled = !state.parsing) {
                            Icon(Icons.Default.Link, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.parsing) "解析中…" else "解析链接")
                        }
                        if (state.canSave) {
                            TextButton(onClick = onSave) { Text("保存") }
                        }
                    }
                }
            }

            state.itemNames.forEachIndexed { index, name ->
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.surfaceRaised)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { menuIndex = index }
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                        color = c.textPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    DropdownMenu(
                        expanded = menuIndex == index,
                        onDismissRequest = { menuIndex = -1 }
                    ) {
                        DropdownMenuItem(
                            text = { Text("删除当前") },
                            onClick = {
                                menuIndex = -1
                                onRemove(index)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("清除全部") },
                            onClick = {
                                menuIndex = -1
                                onClearAll()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkThumb(path: String?, modifier: Modifier = Modifier) {
    ThumbImage(
        path = path,
        modifier = modifier,
        contentScale = ContentScale.Crop,
        placeholder = {
            Image(
                painter = painterResource(R.drawable.ic_media_default),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize()
            )
        }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 720)
@Composable
private fun MediaLinkAddPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        MediaLinkAddSheetContent(
            state = MediaLinkUiState(
                linkText = "#EP 第1话;https://example.com/1.m3u8",
                title = "示例在线媒体",
                playType = "M3U8",
                itemNames = listOf("第1话", "第2话")
            )
        )
    }
}
