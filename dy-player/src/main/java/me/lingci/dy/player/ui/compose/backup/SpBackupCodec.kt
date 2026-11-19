package me.lingci.dy.player.ui.compose.backup

import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.json.JSONObject
import me.lingci.lib.base.util.Log
import java.util.LinkedHashSet

/**
 * SharedPreferences 整体备份编解码。
 *
 * 逐 key 列举（一个字段在导出、导入各维护一次）是漏字段的根源：`SpUtil`/`SpBase` 里
 * 每加一个偏好都得记得回来补两处，迟早对不上。这里改成「整份 SP + 类型标注」：
 *
 * - 导出：`preferences.all` 一次取全，每个值按实际类型写成 `{"t": 类型, "v": 值}`
 * - 导入：按 `t` 分发到对应的 `putBoolean/putInt/putLong/putFloat/putString/putStringSet`，
 *   最后一次性提交
 *
 * 新增偏好字段不需要改这里，也不需要在备份页改任何一行。
 *
 * 为什么必须带类型标记：JSON 数字不区分 Int/Float/Long，而 SharedPreferences 的
 * `getInt`/`getFloat` 在类型不匹配时抛 `ClassCastException`。把 Int 存成浮点在备份文件里
 * 看不出来，但还原后第一次读就会崩。
 */
internal object SpBackupCodec {

    private const val TYPE_BOOLEAN = "boolean"
    private const val TYPE_INT = "int"
    private const val TYPE_LONG = "long"
    private const val TYPE_FLOAT = "float"
    private const val TYPE_STRING = "string"
    private const val TYPE_SET = "set"

    private const val KEY_TYPE = "t"
    private const val KEY_VALUE = "v"

    /** 导出整份 SP：key → {t, v} */
    fun encode(sp: SpUtil): JSONObject {
        val out = JSONObject()
        sp.allPreferences.forEach { (key, raw) ->
            val entry = JSONObject()
            when (raw) {
                is Boolean -> {
                    entry.putValue(KEY_TYPE, TYPE_BOOLEAN)
                    entry.putValue(KEY_VALUE, raw)
                }

                is Int -> {
                    entry.putValue(KEY_TYPE, TYPE_INT)
                    entry.putValue(KEY_VALUE, raw)
                }

                is Long -> {
                    entry.putValue(KEY_TYPE, TYPE_LONG)
                    entry.putValue(KEY_VALUE, raw)
                }

                is Float -> {
                    entry.putValue(KEY_TYPE, TYPE_FLOAT)
                    entry.putValue(KEY_VALUE, raw)
                }

                is String -> {
                    entry.putValue(KEY_TYPE, TYPE_STRING)
                    entry.putValue(KEY_VALUE, raw)
                }

                is Set<*> -> {
                    entry.putValue(KEY_TYPE, TYPE_SET)
                    entry.putValue(KEY_VALUE, raw.map { it.toString() })
                }

                else -> {
                    // SharedPreferences 只存上述 6 种类型，理论上到不了这里；
                    // 出现未知类型时跳过单个键，不让整次备份失败
                    Log.d(this, "skip unsupported preference: $key -> ${raw?.javaClass}")
                    return@forEach
                }
            }
            out.putValue(key, entry)
        }
        return out
    }

    /**
     * 把 [payload] 整体写回 SP，返回实际写入的键数（供日志与验证使用）。
     *
     * 语义是**镜像**而不是合并：快照里没有、设备上却存在的键会被移除。
     * 因为 SP 只存「显式写过的值」，从没写过的键意味着导出端用的是默认值；
     * 若目标设备把该键改成了非默认值，只做合并就会留下与备份不一致的配置
     * （典型：`labMpvSequentialRead` 这类有默认值的跨模块开关）。
     *
     * 写入与移除在同一个 editor 里提交，其它线程不会读到“半套配置”。
     *
     * 注意不做任何业务归一化：调用方（备份还原）在这些值之上还有自己的兼容逻辑。
     */
    fun apply(sp: SpUtil, payload: JSONObject): Int {
        val staleKeys = sp.allPreferences.keys.filter { !payload.hasKey(it) }
        var written = 0
        sp.editAll { editor ->
            staleKeys.forEach { editor.remove(it) }
            payload.keys().forEach { key ->
                val entry = payload.getJSONObject(key)
                when (entry.getString(KEY_TYPE)) {
                    TYPE_BOOLEAN -> {
                        editor.putBoolean(key, entry.getBoolean(KEY_VALUE))
                        written++
                    }

                    TYPE_INT -> {
                        editor.putInt(key, entry.getInt(KEY_VALUE))
                        written++
                    }

                    TYPE_LONG -> {
                        editor.putLong(key, entry.getLong(KEY_VALUE))
                        written++
                    }

                    TYPE_FLOAT -> {
                        editor.putFloat(key, entry.getFloat(KEY_VALUE))
                        written++
                    }

                    TYPE_STRING -> {
                        editor.putString(key, entry.getString(KEY_VALUE))
                        written++
                    }

                    TYPE_SET -> {
                        val array = entry.getJSONArray(KEY_VALUE)
                        val set = LinkedHashSet<String>(array.size())
                        for (index in 0 until array.size()) {
                            set.add(array.getString(index))
                        }
                        editor.putStringSet(key, set)
                        written++
                    }

                    else -> Log.d(this, "skip preference without type: $key")
                }
            }
        }
        if (staleKeys.isNotEmpty()) {
            Log.d(this, "removed ${staleKeys.size} preference(s) absent from backup")
        }
        return written
    }
}
