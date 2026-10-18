package me.lingci.dy.player

import android.app.Application
import android.content.Context
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.lingci.dy.player.cache.ProgressManagerImpl
import me.lingci.dy.player.migrate.MigrationChecker
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.crash.AppExceptionHandler
import me.lingci.lib.base.util.Log
import me.lingci.lib.player.exo.CustomExoMediaPlayerFactory
import xyz.doikki.videoplayer.player.VideoViewConfig
import xyz.doikki.videoplayer.player.VideoViewManager

/**
 * @author : happyc
 * time    : 2020/06/28
 * desc    :
 * version : 1.0
 */
internal class PlayerApp : Application() {

    companion object {

        private const val TAG = "PlayerApp"

        private var appContext: Context? = null

        fun getAppContext(): Context {
            return appContext!!
        }

        // 应用级协程作用域：与进程同生命周期，用于启动期后台任务
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 启动迁移检查结果（§5.1）：MainActivity 启动时等待此结果决定是否跳迁移页
        private val migrationNeeded = CompletableDeferred<Boolean>()

        /** 等待启动迁移检查完成；返回 true 表示存在旧版数据待迁移 */
        suspend fun awaitMigrationNeeded(): Boolean = migrationNeeded.await()

    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        initDkPlayer()
        initCrash()
        checkMigration()
    }

    /** 启动时检查 SP → Room 迁移状态：全新安装直接落 app_meta 标记，旧数据通知 MainActivity 跳迁移页 */
    private fun checkMigration() {
        appScope.launch {
            try {
                val needed = MigrationChecker.check(SpUtil(this@PlayerApp))
                migrationNeeded.complete(needed)
            } catch (e: Exception) {
                // 检查失败按"无需迁移"处理，避免卡死启动流程（实验室入口仍可手动触发）
                Log.d(TAG, "checkMigration failed: ${e.message}", e)
                migrationNeeded.complete(false)
            }
        }
    }

    private fun initDkPlayer() {
        VideoViewManager.setConfig(
            VideoViewConfig.newBuilder()
                // Global default is only a safe fallback for views without explicit core injection.
                // dy-player playback screens override each CustomVideoView via DyPlayerCoreRegistry.
                .setPlayerFactory(CustomExoMediaPlayerFactory.create())
                //.setRenderViewFactory(SurfaceRenderViewFactory.create())
                .setProgressManager(ProgressManagerImpl())
                .setLogEnabled(BuildConfig.DEBUG)
                .build()
        )
    }

    private fun initCrash() {
        val buglyAppId = BuildConfig.BUGLY_APP_ID
        if (buglyAppId.isNotEmpty()) {
            CrashReport.initCrashReport(applicationContext, buglyAppId, false)
        }
        Thread.setDefaultUncaughtExceptionHandler(AppExceptionHandler(this))
    }

}
