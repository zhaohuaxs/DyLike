package me.lingci.dy.player.ui.compose.icon

import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.util.Log

/** 桌面图标两套别名（Manifest 里的 activity-alias） */
enum class LauncherIcon(val aliasClass: String) {
    Default("me.lingci.dy.player.DefaultAlias"),
    Line("me.lingci.dy.player.LineAlias")
}

@Immutable
data class IconSettingsUiState(
    val selected: LauncherIcon,
    val busy: Boolean = false
)

sealed interface IconSettingsEvent {
    data object Switched : IconSettingsEvent
    data object Failed : IconSettingsEvent
}

/**
 * 桌面图标切换。
 *
 * 别名只影响桌面入口（activity-alias 的 enabled 状态），不动运行中的进程，
 * 所以切换后不需要重启应用，页面自行返回即可。
 *
 * 这里必须用 Application 而非 Activity：包管理器操作与 SP 读写都不需要窗口上下文。
 */
class IconSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val sp: SpUtil by lazy { SpUtil(getApplication()) }

    private val _state = MutableStateFlow(
        IconSettingsUiState(selected = if (sp.iconDefault) LauncherIcon.Default else LauncherIcon.Line)
    )
    val state: StateFlow<IconSettingsUiState> = _state.asStateFlow()

    private val _events = Channel<IconSettingsEvent>(Channel.BUFFERED)
    val events: Flow<IconSettingsEvent> = _events.receiveAsFlow()

    fun select(icon: LauncherIcon) {
        val current = _state.value
        if (current.busy || current.selected == icon) return
        _state.value = current.copy(busy = true)
        viewModelScope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) { applyAlias(icon) }
                true
            }.getOrElse { error ->
                Log.d(this@IconSettingsViewModel, "switch launcher icon failed", error)
                false
            }
            if (success) {
                // 先写偏好再更新状态：偏好在别处（备份/还原）也会读写，保持一致
                sp.iconDefault = icon == LauncherIcon.Default
                _state.value = IconSettingsUiState(selected = icon)
                _events.send(IconSettingsEvent.Switched)
            } else {
                _state.value = current.copy(busy = false)
                _events.send(IconSettingsEvent.Failed)
            }
        }
    }

    /**
     * 先禁用两个别名再启用目标别名：不这么做会出现两个桌面入口同时可见。
     *
     * 别名类名按 namespace 解析（固定为 me.lingci.dy.player.*），
     * 不能用带 applicationIdSuffix 的 packageName 拼接。
     */
    private fun applyAlias(icon: LauncherIcon) {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val aliases = LauncherIcon.entries.map { ComponentName(context, it.aliasClass) }
        aliases.forEach {
            pm.setComponentEnabledSetting(
                it,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
        pm.setComponentEnabledSetting(
            ComponentName(context, icon.aliasClass),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
