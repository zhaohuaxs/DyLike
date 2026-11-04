package me.lingci.dy.player.ui.compose.shell

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.dy.player.util.SpUtil

/**
 * 外观偏好：主题模式 / 品牌色 / 动态取色 / 底栏文字。
 *
 * 这几项同时被外壳（`MainComposeActivity` 的 `DyLikeTheme`）与外观设置页读取，
 * 以前在外壳里用 `remember { mutableStateOf(sp.xxx) }` 各持一份镜像，回到设置页改了
 * 主题，图标与底栏要等下次重建才跟着变。这里做成单一状态源，写 SP 与回灌一起发生。
 */
@Immutable
data class AppearanceState(
    val mode: ThemeMode = ThemeMode.System,
    val brand: BrandHue = BrandHue.Purple,
    val dynamicColor: Boolean = false,
    val navLabels: Boolean = false
)

class AppearanceViewModel(app: Application) : AndroidViewModel(app) {

    private val sp = SpUtil(app)

    private val _state = MutableStateFlow(
        AppearanceState(
            mode = ThemeMode.entries.getOrElse(sp.themeMode) { ThemeMode.System },
            brand = BrandHue.entries.getOrElse(sp.brandHue) { BrandHue.Purple },
            dynamicColor = sp.dynamicColor,
            navLabels = sp.navLabels
        )
    )
    val state: StateFlow<AppearanceState> = _state.asStateFlow()

    fun setMode(mode: ThemeMode) {
        sp.themeMode = mode.ordinal
        _state.update { it.copy(mode = mode) }
    }

    fun setBrand(brand: BrandHue) {
        sp.brandHue = brand.ordinal
        _state.update { it.copy(brand = brand) }
    }

    fun setDynamicColor(enabled: Boolean) {
        sp.dynamicColor = enabled
        _state.update { it.copy(dynamicColor = enabled) }
    }

    fun setNavLabels(enabled: Boolean) {
        sp.navLabels = enabled
        _state.update { it.copy(navLabels = enabled) }
    }
}
