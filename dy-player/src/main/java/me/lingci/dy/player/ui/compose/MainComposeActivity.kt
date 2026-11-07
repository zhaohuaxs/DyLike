package me.lingci.dy.player.ui.compose

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.entity.MediaLibType
import me.lingci.dy.player.ui.compose.components.FloatingNavBar
import me.lingci.dy.player.ui.compose.components.NavBarItem
import me.lingci.dy.player.ui.compose.navigation.AppNavHost
import me.lingci.dy.player.ui.compose.navigation.Dest
import me.lingci.dy.player.ui.compose.navigation.TopLevelRoutes
import me.lingci.dy.player.ui.compose.shell.AppearanceViewModel
import me.lingci.dy.player.ui.compose.shell.MediaLibraryEvent
import me.lingci.dy.player.ui.compose.shell.MediaLibraryViewModel
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.media_add.MediaLinkAddSheet
import me.lingci.dy.player.ui.compose.media_add.MediaManagerSheet

import me.lingci.lib.base.ui.file_select.FileSelectorActivity
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.ToastUtil

class MainComposeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // 外壳只做三件事：主题、导航、弹出层；数据读写全部在 ViewModel
            val appearanceVm: AppearanceViewModel = viewModel()
            val mediaVm: MediaLibraryViewModel = viewModel()
            val appearance by appearanceVm.state.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current

            // 首帧即检查存储权限：targetSdk=28，读写外部存储属于运行时权限，
            // 未授予时 Room 查询会静默返回空列表，用户只会看到「空的媒体库」而没有任何解释。
            // 不能用 rememberSaveable：进程恢复时会把旧的「已授权」带回来，
            // 而权限可能在后台被撤销（或只被 adb grant 改了运行时位）。
            var storageGranted by remember {
                mutableStateOf(PermissionUtil.hasStorageAccess(context))
            }
            var permissionPromptDismissed by rememberSaveable { mutableStateOf(false) }

            // 弹出层只记 id：实体由各自的 ViewModel 按 id 读库，
            // 外壳不再把 MediaData 一路传进 Sheet
            var showMediaManagerSheet by remember { mutableStateOf(false) }
            var editingMediaId by remember { mutableStateOf<String?>(null) }
            var showMediaLinkAddSheet by remember { mutableStateOf(false) }
            var editingOnlineMediaId by remember { mutableStateOf<String?>(null) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var deletingMediaId by remember { mutableStateOf<String?>(null) }

            LaunchedEffect(lifecycleOwner, mediaVm) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    mediaVm.events.collect { event ->
                        when (event) {
                            is MediaLibraryEvent.Message ->
                                ToastUtil.showToast(context, context.getString(event.textRes))
                        }
                    }
                }
            }

            val importOnlineLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    result.data?.getStringArrayListExtra(FileSelectorActivity.KEY_PATH)
                        ?.firstOrNull()
                        ?.let(mediaVm::importOnlineFile)
                }
            }

            DyLikeTheme(
                mode = appearance.mode,
                brand = appearance.brand,
                dynamicColor = appearance.dynamicColor
            ) {
                if (!storageGranted && !permissionPromptDismissed) {
                    StoragePermissionDialog(
                        onGranted = { storageGranted = true },
                        onDismiss = { permissionPromptDismissed = true }
                    )
                }

                // 授权后重建内容树：Room 的 Flow 查询按「订阅时」取数，
                // 不重建则首帧的空结果会一直留在页面上。
                key(storageGranted) {
                    ComposeApp(
                        navLabels = appearance.navLabels,
                        themeMode = appearance.mode,
                        brand = appearance.brand,
                        dynamicColor = appearance.dynamicColor,
                        onThemeMode = appearanceVm::setMode,
                        onBrand = appearanceVm::setBrand,
                        onDynamicColor = appearanceVm::setDynamicColor,
                        onNavLabels = appearanceVm::setNavLabels,
                        onAddLocalMedia = {
                            editingMediaId = null
                            showMediaManagerSheet = true
                        },
                        onAddOnlineMedia = {
                            editingOnlineMediaId = null
                            showMediaLinkAddSheet = true
                        },
                        onImportOnlineMedia = {
                            FileSelectorActivity.startSingle(
                                context as Activity,
                                arrayListOf(".txt"),
                                importOnlineLauncher
                            )
                        },

                        onEditMedia = { media ->
                            if (media.type == MediaLibType.ONLINE) {
                                editingOnlineMediaId = media.id
                                showMediaLinkAddSheet = true
                            } else {
                                editingMediaId = media.id
                                showMediaManagerSheet = true
                            }
                        },
                        onDeleteMedia = { media ->
                            deletingMediaId = media.id
                            showDeleteConfirm = true
                        },
                        onTogglePin = { media -> mediaVm.togglePin(media.id) }
                    )
                }

                if (showMediaManagerSheet) {
                    MediaManagerSheet(
                        editingMediaId = editingMediaId,
                        onSave = mediaVm::saveLocal,
                        onDismiss = { showMediaManagerSheet = false }
                    )
                }

                if (showMediaLinkAddSheet) {
                    MediaLinkAddSheet(
                        editingMediaId = editingOnlineMediaId,
                        onSave = mediaVm::saveOnline,
                        onDismiss = { showMediaLinkAddSheet = false }
                    )
                }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(getString(R.string.hint_delete_media)) },
                        text = { Text(getString(R.string.hint_delete_media_cascade)) },
                        confirmButton = {
                            TextButton(onClick = {
                                deletingMediaId?.let(mediaVm::delete)
                                showDeleteConfirm = false
                            }) { Text(getString(R.string.action_confirmed)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) {
                                Text(getString(R.string.action_cancel))
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 存储权限申请对话框。
 *
 * - 首次进入：说明用途 + 直接申请
 * - 选择「稍后」：不阻塞使用，只影响本地媒体的读取
 * - 被永久拒绝（不再询问）：引导到系统设置手动开启，并在回到前台时复查
 */
@Composable
private fun StoragePermissionDialog(
    onGranted: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val permissions = PermissionUtil.storagePermissions

    // 运行时权限已授予但 AppOps 被拦时，系统不会再弹授权框，只能去设置里重开
    var permanentlyDenied by rememberSaveable {
        mutableStateOf(
            PermissionUtil.checkPermissions(context, permissions) &&
                !PermissionUtil.hasStorageAccess(context)
        )
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isNotEmpty() && result.values.all { granted -> granted } &&
            PermissionUtil.hasStorageAccess(context)
        ) {
            onGranted()
        } else {
            permanentlyDenied = activity != null &&
                PermissionUtil.storagePermissions.none { activity.shouldShowRequestPermissionRationale(it) }
        }
    }

    // 从系统设置返回后复查：Settings 页授权不会回调上面的 launcher
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                PermissionUtil.hasStorageAccess(context)
            ) {
                onGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.permission_storage_title)) },
        text = {
            Text(
                stringResource(
                    if (permanentlyDenied) R.string.permission_storage_denied
                    else R.string.permission_storage_rationale
                )
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (permanentlyDenied) {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null)
                    )
                    context.startActivity(intent)
                } else {
                    launcher.launch(permissions)
                }
            }) {
                Text(
                    stringResource(
                        if (permanentlyDenied) R.string.permission_open_settings
                        else R.string.permission_grant
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.permission_later)) }
        }
    )
}

