package me.lingci.lib.base.util

import me.lingci.lib.base.json.JSONArray
import me.lingci.lib.base.json.JSONObject
import me.lingci.lib.base.json.JsonValueConverter

/**
 *   @author : happyc
 *   time    : 2024/10/16
 *   desc    : 任意对象转 JSON（仅面向日志等调试场景）。
 *             统一委托给 [JsonValueConverter]，不再依赖 org.json。
 *   version : 2.0
 */
internal object AnyToJson {

    fun toString(obj: Any?): String {
        return when (obj) {
            null -> ""
            is Number -> "$obj"
            is String -> obj
            is Boolean -> "$obj"
            is JSONObject -> obj.toJsonStr()
            is JSONArray -> obj.toString()
            else -> try {
                JsonValueConverter.toElement(obj).toString()
            } catch (e: Exception) {
                Log.d(this, "toString failed", e)
                obj.toString()
            }
        }
    }
}
