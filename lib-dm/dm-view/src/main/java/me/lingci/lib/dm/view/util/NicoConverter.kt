package me.lingci.lib.dm.view.util

import me.lingci.lib.base.util.Log
import me.lingci.lib.dm.view.entity.xml.DmData
import me.lingci.lib.dm.view.entity.xml.DmItem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 *   @author : happyc
 *   time    : 2026/10/22
 *   desc    : niconico 弹幕 XML(packet/chat) 转 Bilibili 弹幕 XML(DmData)
 *   version : 1.0
 */
object NicoConverter {

    private const val TAG_VIEW_COUNTER = "view_counter"
    private const val TAG_CHAT = "chat"

    private const val DEFAULT_COLOR = 0xFFFFFF
    private const val DEFAULT_SIZE = 25
    private const val SIZE_SMALL = 16
    private const val SIZE_BIG = 36

    private const val TYPE_SCROLL = 1
    private const val TYPE_BOTTOM = 4
    private const val TYPE_TOP = 5

    /**
     * nico mail 色名 → RGB 十进制。
     * 基础色取 nico 官方色板，扩展色对齐开源 danmaku2ass 的 NiconicoColorMap。
     */
    private val COLOR_MAP = mapOf(
        "red" to 0xFF0000,
        "pink" to 0xFF8080,
        "orange" to 0xFFCC00,
        "yellow" to 0xFFFF00,
        "green" to 0x00FF00,
        "cyan" to 0x00FFFF,
        "blue" to 0x0000FF,
        "purple" to 0xC000FF,
        "black" to 0x000000,
        "white" to 0xFFFFFF,
        "niconicowhite" to 0xCCCC99,
        "white2" to 0xCCCC99,
        "truered" to 0xCC0033,
        "red2" to 0xCC0033,
        "passionorange" to 0xFF6600,
        "orange2" to 0xFF6600,
        "madyellow" to 0x999900,
        "yellow2" to 0x999900,
        "elementalgreen" to 0x00CC66,
        "green2" to 0x00CC66,
        "marineblue" to 0x33FFCC,
        "blue2" to 0x33FFCC,
        "nobleviolet" to 0x6633CC,
        "purple2" to 0x6633CC
    )

    /** Bilibili 弹幕样式无法表达的特效/装饰命令，命中即跳过该条 */
    private val UNSUPPORTED_STYLES = setOf(
        "snow", "rain", "spark", "fire", "heart", "smoke", "star",
        "angry", "happy", "sad", "rainbow", "revolutionary", "glitter", "strong",
        "nobox", "ucustom", "whiteboard", "monochrome", "background",
        "patissier", "santa", "ninja"
    )

    data class Result(val data: DmData, val skip: Int)

    /**
     * 解析 nico XML 并转换为 Bilibili 弹幕数据。
     *
     * - vpos 单位为 1/100 秒（厘秒），转换为秒需除以 100
     * - mail 中的样式命令映射为 B 站 type/size/color
     * - 高级弹幕（正文以 "/" 开头）与特效命令无法表达，逐条跳过并计数
     *
     * @param strictUnknown true 时未知 mail token 也跳过；默认宽松，忽略未知 token（保留默认白色滚动）
     */
    fun nicoToBiliDmData(xmlStr: String, strictUnknown: Boolean = false): Result {
        val list = ArrayList<DmItem>()
        var skip = 0
        var videoId = ""
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                setInput(StringReader(xmlStr))
            }
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        TAG_VIEW_COUNTER -> videoId = parser.getAttributeValue(null, "id") ?: ""
                        TAG_CHAT -> {
                            val vpos = parser.getAttributeValue(null, "vpos")?.toIntOrNull() ?: 0
                            val date = parser.getAttributeValue(null, "date")?.toLongOrNull() ?: 0L
                            val no = parser.getAttributeValue(null, "no") ?: "0"
                            val mail = parser.getAttributeValue(null, "mail") ?: ""
                            val content = readChatText(parser)
                            val style = if (content.isBlank()) {
                                null
                            } else {
                                mapStyle(mail, content, strictUnknown)
                            }
                            if (style == null) {
                                skip++
                            } else {
                                // p: 时间(秒),类型,字号,颜色,时间戳(毫秒),弹幕池,用户,弹幕id
                                val p = "${vpos.coerceAtLeast(0) / 100.0},$style,${date * 1000},0,0,$no"
                                list.add(DmItem(content = content, style = p, extend = ""))
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            Log.d(this, "nico parse failed", e)
        }
        return Result(DmData(cid = videoId, count = list.size, data = list), skip)
    }

    /** 读取 chat 正文；自闭合或无正文时返回空串 */
    private fun readChatText(parser: XmlPullParser): String {
        return try {
            parser.nextText() ?: ""
        } catch (e: Exception) {
            Log.d(this, "read chat text failed", e.message)
            ""
        }
    }

    /**
     * mail 样式命令 → "type,size,color"。
     * @return null 表示该条无法映射（高级弹幕 / 不支持的特效）
     */
    private fun mapStyle(mail: String, content: String, strictUnknown: Boolean): String? {
        // 2.0 高级/编程弹幕，B 站 p 模型无法表达
        if (content.trimStart().startsWith("/")) {
            return null
        }
        var type = TYPE_SCROLL
        var size = DEFAULT_SIZE
        var color = DEFAULT_COLOR
        for (raw in mail.split(" ")) {
            val token = raw.trim().lowercase()
            if (token.isEmpty()) {
                continue
            }
            when {
                token in UNSUPPORTED_STYLES -> return null
                token == "ue" -> type = TYPE_TOP
                token == "shita" -> type = TYPE_BOTTOM
                token == "big" -> size = SIZE_BIG
                token == "small" -> size = SIZE_SMALL
                // screen/naka=默认滚动，184=旧版匿名命令（不改颜色）
                token == "screen" || token == "naka" || token == "184" -> {}
                COLOR_MAP.containsKey(token) -> color = COLOR_MAP.getValue(token)
                strictUnknown -> return null
            }
        }
        return "$type,$size,$color"
    }
}
