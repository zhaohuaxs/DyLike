package me.lingci.lib.base.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import me.lingci.lib.base.util.Log

/**
 *   @author : happyc
 *   time    : 2023/07/19
 *   desc    : JSON 对象包装（底层 kotlinx.serialization.JsonObject）
 *   version : 2.0
 */
class JSONObject {

    // 有序可变 Map 承载数据：单次 put 为 O(1)，并保持字段写入顺序
    private val values = LinkedHashMap<String, JsonElement>()

    constructor()

    constructor(json: String) {
        try {
            values.putAll(Json.parseToJsonElement(json).jsonObject)
        } catch (e: Exception) {
            // 与原实现保持一致：解析失败返回空对象，额外补日志便于排查脏数据
            Log.d(this, "parse object failed", e.message)
        }
    }

    constructor(jsonObject: JsonObject) {
        values.putAll(jsonObject)
    }

    constructor(map: Map<*, *>) {
        // 逐值递归转换，修复原实现 value.toString() 导致嵌套结构被压成字符串的问题
        map.forEach { (key, value) ->
            values[key.toString()] = JsonValueConverter.toElement(value)
        }
    }

    /** 取底层 JsonObject（快照） */
    fun original(): JsonObject {
        return JsonObject(values)
    }

    fun hasKey(key: String): Boolean {
        return values.containsKey(key)
    }

    fun getJSONObject(key: String): JSONObject {
        return when (val element = values[key]) {
            is JsonObject -> JSONObject(element)
            // 兼容历史数据中"对象被存成 JSON 字符串"的情况
            is JsonPrimitive -> if (element.isJsonLike('{')) JSONObject(element.content) else JSONObject()
            else -> JSONObject()
        }
    }

    fun getJSONArray(key: String): JSONArray {
        return when (val element = values[key]) {
            is JsonArray -> JSONArray(element)
            // 兼容历史数据中"数组被存成 JSON 字符串"的情况
            is JsonPrimitive -> if (element.isJsonLike('[')) JSONArray(element.content) else JSONArray()
            else -> JSONArray()
        }
    }

    fun getString(key: String): String {
        return getString(key, "")
    }

    fun getString(key: String, default: String): String {
        return primitive(key)?.content ?: default
    }

    fun getInt(key: String): Int {
        return getInt(key, -1)
    }

    fun getInt(key: String, default: Int): Int {
        return try {
            primitive(key)?.int ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun getLong(key: String): Long {
        return getLong(key, -1L)
    }

    fun getLong(key: String, default: Long): Long {
        return try {
            primitive(key)?.long ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun getFloat(key: String): Float {
        return getFloat(key, -1.0f)
    }

    fun getFloat(key: String, default: Float): Float {
        return try {
            primitive(key)?.float ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun getDouble(key: String): Double {
        return getDouble(key, -1.0)
    }

    fun getDouble(key: String, default: Double): Double {
        return try {
            primitive(key)?.double ?: default
        } catch (e: Exception) {
            default
        }
    }

    fun getBoolean(key: String): Boolean {
        return getBoolean(key, false)
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        return try {
            primitive(key)?.boolean ?: default
        } catch (e: Exception) {
            default
        }
    }

    /** 写入任意值，支持 null（写成 JSON null）与嵌套对象/集合 */
    fun putValue(key: String, value: Any?) {
        values[key] = JsonValueConverter.toElement(value)
    }

    fun keys(): Set<String> {
        return values.keys
    }

    fun isEmpty(): Boolean {
        return values.isEmpty()
    }

    fun isNotEmpty(): Boolean {
        return values.isNotEmpty()
    }

    fun toJsonStr(): String {
        return JsonObject(values).toString()
    }

    override fun toString(): String {
        return toJsonStr()
    }

    /** 取原始值；显式 null（JsonNull）与缺失一律视为不可用 */
    private fun primitive(key: String): JsonPrimitive? {
        val element = values[key] as? JsonPrimitive ?: return null
        return if (element is JsonNull) null else element
    }

    /** 判断该原始值是否是"被序列化成字符串的 JSON 片段" */
    private fun JsonPrimitive.isJsonLike(prefix: Char): Boolean {
        return isString && content.trimStart().startsWith(prefix)
    }
}
