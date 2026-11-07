package me.lingci.lib.base.util

import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 *   @author : happyc
 *   time    : 2025/02/22
 *   desc    :
 *   version : 1.0
 */
object PermissionUtil {

    private const val PERMISSION_REQUEST_CODE = 101

    val storagePermissions = arrayOf(
        android.Manifest.permission.READ_EXTERNAL_STORAGE,
        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    fun requestStoragePermission(activity: Activity, callback: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(
                activity,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        } else {
            callback(true)
        }
    }

    fun handlePermissionResult(
        requestCode: Int,
        grantResults: IntArray,
        callback: (Boolean) -> Unit
    ) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            callback(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
        }
    }

    /**
     * 检查所有权限是否已被授予
     */
    fun checkPermissions(context: Context, permissions: Array<String>): Boolean {
        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 存储权限是否真正可用。
     *
     * `pm grant`（以及部分 MIUI 版本）只改运行时权限，AppOps 仍可能是 ignore：
     * `checkSelfPermission` 返回已授予，但 `File.listFiles()` 返回 null，界面表现为空目录。
     * 因此这里额外看 READ_EXTERNAL_STORAGE 的 AppOps 状态。
     */
    fun hasStorageAccess(context: Context): Boolean {
        if (!checkPermissions(context, storagePermissions)) return false
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return true
        // OEM 的 AppOps 实现差异较大，查询失败时退回运行时权限结论
        return runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode != AppOpsManager.MODE_IGNORED && mode != AppOpsManager.MODE_ERRORED
        }.getOrDefault(true)
    }

    /**
     * 申请权限
     */
    fun requestPermissions(activity: android.app.Activity, permissions: Array<String>, requestCode: Int) {
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    /**
     * 检查并申请权限
     * @param activity Activity实例
     * @param permissions 要申请的权限数组
     * @param requestCode 请求码
     * @param onGranted 权限全部被授予时的回调
     * @param onDenied 有权限被拒绝时的回调
     */
    fun checkAndRequestPermissions(
        activity: Activity,
        permissions: Array<String>,
        requestCode: Int,
        onGranted: () -> Unit,
        onDenied: () -> Unit = {}
    ) {
        if (checkPermissions(activity, permissions)) {
            onGranted()
        } else {
            requestPermissions(activity, permissions, requestCode)
            onDenied()
        }
    }

}