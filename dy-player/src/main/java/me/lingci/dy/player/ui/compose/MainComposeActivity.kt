package me.lingci.dy.player.ui.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.lingci.dy.player.R
import me.lingci.dy.player.PlayerApp
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
import me.lingci.dy.player.ui.main.MainActivity
import me.lingci.dy.player.ui.main.checkAppUpdateOnLaunch
import me.lingci.dy.player.util.AutoPlayOnLaunch
import me.lingci.dy.player.util.SpUtil
import kotlinx.coroutines.launch

import me.lingci.lib.compose.fileselect.FileSelectorActivity
import me.lingci.lib.base.util.PermissionUtil
import me.lingci.lib.base.util.ToastUtil

class MainComposeActivity : FragmentActivity() {

    companion object {
        const val EXTRA_UI_SWITCH = "home_ui_switch"
    }

    private val updateLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    // 与旧版首页 MainActivity(BaseDisplayActivity) 行为一致：固定字体缩放，
    // 避免系统放大字体时撑破固定尺寸的工具栏与胶囊控件。
    override fun attachBaseContext(newBase: Context) {
        val configuration = newBase.resources.configuration
        configuration.fontScale = 1f
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    override fun getResources(): Resources {
        val resources = super.getResources()
        val configuration = resources.configuration
        return if (configuration.fontScale != 1f) {
            configuration.fontScale = 1f
            baseContext.createConfigurationContext(configuration).resources
        } else {
            resources
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SpUtil(this).launchComposeHome) {
            openLegacyHome(intent.getBooleanExtra(EXTRA_UI_SWITCH, false))
            return
        }
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            // 外壳只做三件事：主题、导航、弹出层；数据读写全部在 ViewModel
            val appearanceVm: AppearanceViewModel = viewModel()
            val mediaVm: MediaLibraryViewModel = viewModel()
            val appearance by appearanceVm.state.collectAsStateWithLifecycle()
            val firstScanPending by mediaVm.firstScanPending.collectAsStateWithLifecycle()
            val lifecycleOwner = LocalLifecycleOwner.current

            // 存储权限由 MediaLibraryViewModel 持有：授权变化时由数据层重订阅，
            // 不再用 key(...) 重建内容树，导航回退栈与滚动位置得以保留。
            val storageAccess by mediaVm.storageAccess.collectAsStateWithLifecycle()
            var permissionPromptDismissed by rememberSaveable { mutableStateOf(false) }

            // 弹出层只记 id：实体由各自的 ViewModel 按 id 读库，
            // 外壳不再把 MediaData 一路传进 Sheet
            var showMediaManagerSheet by remember { mutableStateOf(false) }
            var editingMediaId by remember { mutableStateOf<String?>(null) }
            var showMediaLinkAddSheet by remember { mutableStateOf(false) }
            var editingOnlineMediaId by remember { mutableStateOf<String?>(null) }
            var showDeleteConfirm by remember { mutableStateOf(false) }
            var deletingMediaId by remember { mutableStateOf<String?>(null) }
            var deletingMediaSeriesKey by remember { mutableStateOf("") }

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

            // 权限可能在后台被撤销（或从系统设置返回后才授予）：每次回前台复核一次
            LaunchedEffect(lifecycleOwner, mediaVm) {
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    mediaVm.refreshStorageAccess()
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
                if (!storageAccess && !permissionPromptDismissed) {
                    StoragePermissionDialog(
                        onGranted = { mediaVm.refreshStorageAccess() },
                        onDismiss = { permissionPromptDismissed = true }
                    )
                }

                ComposeApp(
                    navLabels = appearance.navLabels,
                    storageAccess = storageAccess,
                    themeMode = appearance.mode,
                    brand = appearance.brand,
                    dynamicColor = appearance.dynamicColor,
                    onThemeMode = appearanceVm::setMode,
                    onBrand = appearanceVm::setBrand,
                    onDynamicColor = appearanceVm::setDynamicColor,
                    onNavLabels = appearanceVm::setNavLabels,
                    onHomeUiChanged = { enabled ->
                        if (!enabled) openLegacyHome(suppressAutoplay = true)
                    },
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
                            // 本地/远端共用表单：远端库在表单内锁定目录，
                            // 落库走读改写，来源（type/storageType/storageId）不会丢
                            editingMediaId = media.id
                            showMediaManagerSheet = true
                        }
                    },
                    onDeleteMedia = { media ->
                        deletingMediaId = media.id
                        deletingMediaSeriesKey = media.seriesKey
                        showDeleteConfirm = true
                    },
                    onTogglePin = { media -> mediaVm.togglePin(media.id) },
                    firstScanPending = firstScanPending,
                    onFirstScanHandled = mediaVm::consumeFirstScan
                )

                if (showMediaManagerSheet) {
                    MediaManagerSheet(
                        editingMediaId = editingMediaId,
                        onSave = { media, update ->
                            if (update) mediaVm.editMedia(media) else mediaVm.addLocal(media)
                        },
                        onDismiss = { showMediaManagerSheet = false }
                    )
                }

                if (showMediaLinkAddSheet) {
                    MediaLinkAddSheet(
                        editingMediaId = editingOnlineMediaId,
                        onSave = { media, update ->
                            if (update) mediaVm.editOnline(media) else mediaVm.addOnline(media)
                        },
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
                                deletingMediaId?.let { mediaVm.delete(it, deletingMediaSeriesKey) }
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
        if (savedInstanceState == null &&
            !intent.getBooleanExtra(EXTRA_UI_SWITCH, false)
        ) {
            checkAppUpdateOnLaunch(this) { versionData ->
                updateLauncher.launch(
                    Intent(Intent.ACTION_VIEW, versionData.downUrl.toUri())
                )
            }
            lifecycleScope.launch {
                if (!PlayerApp.awaitMigrationNeeded()) {
                    AutoPlayOnLaunch.tryTrigger(this@MainComposeActivity, lifecycleScope)
                }
            }
        }
    }

    private fun openLegacyHome(suppressAutoplay: Boolean) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_UI_SWITCH, suppressAutoplay)
        })
        finish()
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
    onHomeUiChanged: (Boolean) -> Unit,
    onAddLocalMedia: () -> Unit,
    onAddOnlineMedia: () -> Unit,
    onImportOnlineMedia: () -> Unit,

    onEditMedia: (MediaUiModel) -> Unit,
    onDeleteMedia: (MediaUiModel) -> Unit,
    onTogglePin: (MediaUiModel) -> Unit,
    firstScanPending: Boolean,
    storageAccess: Boolean,
    onFirstScanHandled: () -> Unit
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val tabs = listOf(
        NavBarItem(Dest.Library, stringResource(R.string.title_media), Icons.Default.VideoLibrary),
        NavBarItem(Dest.Sources, stringResource(R.string.title_source), Icons.Default.Dns),
        NavBarItem(Dest.Me, stringResource(R.string.title_mine), Icons.Default.Person)
    )

    // 首次进入自动打开扫描页并开始扫描，与旧页的首次扫描时机一致。
    // 先消费再导航：状态消费后置 false，重建时不会重复触发。
    LaunchedEffect(firstScanPending, nav) {
        if (!firstScanPending) return@LaunchedEffect
        onFirstScanHandled()
        nav.navigate(Dest.ScanAuto)
    }

    // 冷启动闸门：旧数据未迁移时先进迁移页。应用级检查在 PlayerApp 里只跑一次，
    // 这段兜的是「直接以 Compose 为入口」，避免迁移被绕过。Activity 已接管常见 configChanges，
    // 重建只有真正的进程/Activity 重建，因此不会因为旋转屏幕重复弹迁移页。
    LaunchedEffect(nav) {
        if (PlayerApp.awaitMigrationNeeded()) nav.navigate(Dest.Migration)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppNavHost(
            navController = nav,
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            themeMode = themeMode,
            brand = brand,
            dynamicColor = dynamicColor,
            navLabels = navLabels,
            storageAccess = storageAccess,
            onThemeMode = onThemeMode,
            onBrand = onBrand,
            onDynamicColor = onDynamicColor,
            onNavLabels = onNavLabels,
            onHomeUiChanged = onHomeUiChanged,
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
