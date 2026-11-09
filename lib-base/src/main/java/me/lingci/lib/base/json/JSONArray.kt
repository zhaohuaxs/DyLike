package me.lingci.lib.base.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import me.lingci.lib.base.util.Log


/**
 *   @author : happyc
 *   time    : 2023/07/19
 *   desc    : JSON 数组包装（底层 kotlinx.serialization.JsonArray）
 *   version : 2.0
 */
class JSONArray {

    private val items = mutableListOf<JsonElement>()

    constructor()

    constructor(json: String) {
        try {
            items.addAll(Json.parseToJsonElement(json).jsonArray)
        } catch (e: Exception) {
            Log.d(this, "init failed", e.message)
        }
    }

    constructor(jsonArray: JsonArray) {
        items.addAll(jsonArray)
    }

    constructor(list: List<JsonElement>) {
        items.addAll(list)
    }

    /** 取底层 JsonArray（快照） */
    fun original(): JsonArray {
        return JsonArray(items)
    }

    fun itemIsArray(index: Int): Boolean {
        // 原实现使用 index > size，index == size 时会越界崩溃
        return index in items.indices && items[index] is JsonArray
    }

    fun itemIsObj(index: Int): Boolean {
        return index in items.indices && items[index] is JsonObject
    }

    fun firstJSONObject(): JSONObject {
        return (items.firstOrNull() as? JsonObject)?.let { JSONObject(it) } ?: JSONObject()
    }

    fun getJSONObject(index: Int): JSONObject {
        return (items.getOrNull(index) as? JsonObject)?.let { JSONObject(it) } ?: JSONObject()
    }

    fun getJSONArray(index: Int): JSONArray {
        return (items.getOrNull(index) as? JsonArray)?.let { JSONArray(it) } ?: JSONArray()
    }

    fun getStringArray(index: Int): List<String> {
        val array = items.getOrNull(index) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        }
    }

    fun getString(index: Int): String {
        return (items.getOrNull(index) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
    }

    fun size(): Int {
        return items.size
    }

    fun length(): Int {
        return size()
    }

    override fun toString(): String {
        return JsonArray(items).toString()
    }

    fun isEmpty(): Boolean {
        return items.isEmpty()
    }

    fun isNotEmpty(): Boolean {
        return items.isNotEmpty()
    }

}

fun JSONArray.forEach(action: (JSONObject) -> Unit) {
    // 非对象元素直接跳过，避免原实现 element.jsonObject 抛异常
    original().forEach { element ->
        (element as? JsonObject)?.let { action(JSONObject(it)) }
    }
}

fun JSONArray.forEach2(item: (jsonObject: JSONObject) -> Unit) {
    for (i in 0 until this.size()) {
        if (this.itemIsObj(i)) {
            item(this.getJSONObject(i))
        }
    }
}
