package me.lingci.dy.player.ui.main

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lingci.dy.player.entity.VersionData
import me.lingci.dy.player.util.AppUtil
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.okhttp.httpGet
import me.lingci.lib.base.util.CodeUtil
import me.lingci.lib.base.util.JsonUtil
import me.lingci.lib.base.util.Log

fun checkAppUpdateOnLaunch(
    activity: FragmentActivity,
    onDownload: (VersionData) -> Unit
) {
    val sp = SpUtil(activity)
    val today = AppUtil.today()
    if (sp.dayStr.orEmpty().startsWith(today)) return
    activity.lifecycleScope.launch {
        httpGet("https://gitee.com/happycao/static-file/raw/api/app/api/dy_like_version.json")
            .execute()
            .onSuccess { response ->
                val versionData = JsonUtil.toEntityCbc<VersionData>(response)
                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext
                    val versionCode = CodeUtil.versionCode(activity)
                    if (versionData.type == 0 && versionCode < versionData.versionCode && sp.isFirst) {
                        sp.isFirst = false
                        VersionDialog.newInstance(versionData, null)
                            .show(activity.supportFragmentManager, "app-version")
                    }
                    if (versionCode < versionData.versionCode) {
                        val dayStr = sp.dayStr.orEmpty().split("#")
                        val alreadyHandled = dayStr.size > 1 &&
                            (dayStr[0] == today ||
                                (versionData.type == 0 && dayStr[1] == "${versionData.versionCode}"))
                        if (!alreadyHandled) {
                            val dialog = VersionDialog.newInstance(versionData) {
                                onDownload(versionData)
                            }
                            dialog.show(activity.supportFragmentManager, dialog.tag)
                        }
                    }
                    sp.dayStr = "$today#${versionData.versionCode}"
                }
            }.onFailure { e ->
                sp.dayStr = "$today#"
                Log.d("AppUpdateOnLaunch", "check update failed: ${e.message}", e)
            }
    }
}
