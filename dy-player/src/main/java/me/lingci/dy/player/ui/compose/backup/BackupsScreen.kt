package me.lingci.dy.player.ui.compose.backup

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import me.lingci.dy.player.ui.compose.components.SettingRow
import me.lingci.dy.player.ui.compose.settings.SettingsNoteLine
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.theme.findActivity
import me.lingci.lib.compose.fileselect.FileSelectorActivity
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.ToastUtil

/** 路由入口：接事件（提示/完成/旧备份迁移）与两次还原确认。 */
@Composable
fun BackupsRoute(
    onBack: () -> Unit,
    onOpenMigration: () -> Unit = {},
    vm: BackupsViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showEntryConfirm by remember { mutableStateOf(false) }
    var pendingFile by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { pendingFile = it }
        }
    }

    val tplBackupDone = stringResource(R.string.hint_backup_done)
    val msgBackupFailed = stringResource(R.string.hint_backup_failed)
    val msgRestoreInvalid = stringResource(R.string.hint_restore_invalid)
    val tplRestoreFailed = stringResource(R.string.hint_restore_failed)
    val msgRestoreMigrating = stringResource(R.string.hint_restore_migrating)

    LaunchedEffect(lifecycleOwner, vm) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is BackupsEvent.BackupDone ->
                        ToastUtil.showToast(context, tplBackupDone.format(event.fileName))
                    BackupsEvent.BackupFailed ->
                        ToastUtil.showToast(context, msgBackupFailed)
                    BackupsEvent.RestoreDone -> showDone = true
                    BackupsEvent.RestoreInvalid ->
                        ToastUtil.showToast(context, msgRestoreInvalid)
                    is BackupsEvent.RestoreFailed ->
                        ToastUtil.showToast(context, tplRestoreFailed.format(event.detail.orEmpty()))
                    BackupsEvent.RestoreNeedsMigration -> {
                        ToastUtil.showToast(context, msgRestoreMigrating)
                        onOpenMigration()
                    }
                }
            }
        }
    }

    BackupsScreen(
        state = state,
        onBack = onBack,
        onBackup = vm::backup,
        onRestore = { showEntryConfirm = true }
    )

    if (showEntryConfirm) {
        AlertDialog(
            onDismissRequest = { showEntryConfirm = false },
            title = { Text(stringResource(R.string.dialog_restore_entry_title)) },
            text = { Text(stringResource(R.string.dialog_restore_entry_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showEntryConfirm = false
                    context.findActivity()?.let {
                        FileSelectorActivity.startSingle(it, FileOperator.JSON_EXTENSIONS, picker)
                    }
                }) { Text(stringResource(R.string.action_confirmed)) }
            },
            dismissButton = {
                TextButton(onClick = { showEntryConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    pendingFile?.let { path ->
        AlertDialog(
            onDismissRequest = { pendingFile = null },
            title = { Text(stringResource(R.string.dialog_restore_confirm_title)) },
            text = { Text(stringResource(R.string.dialog_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingFile = null
                    vm.restore(path)
                }) { Text(stringResource(R.string.action_confirmed)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFile = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showDone) {
        AlertDialog(
            onDismissRequest = { showDone = false },
            title = { Text(stringResource(R.string.dialog_restore_done_title)) },
            text = { Text(stringResource(R.string.dialog_restore_done_message)) },
            confirmButton = {
                TextButton(onClick = { showDone = false }) {
                    Text(stringResource(R.string.action_confirmed))
                }
            }
        )
    }
}

@Composable
fun BackupsScreen(
    state: BackupsUiState,
    onBack: () -> Unit = {},
    onBackup: () -> Unit = {},
    onRestore: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current
    val busy = state.busy

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            Column(Modifier.padding(start = 6.dp)) {
                Kicker("BACKUP")
                Text(
                    stringResource(R.string.title_backup_restore),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.weight(1f))
            if (busy) {
                Text(
                    stringResource(
                        if (state.task == BackupsTask.Restore) R.string.action_restore_running
                        else R.string.action_backup_running
                    ),
                    style = me.lingci.dy.player.ui.compose.theme.DyLikeType.meta,
                    color = c.accent
                )
            }
        }
        if (busy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                color = c.accent
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item { SettingsNoteLine(stringResource(R.string.hint_backup_note)) }
            item {
                SettingRow(
                    title = stringResource(R.string.hint_backup_data),
                    summary = stringResource(R.string.hint_backup_data_desc),
                    value = stringResource(
                        if (busy && state.task == BackupsTask.Backup) R.string.action_backup_running
                        else R.string.action_backup_now
                    ),
                    onClick = { if (!busy) onBackup() }
                )
            }
            item {
                SettingRow(
                    title = stringResource(R.string.hint_restore_data),
                    summary = stringResource(R.string.hint_restore_data_desc),
                    value = stringResource(
                        if (busy && state.task == BackupsTask.Restore) R.string.action_restore_running
                        else R.string.action_choose_file
                    ),
                    onClick = { if (!busy) onRestore() }
                )
            }
            item { SettingsNoteLine(stringResource(R.string.hint_backup_location)) }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 520)
@Composable
private fun BackupsContentPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        BackupsScreen(state = BackupsUiState())
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 520)
@Composable
private fun BackupsBusyPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        BackupsScreen(state = BackupsUiState(busy = true, task = BackupsTask.Backup))
    }
}
