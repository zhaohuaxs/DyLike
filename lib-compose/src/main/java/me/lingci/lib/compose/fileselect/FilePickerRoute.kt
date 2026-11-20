package me.lingci.lib.compose.fileselect

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lingci.lib.compose.R

/**
 * 连接 ViewModel 与无状态 Screen：订阅状态、处理一次性事件（Toast / 结果回传）。
 * ViewModel 由 Activity 以 factory 注入，因此跨进程恢复不会丢配置。
 */
@Composable
internal fun FilePickerRoute(
    viewModel: FilePickerViewModel,
    onBack: () -> Unit,
    onFinish: (paths: List<String>, folderMode: Boolean) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val needTwoText = stringResource(R.string.file_picker_need_two_folders)
    val needConcreteText = stringResource(R.string.file_picker_need_concrete_folder)
    val selectedText = stringResource(R.string.file_picker_current_selected)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FilePickerEvent.Finish -> onFinish(event.paths, event.folderMode)
                FilePickerEvent.Cancel -> onCancel()
                FilePickerEvent.NeedSelectTwoFolders ->
                    Toast.makeText(context, needTwoText, Toast.LENGTH_SHORT).show()
                FilePickerEvent.NeedConcreteFolder ->
                    Toast.makeText(context, needConcreteText, Toast.LENGTH_SHORT).show()
                FilePickerEvent.CurrentFolderSelected ->
                    Toast.makeText(context, selectedText, Toast.LENGTH_SHORT).show()
            }
        }
    }

    FilePickerScreen(
        state = state,
        onBack = onBack,
        onCrumbClick = viewModel::onCrumbClick,
        onFavoriteClick = viewModel::onFavoriteClick,
        onItemClick = viewModel::onItemClick,
        onCheck = { path ->
            if (state.mode == PickerMode.MultiFolder) viewModel.toggleFolder(path)
            else viewModel.onItemClick(path)
        },
        onToggleFavorite = viewModel::toggleFavorite,
        onSelectAll = viewModel::selectAll,
        onSelectInvert = viewModel::selectInvert,
        onSelectCurrent = viewModel::selectCurrentFolder,
        onConfirm = viewModel::confirm,
        modifier = modifier,
    )
}
