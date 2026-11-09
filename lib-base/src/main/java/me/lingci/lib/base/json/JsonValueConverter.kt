package me.lingci.lib.base.json

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 *   @author : happyc
 *   time    : 2026/11/09
 *   desc    : 任意对象 -> JsonElement 的唯一转换内核。
 *             统一供 JSONObject.putValue、JSONArray 以及 AnyToJson（日志）复用，
 *             避免"自研包装 + org.json"两套 JSON 模型并存。
 *   version : 1.0
 */
internal object JsonValueConverter {

    /** 反射嵌套的最大深度，超出后截断，防止把整棵 UI 对象树打出来 */
    private const val MAX_DEPTH = 6

    private const val KEY_DEPTH_LIMIT = "_depth_limit_reached"
    private const val KEY_CIRCULAR_REF = "_circular_reference"

    /** 字段缓存，避免每次转换重复反射同一类型 */
    private val fieldCache = ConcurrentHashMap<Class<*>, Array<Field>>()

    /**
     * 把任意对象转换为 [JsonElement]。
     * 单个字段/元素转换失败会被忽略，不会中断整体转换。
     */
    fun toElement(value: Any?): JsonElement {
        return convert(value, ConvertContext(0, newIdentitySet()))
    }

    private class ConvertContext(
        val depth: Int,
        /** 当前转换路径上的对象，用于检测循环引用 */
        val path: MutableSet<Any>
    ) {
        fun next() = ConvertContext(depth + 1, path)
    }

    private fun newIdentitySet(): MutableSet<Any> {
        // IdentityHashMap 按引用比较，避免 equals 重写导致误判循环
        return Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    }

    private fun convert(value: Any?, ctx: ConvertContext): JsonElement {
        if (ctx.depth > MAX_DEPTH) {
            return JsonObject(mapOf(KEY_DEPTH_LIMIT to JsonPrimitive(true)))
        }
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is JSONObject -> value.original()
            is JSONArray -> value.original()
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Char -> JsonPrimitive(value.toString())
            is Enum<*> -> JsonPrimitive(value.name)
            is Date -> JsonPrimitive(value.time)
            is CharSequence -> JsonPrimitive(value.toString())
            is Class<*> -> JsonPrimitive(value.name)
            is Collection<*> -> JsonArray(value.map { convert(it, ctx.next()) })
            is Map<*, *> -> convertMap(value, ctx)
            else -> if (value.javaClass.isArray) {
                convertArray(value, ctx)
            } else {
                // 对象自身的深度由字段转换时递增，此处不再额外自增
                convertObject(value, ctx)
            }
        }
    }

    private fun convertMap(map: Map<*, *>, ctx: ConvertContext): JsonElement {
        val result = LinkedHashMap<String, JsonElement>(map.size)
        map.forEach { (key, value) ->
            result[key?.toString() ?: "null"] = convert(value, ctx.next())
        }
        return JsonObject(result)
    }

    private fun convertArray(array: Any, ctx: ConvertContext): JsonElement {
        val length = ReflectArray.getLength(array)
        val result = ArrayList<JsonElement>(length)
        for (i in 0 until length) {
            result.add(convert(ReflectArray.get(array, i), ctx.next()))
        }
        return JsonArray(result)
    }

    private fun convertObject(obj: Any, ctx: ConvertContext): JsonElement {
        // 同一对象在当前转换路径上重复出现，视为循环引用
        if (!ctx.path.add(obj)) {
            return JsonObject(mapOf(KEY_CIRCULAR_REF to JsonPrimitive("true")))
        }
        return try {
            val result = LinkedHashMap<String, JsonElement>()
            for (field in getFields(obj.javaClass)) {
                try {
                    result[field.name] = convert(field.get(obj), ctx.next())
                } catch (_: Exception) {
                    // 单个字段读取/转换失败时跳过，保证整体仍可输出
                }
            }
            JsonObject(result)
        } finally {
            ctx.path.remove(obj)
        }
    }

    private fun getFields(clazz: Class<*>): Array<Field> {
        return fieldCache.getOrPut(clazz) {
            val result = mutableListOf<Field>()
            var current: Class<*>? = clazz
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (!Modifier.isStatic(field.modifiers) &&
                        !Modifier.isTransient(field.modifiers) &&
                        !field.isSynthetic
                    ) {
                        field.isAccessible = true
                        result.add(field)
                    }
                }
                current = current.superclass
            }
            result.toTypedArray()
        }
    }
}
