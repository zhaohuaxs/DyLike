package me.lingci.dy.player.ui.compose.components

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import me.lingci.lib.base.util.PermissionUtil

/**
 * 返回一个「申请存储权限」的动作。
 *
 * 授予后回调 [onGranted]；用户勾选「不再询问」后系统不会再弹窗，只能跳应用设置，
 * 因此这里在永久拒绝时直接打开设置页。
 */
@Composable
fun rememberStoragePermissionRequest(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it }) {
            if (PermissionUtil.hasStorageAccess(context)) onGranted() else openAppSettings(context)
        } else if (activity != null &&
            PermissionUtil.storagePermissions.none { activity.shouldShowRequestPermissionRationale(it) }
        ) {
            openAppSettings(context)
        }
    }
    return {
        // 运行时权限已授予但 AppOps 被拦（典型是 adb grant）：系统不会再弹授权框，只能去设置里重开
        if (PermissionUtil.checkPermissions(context, PermissionUtil.storagePermissions) &&
            !PermissionUtil.hasStorageAccess(context)
        ) {
            openAppSettings(context)
        } else {
            launcher.launch(PermissionUtil.storagePermissions)
        }
    }
}

private fun openAppSettings(context: android.content.Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        )
    )
}
