package me.lingci.dy.player.ui.compose.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.entity.MediaData
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.ui.compose.about.AboutRoute
import me.lingci.dy.player.ui.compose.backup.BackupsRoute
import me.lingci.dy.player.ui.compose.browser.BrowserScreen
import me.lingci.dy.player.ui.compose.danmaku_binding.DanmakuBindingRoute
import me.lingci.dy.player.ui.compose.danmaku_binding.DanmakuBindingTransfer
import me.lingci.dy.player.ui.compose.detail.DetailRoute
import me.lingci.dy.player.ui.compose.history.HistoryRoute
import me.lingci.dy.player.ui.compose.likes.LikesRoute
import me.lingci.dy.player.ui.compose.library.LibraryRoute
import me.lingci.dy.player.ui.compose.playlist.PlaylistRoute
import me.lingci.dy.player.ui.compose.me.MeScreen
import me.lingci.dy.player.ui.compose.migration.MigrationRoute
import me.lingci.dy.player.ui.compose.scan.MediaScanRoute
import me.lingci.dy.player.ui.compose.settings.DanmakuSettingsDetail
import me.lingci.dy.player.ui.compose.settings.KernelSettingsDetail
import me.lingci.dy.player.ui.compose.settings.LabSettingsDetail
import me.lingci.dy.player.ui.compose.settings.LibrarySettingsDetail
import me.lingci.dy.player.ui.compose.settings.PlaybackSettingsDetail
import me.lingci.dy.player.ui.compose.settings.settingsViewModel
import me.lingci.dy.player.ui.compose.sources.BuiltinSources
import me.lingci.dy.player.ui.compose.sources.SourceByIdViewModel
import me.lingci.dy.player.ui.compose.sources.SourcesScreen
import me.lingci.dy.player.ui.compose.icon.IconSettingsRoute
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.BuildConfig
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.theme_screen.ThemeScreen
import me.lingci.dy.player.util.LibraryCompat
import me.lingci.dy.player.util.SeriesKey

object Dest {
    const val Library = "library"
    const val Sources = "sources"
    const val Me = "me"
    const val Detail = "detail"
    const val DetailWithMode = "detail/{mediaId}?directPlay={directPlay}"
    const val History = "history"
    const val Likes = "likes"
    const val Playlists = "playlists"
    const val Theme = "theme"
    /** 设置分类页：「我的」页直接列设置入口，这里只是分类详情，没有中间的「全部设置」索引层 */
    const val SettingsPlayback = "settings/playback"
    const val SettingsLibrary = "settings/library"
    const val SettingsKernel = "settings/kernel"
    const val SettingsDanmaku = "settings/danmaku"
    const val SettingsLab = "settings/lab"
    const val SettingsBackup = "settings/backup"
    const val ShortPlayer = "shortPlayer"
    const val LongPlayer = "longPlayer"
    const val LongPlayerWithVideo = "longPlayer/{videoId}"
    const val Browser = "browser"
    const val BrowserWithSource = "browser/{sourceId}"
    const val Scan = "scan"
    /** 首次进入的自动扫描入口：同页但进页面即开始，对齐 View 侧 MediaScanActivity(autoStart=true) */
    const val ScanAuto = "scan/auto"
    const val Danmaku = "danmaku"
    /** 弹幕绑定页：mediaId 用于记住上次弹幕目录，temp 是详情页传来的视频列表临时文件路径 */
    const val DanmakuWithMedia = "danmaku/{mediaId}?temp={temp}"
    /** 旧版数据迁移页：既能被冷启动闸门推入，也能从「我的」手动进入 */
    const val Migration = "migration"
    const val About = "about"
    /** 桌面图标切换：入口在「个性化」页，属于外观设置 */
    const val Icon = "icon"
}

val TopLevelRoutes = setOf(Dest.Library, Dest.Sources, Dest.Me)
val PlayerRoutes = setOf(Dest.ShortPlayer, Dest.LongPlayer, Dest.LongPlayerWithVideo)

