package me.lingci.lib.base.util

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.regex.Pattern

/**
 *   @author : DouBao-1.5-Pro
 *   time    : 2025/03/24
 *   desc    :
 *   version : 1.0
 */
object JsonTextHelper {

    /** 仅用于格式化展示，prettyPrint 默认缩进为 4 个空格 */
    private val prettyJson = Json {
        prettyPrint = true
    }

    /**
     * 格式化并高亮 JSON 字符串
     * @param json 未格式化的 JSON 字符串
     * @return 格式化并高亮后的 SpannableString
     */
    fun formatAndHighlightJson(json: String): SpannableString {
        val formattedJson = formatJson(json)
        return highlightJson(formattedJson)
    }

    /**
     * 格式化 JSON 字符串
     * @param json 未格式化的 JSON 字符串
     * @return 格式化后的 JSON 字符串
     */
    private fun formatJson(json: String): String {
        return try {
            // 等价于原 org.json 的 toString(4)：4 空格缩进
            prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(json))
        } catch (e: Exception) {
            json
        }
    }

    /**
     * 高亮 JSON 字符串
     * @param json 格式化后的 JSON 字符串
     * @return 高亮后的 SpannableString
     */
    private fun highlightJson(json: String): SpannableString {
        val spannable = SpannableString(json)
        val regex = arrayOf(
            // 匹配键名
            Pattern.compile("\"([^\"]+)\":\\s*"),
            // 匹配字符串值
            Pattern.compile("\"([^\"]*)\""),
            // 匹配布尔值和 null
            Pattern.compile("\\b(true|false|null)\\b"),
            // 匹配纯数字值
            Pattern.compile("(?<![\\w\"])-?\\d+(?:\\.\\d+)?(?![\\w\"])")
        )
        val colors = intArrayOf(
            Color.rgb(0, 128, 0), // 键名颜色
            Color.rgb(255, 165, 0), // 字符串值颜色
            Color.rgb(0, 0, 255), // 布尔值和 null 颜色
            Color.rgb(128, 0, 128) // 纯数字值颜色
        )
        for (i in regex.indices) {
            val matcher = regex[i].matcher(json)
            while (matcher.find()) {
                spannable.setSpan(
                    ForegroundColorSpan(colors[i]),
                    matcher.start(),
                    matcher.end(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

}