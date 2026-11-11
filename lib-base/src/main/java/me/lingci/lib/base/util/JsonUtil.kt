package me.lingci.lib.base.util

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Json工具类
 * Created by bafsj on 17/3/31.
 */
object JsonUtil {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    inline fun <reified T> toEntity(jsonString: String): T {
        return json.decodeFromString(jsonString)
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> toEntity(stream: InputStream): T {
        stream.use {
            return json.decodeFromStream(it)
        }
    }

    inline fun <reified T> toEntityCbc(encodeStr: String): T {
        return json.decodeFromString(AesUtil.aesDecrypt(encodeStr))
    }

    inline fun <reified T> toList(jsonString: String): List<T> {
        if (jsonString.isEmpty() || "[]" == jsonString || "[{}]" == jsonString) {
            return listOf()
        }
        return try {
            json.decodeFromString(jsonString)
        } catch (e: Exception) {
            logD("toList failed", e)
            listOf()
        }
    }

    inline fun <reified T> toJsonString(src: T): String {
        try {
            return Json.encodeToString(src)
        } catch (e: Exception) {
            logD("toJsonString failed", e.message)
            return ""
        }
    }

    inline fun <reified T> toJsonString(src: List<T>): String {
        try {
            return Json.encodeToString(src)
        } catch (e: Exception) {
            logD("toJsonString failed", e.message)
            return ""
        }
    }

    fun toJsonObject(jsonString: String): JsonObject {
        if (jsonString.isEmpty() || "{}" == jsonString) {
            return JsonObject(mapOf())
        }
        try {
            return json.parseToJsonElement(jsonString).jsonObject
        } catch (e: Exception) {
            logD("toJsonObject", e)
            return JsonObject(mapOf())
        }
    }

    fun toJsonArray(jsonString: String): JsonArray {
        if (jsonString.isEmpty() || "[]" == jsonString) {
            return JsonArray(listOf())
        }
        try {
            return json.parseToJsonElement(jsonString).jsonArray
        } catch (e: Exception) {
            logD("toJsonArray", e)
            return JsonArray(listOf())
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified T> writeTo(src: T, stream: OutputStream) {
        stream.use {
            Json.encodeToStream(src, it)
        }
    }

}

inline fun <reified T> File.writeJsonEntity(src: T): Boolean {
    this.createNew()
    this.outputStream().use { stream ->
        return try {
            JsonUtil.writeTo(src, stream)
            true
        } catch (e: Exception) {
            logD("writeJsonEntity failed", e.message)
            false
        }
    }
}

inline fun <reified T> File.readJsonEntity(): T? {
    if (notExists()) return null
    this.inputStream().use { stream ->
        return try {
            JsonUtil.toEntity(stream)
        } catch (e: Exception) {
            logD("readJsonEntity failed", e.message)
            null
        }
    }
}