@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    themeMode: ThemeMode = ThemeMode.System,
    brand: BrandHue = BrandHue.Purple,
    dynamicColor: Boolean = false,
    navLabels: Boolean = false,
    /** 存储权限：媒体库查询据此重订阅，权限变化不再重建整棵导航树 */
    storageAccess: Boolean = true,
    onThemeMode: (ThemeMode) -> Unit = {},
    onBrand: (BrandHue) -> Unit = {},
    onDynamicColor: (Boolean) -> Unit = {},
    onNavLabels: (Boolean) -> Unit = {},
    onHomeUiChanged: (Boolean) -> Unit = {},
    onAddLocalMedia: () -> Unit = {},
    onAddOnlineMedia: () -> Unit = {},
    onImportOnlineMedia: () -> Unit = {},

    onEditMedia: (MediaUiModel) -> Unit = {},
    onDeleteMedia: (MediaUiModel) -> Unit = {},
    onTogglePin: (MediaUiModel) -> Unit = {}
) {
    val navTransitions = AppNavTransitions(TopLevelRoutes, PlayerRoutes)
    NavHost(
        navController = navController,
        startDestination = Dest.Library,
        modifier = modifier,
        enterTransition = {
            navTransitions.enter(initialState.destination.route, targetState.destination.route)
        },
        exitTransition = {
            navTransitions.exit(initialState.destination.route, targetState.destination.route)
        },
        popEnterTransition = {
            navTransitions.popEnter(initialState.destination.route, targetState.destination.route)
        },
        popExitTransition = {
            navTransitions.popExit(initialState.destination.route, targetState.destination.route)
        }
    ) {
        composable(Dest.Library) {
            val settingsVm = settingsViewModel()
            val settingsState by settingsVm.state.collectAsStateWithLifecycle()
            val openMedia: (String) -> Unit = { mediaId ->
                navController.navigate(
                    "${Dest.Detail}/${Uri.encode(mediaId)}?directPlay=${!settingsState.library.videoDetailMode}"
                )
            }
            LibraryRoute(
                storageAccess = storageAccess,
                onOpen = openMedia,
                onResume = openMedia,
                onHistory = { navController.navigate(Dest.History) },
                onLikes = { navController.navigate(Dest.Likes) },
                onLists = { navController.navigate(Dest.Playlists) },
                onBrowser = {
                    navController.navigate("${Dest.Browser}/${LibraryCompat.builtinLocalId()}")
                },
                onAddLocalMedia = onAddLocalMedia,
                onAddOnlineMedia = onAddOnlineMedia,
                onImportOnlineMedia = onImportOnlineMedia,
                onScanMedia = { navController.navigate(Dest.Scan) },
                onEditMedia = onEditMedia,
                onDeleteMedia = onDeleteMedia,
                onTogglePin = onTogglePin
            )
        }
        composable(Dest.Sources) {
            val context = LocalContext.current
            SourcesScreen(
                onBrowser = { source ->
                    // 用路由参数携带 sourceId，不再依赖进程内单例：进程被回收后仍能重建该页面
                    navController.navigate("${Dest.Browser}/${Uri.encode(LibraryCompat.sourceId(source))}")
                },
                onPlayStream = { url, headers ->
                    LongVideoActivity.start(
                        context,
                        arrayListOf(
                            VideoData(
                                name = url.substringBefore("?"),
                                videoUrl = url,
                                type = StorageType.STREAM_LINK,
                                headers = headers.toMutableMap()
                            )
                        ),
                        0,
                        false
                    )
                }
            )
        }
        composable(Dest.Me) {
            MeScreen(
                themeMode = themeMode,
                brand = brand,
                dynamicColor = dynamicColor,
                version = BuildConfig.VERSION_NAME,
                onTheme = { navController.navigate(Dest.Theme) },
                onLibrary = { navController.navigate(Dest.SettingsLibrary) },
                onPlayback = { navController.navigate(Dest.SettingsPlayback) },
                onKernel = { navController.navigate(Dest.SettingsKernel) },
                onDanmaku = { navController.navigate(Dest.SettingsDanmaku) },
                onLab = { navController.navigate(Dest.SettingsLab) },
                onBackup = { navController.navigate(Dest.SettingsBackup) },
                onFeedback = { navController.navigate(Dest.About) },
                onMigration = { navController.navigate(Dest.Migration) }
            )
        }
        composable(Dest.SettingsPlayback) {
            PlaybackSettingsDetail(onBack = { navController.popBackStack() })
        }
        composable(Dest.SettingsLibrary) {
            LibrarySettingsDetail(onBack = { navController.popBackStack() })
        }
        composable(Dest.SettingsKernel) {
            KernelSettingsDetail(onBack = { navController.popBackStack() })
        }
        composable(Dest.SettingsDanmaku) {
            DanmakuSettingsDetail(onBack = { navController.popBackStack() })
        }
        composable(Dest.SettingsLab) {
            LabSettingsDetail(onBack = { navController.popBackStack() })
        }
        composable(Dest.SettingsBackup) {
            BackupsRoute(
                onBack = { navController.popBackStack() },
                onOpenMigration = { navController.navigate(Dest.Migration) }
            )
        }
        composable(Dest.Migration) {
            // 迁移完成只写 app_meta 标记，设置页的入口显隐由它自己的缓存决定，需要主动刷新一次
            val settingsVm = settingsViewModel()
            MigrationRoute(
                onBack = { navController.popBackStack() },
                onDone = {
                    settingsVm.refreshMigrationPending()
                    navController.popBackStack()
                }
            )
        }
        composable(Dest.About) {
            AboutRoute(onBack = { navController.popBackStack() })
        }
        composable(Dest.Icon) {
            IconSettingsRoute(onBack = { navController.popBackStack() })
        }
        composable(
            route = Dest.DetailWithMode,
            arguments = listOf(
                navArgument("mediaId") { type = NavType.StringType },
                navArgument("directPlay") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
            val directPlay = backStackEntry.arguments?.getBoolean("directPlay") == true
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            DetailRoute(
                mediaId = mediaId,
                directPlay = directPlay,
                onBack = { navController.popBackStack() },
                onPlay = { media, videos, index, isShort, seasonLabel ->
                    if (directPlay) navController.popBackStack()
                    startPlayback(context, media, videos, index, isShort, seasonLabel = seasonLabel)
                },
                onOpenDanmaku = { videos, targetMediaId ->
                    // 视频列表远超路由/Bundle 承载能力：先落临时文件，路由只带路径
                    scope.launch {
                        val tempPath = withContext(Dispatchers.IO) {
                            DanmakuBindingTransfer.writeTempVideos(context, videos)
                        }
                        navController.navigate(
                            "${Dest.Danmaku}/${Uri.encode(targetMediaId)}?temp=${Uri.encode(tempPath)}"
                        )
                    }
                }
            )
        }
        composable(Dest.History) {
            val context = LocalContext.current
            HistoryRoute(
                onBack = { navController.popBackStack() },
                onOpenDetail = { mediaId -> navController.navigate("${Dest.Detail}/${Uri.encode(mediaId)}") },
                onPlay = { videos, index, isShort ->
                    // 历史页是重播入口：不写回媒体库的「上次播放」
                    startPlayback(context, null, videos, index, isShort, history = true)
                }
            )
        }
        composable(Dest.Likes) {
            val context = LocalContext.current
            LikesRoute(
                onBack = { navController.popBackStack() },
                onPlay = { videos, index ->
                    startPlayback(context, null, videos, index, true, history = true)
                }
            )
        }
        composable(Dest.Playlists) {
            PlaylistRoute(
                onBack = { navController.popBackStack() },
                onOpenPlaylist = { playlistId ->
                    navController.navigate("${Dest.Detail}/${Uri.encode(playlistId)}")
                }
            )
        }
        composable(Dest.Theme) {
            // 个性化页要改「新首页 UI」开关，状态仍走设置页的同一个 ViewModel（Activity 作用域），
            // 这样「我的 → 个性化」与「我的 → 媒体库」读到的是同一份值
            val settingsVm = settingsViewModel()
            val settingsState by settingsVm.state.collectAsStateWithLifecycle()
            ThemeScreen(
                mode = themeMode,
                brand = brand,
                dynamic = dynamicColor,
                navLabels = navLabels,
                launchComposeHome = settingsState.library.launchComposeHome,
                onBack = { navController.popBackStack() },
                onMode = onThemeMode,
                onBrand = onBrand,
                onDynamic = onDynamicColor,
                onNavLabels = onNavLabels,
                onLaunchComposeHome = { on ->
                    val changed = on != settingsState.library.launchComposeHome
                    settingsVm.applyLibrary(settingsState.library.copy(launchComposeHome = on))
                    if (changed) onHomeUiChanged(on)
                },
                onIcon = { navController.navigate(Dest.Icon) }
            )
        }
        composable(Dest.ShortPlayer) { Placeholder("短视频播放") }
        composable(Dest.LongPlayer) { Placeholder("长视频播放") }
        composable(
            route = Dest.LongPlayerWithVideo,
            arguments = listOf(navArgument("videoId") { type = NavType.StringType })
        ) { entry ->
            // 具体分集：videoId 由详情页传入，播放页接入后在此取用，不再依赖调用方隐式状态
            val videoId = entry.arguments?.getString("videoId")
            Placeholder("长视频播放 ${videoId.orEmpty()}")
        }
        composable(
            route = Dest.BrowserWithSource,
            arguments = listOf(navArgument("sourceId") { type = NavType.StringType })
        ) { entry ->
            val sourceId = entry.arguments?.getString("sourceId").orEmpty()
            val context = LocalContext.current
            val vm: SourceByIdViewModel = viewModel()
            val lookup by vm.state.collectAsStateWithLifecycle()
            LaunchedEffect(sourceId) { vm.load(sourceId) }

            // 内置源（本地/串流）不落库：库内查不到时按 id 现场构造，仍构造不出来才算已删除
            val ready = lookup.source ?: if (!lookup.loading) BuiltinSources.byId(sourceId, context) else null
            when {
                ready != null -> BrowserScreen(
                    sourceData = ready,
                    onBack = { navController.popBackStack() }
                )
                lookup.loading -> Placeholder("加载中")
                else -> Placeholder("资源已删除")
            }
        }
        composable(Dest.Scan) {
            MediaScanRoute(
                onBack = { navController.popBackStack() }
            )
        }
        composable(Dest.ScanAuto) {
            MediaScanRoute(
                onBack = { navController.popBackStack() },
                autoStart = true
            )
        }
        composable(
            route = Dest.DanmakuWithMedia,
            arguments = listOf(
                navArgument("mediaId") { type = NavType.StringType },
                navArgument("temp") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { entry ->
            val targetMediaId = entry.arguments?.getString("mediaId").orEmpty()
            val tempPath = entry.arguments?.getString("temp").orEmpty()
            DanmakuBindingRoute(
                mediaId = targetMediaId,
                tempFilePath = tempPath,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
private fun Placeholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
/**
 * 起播统一入口。
 *
 * [media] 决定播放页能不能写回「上次播放」：两个播放页都只在 `!history` 时才接收 KEY_MEDIA，
 * 而写入 `play_last` / 历史 / 进度都依赖它，所以媒体库进入必须带上下文、且 `history = false`。
 * 历史页本身是重播入口（`history = true`，不带上下文），避免把历史页当成媒体库再写一次。
 */
private fun startPlayback(
    context: Context,
    media: MediaData?,
    list: List<VideoData>,
    index: Int,
    isShort: Boolean,
    seasonLabel: String = "",
    history: Boolean = media?.isHistory() == true
) {
    if (list.isEmpty()) return
    val arrayList = ArrayList(list)
    // 多季剧集：把当前季名透传给播放页，季末在 onNextPlay 的边界分支给出提示。
    // 剧集根库没有季目录 path，季名由详情页按当前选中的子目录传入；
    // 一季一库仍可从 media.path 推导。历史 / 喜欢 / 播放列表入口季名为空，行为不变。
    val season = seasonLabel.ifBlank {
        media?.path?.let { SeriesKey.seasonLabelOf(it) }.orEmpty()
    }
    if (isShort) {
        ShortVideoActivity.start(context, media, arrayList, index, history)
    } else {
        LongVideoActivity.start(context, media, arrayList, index, history, season)
    }
}
