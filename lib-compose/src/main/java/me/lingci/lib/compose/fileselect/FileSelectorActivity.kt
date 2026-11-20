package me.lingci.lib.compose.fileselect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.SpBase

/**
 * Compose 版文件选择器，独立于 lib-base 的旧页面，供各 app 模块复用。
 *
 * 对外 API（启动方法 / 返回 Key）与旧版保持一致：调用方只需替换 import 即可迁移。
 */
class FileSelectorActivity : ComponentActivity() {

    companion object {
        const val KEY_PATH = "path"
        const val KEY_FOLDER = "folder"

        private const val KEY_MULTI_MODE = "multiMode"
        private const val KEY_FILE_TYPE = "fileType"
        private const val KEY_FOLDER_MODE = "folderMode"
        private const val KEY_FOLDER_MULTI_MODE = "folderMultiMode"

        fun Intent.selectorFile(): List<String> =
            getStringArrayListExtra(KEY_PATH) ?: emptyList()

        fun Intent.selectorFile(onSelect: (paths: List<String>, folderMode: Boolean) -> Unit) {
            onSelect(selectorFile(), getBooleanExtra(KEY_FOLDER, false))
        }

        fun start(context: Activity, launcher: ActivityResultLauncher<Intent>) {
            launcher.launch(
                Intent(context, FileSelectorActivity::class.java).apply {
                    putExtra(KEY_MULTI_MODE, true)
                    putExtra(KEY_FOLDER_MODE, false)
                }
            )
        }

        fun start(
            context: Activity,
            fileType: ArrayList<String>,
            launcher: ActivityResultLauncher<Intent>,
        ) {
            launcher.launch(
                Intent(context, FileSelectorActivity::class.java).apply {
                    putExtra(KEY_MULTI_MODE, true)
                    putExtra(KEY_FOLDER_MODE, false)
                    putStringArrayListExtra(KEY_FILE_TYPE, fileType)
                }
            )
        }

        fun startSingle(context: Activity, launcher: ActivityResultLauncher<Intent>) {
            launcher.launch(
                Intent(context, FileSelectorActivity::class.java).apply {
                    putExtra(KEY_MULTI_MODE, false)
                    putExtra(KEY_FOLDER_MODE, false)
                }
            )
        }

        fun startSingle(
            context: Activity,
            fileType: ArrayList<String>,
            launcher: ActivityResultLauncher<Intent>,
        ) {
            launcher.launch(
                Intent(context, FileSelectorActivity::class.java).apply {
                    putExtra(KEY_MULTI_MODE, false)
                    putExtra(KEY_FOLDER_MODE, false)
                    putStringArrayListExtra(KEY_FILE_TYPE, fileType)
                }
            )
        }

        fun startFolder(context: Activity, launcher: ActivityResultLauncher<Intent>) {
            launcher.launch(
                Intent(context, FileSelectorActivity::class.java).apply {
                    putExtra(KEY_MULTI_MODE, false)
                    putExtra(KEY_FOLDER_MODE, true)
                }
            )
        }

        /** 多目录勾选：可勾选多个目录，返回勾选列表。 */
        fun startFolders(context: Activity, launcher: ActivityResultLauncher<Intent>) {
            launcher.launch(
                Intent(context, FileSelectorActivity::class.java).apply {
                    putExtra(KEY_MULTI_MODE, true)
                    putExtra(KEY_FOLDER_MODE, true)
                    putExtra(KEY_FOLDER_MULTI_MODE, true)
                }
            )
        }
    }

    private class SpFavorites(context: Context) : FavoriteFoldersStore {
        private val sp = SpBase(context.applicationContext)

        override fun load(): List<String> =
            sp.useFolders?.let { JsonUtil.toList<String>(it) } ?: emptyList()

        override fun save(paths: List<String>) {
            sp.useFolders = JsonUtil.toJsonString(paths)
        }
    }

    /** 由 Route 注入，统一处理系统返回键与顶部返回按钮：逐级回退，到 root 后关闭。 */
    private var onBackAction: (() -> Unit)? = null

    // 固定字体缩放：列表行高是固定值，系统放大字体会撑破布局。与旧版行为一致。
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

        val multiMode = intent.getBooleanExtra(KEY_MULTI_MODE, true)
        val folderMode = intent.getBooleanExtra(KEY_FOLDER_MODE, false)
        val folderMultiMode = intent.getBooleanExtra(KEY_FOLDER_MULTI_MODE, false)
        val extensions = intent.getStringArrayListExtra(KEY_FILE_TYPE)
            ?.toList() ?: FileOperator.XML_EXTENSIONS.toList()
        val mode = when {
            folderMode && folderMultiMode -> PickerMode.MultiFolder
            folderMode -> PickerMode.SingleFolder
            multiMode -> PickerMode.MultiFile
            else -> PickerMode.SingleFile
        }
        val store = SpFavorites(this)

        setContent {
            MaterialTheme {
                val vm: FilePickerViewModel = viewModel(
                    factory = remember {
                        viewModelFactory {
                            initializer { FilePickerViewModel(mode, extensions, store) }
                        }
                    }
                )
                val back: () -> Unit = {
                    if (!vm.onBack()) finish()
                }
                onBackAction = back
                FilePickerRoute(
                    viewModel = vm,
                    onFinish = { paths, folder -> finishWith(paths, folder) },
                    onCancel = { finish() },
                    onBack = back,
                )
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                onBackAction?.invoke() ?: finish()
            }
        })
    }

    private fun finishWith(paths: List<String>, folderMode: Boolean) {
        val result = Intent().apply {
            putStringArrayListExtra(KEY_PATH, ArrayList(paths))
            putExtra(KEY_FOLDER, folderMode)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
