package me.lingci.lib.base.util

import me.lingci.lib.base.BuildConfig
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets

/**
 *   @author : happyc
 *   time    : 2024/09/27
 *   desc    :
 *   version : 1.0
 */
object Log {

    var log2File = false

    private val showLog = BuildConfig.DEBUG
    // 使用 UTF-8 编码的输出流
    private val out = PrintWriter(
        OutputStreamWriter(System.out, StandardCharsets.UTF_8),
        true
    )

    @JvmStatic
    fun d(tag: Any, vararg messages: Any?) {
        if (showLog) {
            try {
                android.util.Log.d(
                    getTag(tag),
                    messages.joinToString(" ") { toStr(it) }
                )
                messages.find { it is Throwable }?.let { e ->
                    android.util.Log.e(getTag(tag), "", e as Throwable)
                }
            } catch (e: Exception) {
                out.println("${getTag(tag)} ${messages.joinToString(" ") { toStr(it) }}")
                messages.find { it is Throwable }?.let { e ->
                    (e as Throwable).printStackTrace(System.err)
                }
            }
        }
    }

    @JvmStatic
    fun dd(tag: Any, messages: List<Any?>) {
        if (showLog) {
            try {
                android.util.Log.d(
                    getTag(tag),
                    messages.joinToString(" ") { toStr(it) }
                )
                messages.find { it is Throwable }?.let { e ->
                    android.util.Log.e(getTag(tag), "", e as Throwable)
                }
            } catch (e: Exception) {
                out.println("${getTag(tag)} ${messages.joinToString(" ") { toStr(it) }}")
                messages.find { it is Throwable }?.let { e ->
                    (e as Throwable).printStackTrace(System.err)
                }
            }
        }
    }

    private fun getTag(any: Any?): String {
        if (any == null) {
            return "LxLog"
        }
        if (any is String) {
            return any
        }
        return any.javaClass.simpleName
    }

    private fun toStr(any: Any?): String {
        if (any == null) {
            return ""
        }
        if (any is String) {
            return any
        }
        if (any is Boolean) {
            return any.toString()
        }
        if (any is Number) {
            return any.toString()
        }
        if (any is Throwable) {
            return "${any.message}"
        }
        /*if (any.javaClass.isAnnotationPresent(Serializable::class.java)) {
            return JsonUtil.toJsonString(any)
        }*/
        // Map / Array / Collection 统一交给 AnyToJson 处理，保证嵌套结构输出为合法 JSON
        return AnyToJson.toString(any)
    }

}