private val tabs = listOf(
    NavBarItem(Dest.Library, "媒体库", Icons.Default.VideoLibrary),
    NavBarItem(Dest.Sources, "资源库", Icons.Default.Dns),
    NavBarItem(Dest.Me, "我的", Icons.Default.Person)
)

@Composable
private fun ComposeApp(
    navLabels: Boolean,
    themeMode: ThemeMode,
    brand: BrandHue,
    dynamicColor: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onBrand: (BrandHue) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onNavLabels: (Boolean) -> Unit,
    onAddLocalMedia: () -> Unit,
    onAddOnlineMedia: () -> Unit,
    onImportOnlineMedia: () -> Unit,

    onEditMedia: (MediaUiModel) -> Unit,
    onDeleteMedia: (MediaUiModel) -> Unit,
    onTogglePin: (MediaUiModel) -> Unit
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppNavHost(
            navController = nav,
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            themeMode = themeMode,
            brand = brand,
            dynamicColor = dynamicColor,
            navLabels = navLabels,
            onThemeMode = onThemeMode,
            onBrand = onBrand,
            onDynamicColor = onDynamicColor,
            onNavLabels = onNavLabels,
            onAddLocalMedia = onAddLocalMedia,
            onAddOnlineMedia = onAddOnlineMedia,
            onImportOnlineMedia = onImportOnlineMedia,

            onEditMedia = onEditMedia,
            onDeleteMedia = onDeleteMedia,
            onTogglePin = onTogglePin
        )
        if (currentRoute in TopLevelRoutes) {
            FloatingNavBar(
                items = tabs,
                selectedKey = currentRoute ?: Dest.Library,
                showLabels = navLabels,
                onSelect = { key ->
                    nav.navigate(key) {
                        popUpTo(Dest.Library) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    }
}
