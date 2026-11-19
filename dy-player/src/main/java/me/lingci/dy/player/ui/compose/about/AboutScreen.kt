package me.lingci.dy.player.ui.compose.about

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import me.lingci.dy.player.BuildConfig
import me.lingci.dy.player.R
import me.lingci.dy.player.ui.compose.components.AppIconButton
import me.lingci.dy.player.ui.compose.components.AppIconImage
import me.lingci.dy.player.ui.compose.components.GroupLabel
import me.lingci.dy.player.ui.compose.components.Kicker
import me.lingci.dy.player.ui.compose.settings.SettingsNoteLine
import me.lingci.dy.player.ui.compose.theme.BrandHue
import me.lingci.dy.player.ui.compose.theme.DyLikeTheme
import me.lingci.dy.player.ui.compose.theme.DyLikeType
import me.lingci.dy.player.ui.compose.theme.LocalDyLikeColors
import me.lingci.dy.player.ui.compose.theme.ThemeMode
import me.lingci.lib.base.util.ToastUtil

/** QQ 群 key（由官网生成），与旧版关于页保持一致 */
private const val QQ_GROUP_KEY = "QB6v-ccuzZgQe2jErJwcjEw_OXqfI-vf"

/**
 * 路由入口：只有外部动作用到这里（拉起手Q、占位提示），页面本身保持无状态。
 *
 * 「服务协议」在旧版里会弹一个显示信息（HDR/亮度）的弹窗，属于误接：
 * 这里只保留占位提示，不再顺手弹无关弹窗。
 */
@Composable
fun AboutRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val msgPending = stringResource(R.string.about_pending)
    val msgQqMissing = stringResource(R.string.about_qq_missing)

    AboutScreen(
        version = BuildConfig.VERSION_NAME,
        onBack = onBack,
        onJoinGroup = {
            val intent = Intent().setData(
                ("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr" +
                        "%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$QQ_GROUP_KEY").toUri()
            )
            // 未安装手Q或版本不支持时 startActivity 会抛 ActivityNotFoundException
            runCatching { context.startActivity(intent) }
                .onFailure { ToastUtil.showToast(context, msgQqMissing) }
        },
        onPending = { ToastUtil.showToast(context, msgPending) }
    )
}

@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit = {},
    onJoinGroup: () -> Unit = {},
    onPending: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val c = LocalDyLikeColors.current

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
            Column(Modifier.padding(start = 6.dp)) {
                Kicker(stringResource(R.string.about_kicker))
                Text(
                    stringResource(R.string.title_about),
                    style = MaterialTheme.typography.headlineSmall,
                    color = c.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            BrandBlock(version)
            GroupLabel(stringResource(R.string.about_section_info))
            InfoRow(
                title = stringResource(R.string.about_group_feedback),
                value = stringResource(R.string.about_group_number),
                highlight = true,
                onClick = onJoinGroup
            )
            HorizontalDivider(color = c.hairline, modifier = Modifier.padding(horizontal = 20.dp))
            InfoRow(
                title = stringResource(R.string.about_license),
                value = stringResource(R.string.about_license_none),
                onClick = onPending
            )
            HorizontalDivider(color = c.hairline, modifier = Modifier.padding(horizontal = 20.dp))
            InfoRow(
                title = stringResource(R.string.about_app_record),
                value = stringResource(R.string.about_app_record_none),
                onClick = onPending
            )
            Spacer(Modifier.height(20.dp))
            SettingsNoteLine(stringResource(R.string.settings_about_feedback_desc))
        }

        // 协议与版权贴在页面底部：它们不承载功能，只是法定信息
        Text(
            stringResource(R.string.about_protocol),
            style = DyLikeType.meta,
            color = c.accent,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .clickable { onPending() }
                .padding(vertical = 10.dp)
        )
        Text(
            stringResource(R.string.about_copyright),
            style = DyLikeType.micro,
            color = c.textTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
        )
    }
}

@Composable
private fun BrandBlock(version: String) {
    val c = LocalDyLikeColors.current
    Column(
        Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AppIconImage(
            resId = R.mipmap.ic_launcher,
            modifier = Modifier.size(72.dp)
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = c.textPrimary,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            stringResource(R.string.about_version, version),
            style = DyLikeType.meta,
            color = c.textTertiary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** 关于页的行：左侧说明、右侧取值，没有箭头——这些不是可下钻的页面入口 */
@Composable
private fun InfoRow(
    title: String,
    value: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val c = LocalDyLikeColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (highlight) c.accent else c.textSecondary
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101014, heightDp = 620)
@Composable
private fun AboutPreview() {
    DyLikeTheme(ThemeMode.Dark, BrandHue.Purple, false) {
        AboutScreen(version = "0.2.4-d")
    }
}
