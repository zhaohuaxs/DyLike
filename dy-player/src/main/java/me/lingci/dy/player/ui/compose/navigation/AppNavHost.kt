package me.lingci.dy.player.ui.compose.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import me.lingci.dy.player.ui.compose.model.MediaUiModel
import me.lingci.dy.player.entity.SourceData
import me.lingci.dy.player.entity.VideoData
import me.lingci.dy.player.migrate.MigrationActivity
import me.lingci.dy.player.ui.about.AboutActivity
import me.lingci.dy.player.ui.compose.browser.BrowserScreen
import me.lingci.dy.player.ui.compose.detail.DetailRoute
import me.lingci.dy.player.ui.compose.history.HistoryRoute
import me.lingci.dy.player.ui.compose.likes.LikesRoute
import me.lingci.dy.player.ui.compose.library.LibraryRoute
import me.lingci.dy.player.ui.compose.playlist.PlaylistRoute
import me.lingci.dy.player.ui.compose.me.MeScreen
import me.lingci.dy.player.ui.compose.scan.MediaScanRoute
import me.lingci.dy.player.ui.compose.settings.DanmakuSettingsDetail
import me.lingci.dy.player.ui.compose.settings.KernelSettingsDetail
import me.lingci.dy.player.ui.compose.settings.LabSettingsDetail
import me.lingci.dy.player.ui.compose.settings.PlaybackSettingsDetail
import me.lingci.dy.player.ui.compose.sources.SourceByIdViewModel
import me.lingci.dy.player.ui.compose.sources.SourcesScreen
import me.lingci.dy.player.ui.icon.IconSettingActivity
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.ui.short_video.ShortVideoActivity
import me.lingci.dy.player.ui.tool.ToolActivity
import me.lingci.dy.player.BuildConfig
import me.lingci.lib.base.storage.entity.StorageType
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.ui.compose.theme_screen.ThemeScreen
import me.lingci.dy.player.util.LibraryCompat

object Dest {
    const val Library = "library"
    const val Sources = "sources"
    const val Me = "me"
    const val Detail = "detail"
    const val History = "history"
    const val Likes = "likes"
    const val Playlists = "playlists"
    const val Theme = "theme"
    /** 设置分类页：「我的」页直接列设置入口，这里只是分类详情，没有中间的「全部设置」索引层 */
    const val SettingsPlayback = "settings/playback"
    const val SettingsKernel = "settings/kernel"
    const val SettingsDanmaku = "settings/danmaku"
    const val SettingsLab = "settings/lab"
    const val ShortPlayer = "shortPlayer"
    const val LongPlayer = "longPlayer"
    const val LongPlayerWithVideo = "longPlayer/{videoId}"
    const val Browser = "browser"
    const val BrowserWithSource = "browser/{sourceId}"
    const val Scan = "scan"
    const val Danmaku = "danmaku"
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
    onThemeMode: (ThemeMode) -> Unit = {},
    onBrand: (BrandHue) -> Unit = {},
    onDynamicColor: (Boolean) -> Unit = {},
    onNavLabels: (Boolean) -> Unit = {},
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
            LibraryRoute(
                onOpen = { mediaId -> navController.navigate("${Dest.Detail}/${Uri.encode(mediaId)}") },
                onResume = { mediaId -> navController.navigate("${Dest.Detail}/${Uri.encode(mediaId)}") },
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
            val context = LocalContext.current
            MeScreen(
                themeMode = themeMode,
                brand = brand,
                dynamicColor = dynamicColor,
                version = BuildConfig.VERSION_NAME,
                onTheme = { navController.navigate(Dest.Theme) },
                onPlayback = { navController.navigate(Dest.SettingsPlayback) },
                onKernel = { navController.navigate(Dest.SettingsKernel) },
                onDanmaku = { navController.navigate(Dest.SettingsDanmaku) },
                onLab = { navController.navigate(Dest.SettingsLab) },
                // 备份还原仍在旧 ToolActivity 里，页面迁移完成前先桥接过去
                onBackup = { ToolActivity.start(context, ToolActivity.PAGE_BACKUP) },
                onFeedback = { context.startActivity(Intent(context, AboutActivity::class.java)) },
                onMigration = {
                    context.startActivity(Intent(context, MigrationActivity::class.java))
                }
            )
        }
        composable(Dest.SettingsPlayback) {
            PlaybackSettingsDetail(onBack = { navController.popBackStack() })
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
        composable(
            route = "detail/{mediaId}",
            arguments = listOf(navArgument("mediaId") { type = NavType.StringType })
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getString("mediaId") ?: ""
            val context = LocalContext.current
            DetailRoute(
                mediaId = mediaId,
                onBack = { navController.popBackStack() },
                onPlay = { videos, index, isShort ->
                    startPlayback(context, videos, index, isShort)
                }
            )
        }
        composable(Dest.History) {
            val context = LocalContext.current
            HistoryRoute(
                onBack = { navController.popBackStack() },
                onOpenDetail = { mediaId -> navController.navigate("${Dest.Detail}/${Uri.encode(mediaId)}") },
                onPlay = { videos, index, isShort ->
                    startPlayback(context, videos, index, isShort)
                }
            )
        }
        composable(Dest.Likes) {
            val context = LocalContext.current
            LikesRoute(
                onBack = { navController.popBackStack() },
                onPlay = { videos, index ->
                    startPlayback(context, videos, index, true)
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
            val context = LocalContext.current
            ThemeScreen(
                mode = themeMode,
                brand = brand,
                dynamic = dynamicColor,
                navLabels = navLabels,
                onBack = { navController.popBackStack() },
                onMode = onThemeMode,
                onBrand = onBrand,
                onDynamic = onDynamicColor,
                onNavLabels = onNavLabels,
                onIcon = { context.startActivity(Intent(context, IconSettingActivity::class.java)) }
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

            // 内置源（本地/外部/串流）不落库：库内查不到时按 id 现场构造，仍构造不出来才算已删除
            val ready = lookup.source ?: if (!lookup.loading) builtinSourceById(sourceId, context) else null
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
        composable(Dest.Danmaku) { Placeholder("弹幕绑定") }
    }
}

@Composable
private fun Placeholder(label: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.headlineSmall)
    }
}
private fun startPlayback(context: android.content.Context, list: List<VideoData>, index: Int, isShort: Boolean) {
    if (list.isEmpty()) return
    val arrayList = ArrayList(list)
    if (isShort) {
        ShortVideoActivity.start(context, arrayList, index, true)
    } else {
        LongVideoActivity.start(context, arrayList, index, true)
    }
}

private fun builtinSourceById(sourceId: String, context: android.content.Context): SourceData? {
    return when (sourceId) {
        LibraryCompat.builtinLocalId() -> SourceData().apply {
            id = LibraryCompat.builtinLocalId()
            type = StorageType.LOCAL_STORAGE
            title = "本地存储"
            siteUrl = "/storage/emulated/0/"
            schema = "/storage/emulated/0/"
        }
        LibraryCompat.builtinExternalId() -> {
            val dirs = context.getExternalFilesDirs(null)
            if (dirs != null && dirs.size > 1) {
                try {
                    val path = dirs[1].path.split("Android/data")[0]
                    SourceData().apply {
                        id = LibraryCompat.builtinExternalId()
                        type = StorageType.LOCAL_STORAGE
                        title = "外部存储"
                        siteUrl = path
                        schema = path
                    }
                } catch (e: Exception) {
                    null
                }
            } else null
        }
        else -> null
    }
}