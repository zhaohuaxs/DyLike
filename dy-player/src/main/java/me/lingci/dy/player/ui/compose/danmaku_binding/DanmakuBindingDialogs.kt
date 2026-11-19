package me.lingci.dy.player.ui.compose.danmaku_binding

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors

@Composable
fun ClearAllBindingDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.danmaku_binding_dialog_clear_all_title)) },
        text = { Text(stringResource(R.string.danmaku_binding_dialog_clear_all_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirmed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun FileActionDialog(
    file: DanmakuFileUiModel,
    onDismiss: () -> Unit,
    onBind: () -> Unit,
    onSetPrimary: () -> Unit,
    onUnbind: () -> Unit,
    onRebind: () -> Unit,
    onViewOwner: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.title) },
        text = {
            Column {
                when (val owner = file.owner) {
                    is DanmakuFileOwner.None ->
                        ActionRow(R.string.danmaku_binding_action_bind_current, onBind)
                    is DanmakuFileOwner.Current -> {
                        ActionRow(R.string.danmaku_binding_action_set_primary, onSetPrimary)
                        ActionRow(R.string.danmaku_binding_action_unbind_current, onUnbind)
                    }
                    is DanmakuFileOwner.Other -> {
                        ActionRow(R.string.danmaku_binding_action_rebind_current, onRebind)
                        ActionRow(R.string.danmaku_binding_action_view_owner) { onViewOwner(owner.title) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun RebindDialog(
    fileName: String,
    ownerTitle: String,
    currentTitle: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.danmaku_binding_dialog_rebind_title)) },
        text = {
            Text(
                stringResource(
                    R.string.danmaku_binding_dialog_rebind_message,
                    fileName,
                    ownerTitle,
                    currentTitle
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.action_confirmed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun BoundTracksDialog(
    media: DanmakuMediaUiModel,
    onDismiss: () -> Unit,
    onTrackClick: (DanmakuTrackUiModel) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(media.title) },
        text = {
            Column {
                media.tracks.forEach { track ->
                    val label = if (track.isPrimary) {
                        stringResource(R.string.danmaku_binding_track_primary_prefix, track.title)
                    } else {
                        track.title
                    }
                    ActionRowText(label) { onTrackClick(track) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
fun TrackActionDialog(
    track: DanmakuTrackUiModel,
    onDismiss: () -> Unit,
    onSetPrimary: () -> Unit,
    onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.title) },
        text = {
            Column {
                ActionRow(R.string.danmaku_binding_action_set_primary, onSetPrimary)
                ActionRow(R.string.danmaku_binding_action_remove, onRemove)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ActionRow(@StringRes labelRes: Int, onClick: () -> Unit) {
    ActionRowText(stringResource(labelRes), onClick)
}

@Composable
private fun ActionRowText(label: String, onClick: () -> Unit) {
    val c = LocalDyLikeColors.current
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        color = c.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp)
    )
}
