package me.lingci.dy.player.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.R
import me.lingci.dy.player.databinding.ActivityMainBinding
import me.lingci.dy.player.entity.VersionData
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.AutoPlayOnLaunch
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.okhttp.httpGet
import me.lingci.lib.base.ui.BaseDisplayActivity
import me.lingci.lib.base.util.CodeUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log
import java.lang.reflect.Method

/**
 * 主页
 */
class MainActivity : BaseDisplayActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val spUtil by lazy { SpUtil(this) }
    private lateinit var binding: ActivityMainBinding
    private lateinit var resultLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        init()
        // 启动自动播放：仅在冷启动（无 savedInstanceState）时触发一次，
        // 避免旋转屏幕、从后台恢复或从播放页返回时重复触发
        if (savedInstanceState == null) {
            AutoPlayOnLaunch.tryTrigger(this, lifecycleScope)
        }
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        // 使更多菜单带图标
        if (menu.javaClass.simpleName.equals("MenuBuilder", ignoreCase = true)) {
            try {
                val method: Method = menu.javaClass.getDeclaredMethod(
                    "setOptionalIconsVisible",
                    java.lang.Boolean.TYPE
                )
                method.isAccessible = true
                method.invoke(menu, true)
            } catch (_: Exception) {

            }
        }
        return super.onMenuOpened(featureId, menu)
    }

    private fun init() {
        initView()
        initBackPressed()
        initResult()
        initUpdate()
    }

    private fun initView() {
        setSupportActionBar(binding.toolbar)
        initNav()
    }

    private fun initNav() {
        val navView: BottomNavigationView = binding.navView
        val navController = binding.navHostFragmentActivityMain
            .getFragment<NavHostFragment>()
            .navController
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_media, R.id.navigation_source, R.id.navigation_tool
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    private fun initBackPressed() {
        onBackPressedDispatcher.addCallback(this) {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // 确保意图能被解析
                    if (resolveActivity(packageManager) == null) {
                        // 如果没有找到能处理的组件，做降级处理
                        finish()
                        return@apply
                    }
                }
                startActivity(homeIntent)
            } catch (e: Exception) {
                Log.d(this, "go home failed ${e.message}")
                finish()
            }
        }
    }

    private fun initResult() {
        resultLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (it.resultCode == RESULT_OK) {
                Log.d(TAG, "initResult: ${it.data}")
                return@registerForActivityResult
            } else {
                Log.d(TAG, "initResult: ${it.resultCode}")
            }
        }
    }

    private fun initUpdate() {
        val today = AppUtil.today()
        if (spUtil.dayStr!!.startsWith(today)) {
            return
        }
        lifecycleScope.launch {
            httpGet("https://gitee.com/happycao/static-file/raw/api/app/api/dy_like_version.json")
                .execute()
                .onSuccess { response ->
                    val versionData = JsonUtil.toEntityCbc<VersionData>(response)
                    withContext(Dispatchers.Main) {
                        val versionCode = CodeUtil.versionCode(this@MainActivity)
                        if (versionData.type == 0 && versionCode < versionData.versionCode && spUtil.isFirst) {
                            spUtil.isFirst = false
                            val versionDialog = VersionDialog.newInstance(versionData, null)
                            versionDialog.show(supportFragmentManager, "app-version")
                        }
                        if (versionCode < versionData.versionCode) {
                            val dayStr = spUtil.dayStr!!.split("#")
                            if (dayStr.size > 1) {
                                if (dayStr[0] == today) {
                                    return@withContext
                                }
                                if (versionData.type == 0 && dayStr[1] == "${versionData.versionCode}") {
                                    return@withContext
                                }
                            }
                            val versionDialog = VersionDialog.newInstance(versionData) {
                                resultLauncher.launch(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        versionData.downUrl.toUri()
                                    )
                                )
                            }
                            versionDialog.show(supportFragmentManager, versionDialog.tag)
                        }
                        spUtil.dayStr = "$today#${versionData.versionCode}"
                    }
                }.onFailure { e ->
                    spUtil.dayStr = "$today#"
                    Log.d(TAG, "initUpdate: ${e.message}", e)
                }
        }
    }

    private fun modeNight() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY)
    }

    private fun changeModeNight() {
        delegate.localNightMode = if (delegate.localNightMode == AppCompatDelegate.MODE_NIGHT_YES) {
            AppCompatDelegate.MODE_NIGHT_NO
        } else {
            AppCompatDelegate.MODE_NIGHT_YES
        }
        recreate()
    }

}
