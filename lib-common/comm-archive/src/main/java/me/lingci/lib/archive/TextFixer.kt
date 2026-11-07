package me.lingci.lib.archive

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TextFixer {

    /** 候选编码：按优先级排列，同分时靠前者胜出（GBK 保持原有默认优先级） */
    private val GBK = Charset.forName("GBK")
    private val BIG5 = Charset.forName("Big5")
    private val SHIFT_JIS = Charset.forName("Shift_JIS")
    private val EUC_KR = Charset.forName("EUC-KR")
    private val KOI8_R = Charset.forName("KOI8-R")
    private val IBM437 = Charset.forName("IBM437")
    private val ISO_8859_1 = Charsets.ISO_8859_1

    private fun containsChinese(text: String): Boolean {
        return text.count { it in '\u4e00'..'\u9fff' } >= 2 // 至少两个汉字
    }

    private fun tryFixAsUtf8Mojibake(s: String): String {
        return String(s.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    }

    private fun tryFixAsGbkMojibake(s: String): String {
        return String(s.toByteArray(Charsets.ISO_8859_1), Charset.forName("GBK"))
    }

    /**
     * 严格解码：遇到非法/不可映射字节返回 null（不放宽为替换字符）。
     * 这是不引入依赖时能拿到的最强有效性信号。
     */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? {
        if (bytes.isEmpty()) return null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
    }

    /** Shift-JIS 结构门槛：必须存在至少一个双字节序列，避免纯半角片假名单字节解释“通吃”任意高位字节 */
    private fun hasDoubleByteSjis(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b <= 0x7F || b in 0xA1..0xDF -> i++
                b in 0x81..0x9F || b in 0xE0..0xFC -> {
                    val trail = bytes.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: return false
                    if (trail in 0x40..0x7E || trail in 0x80..0xFC) return true else return false
                }
                else -> return false
            }
        }
        return false
    }

    /**
     * KOI8-R 结构门槛：
     * 1) 高位字节必须全部落在 0xC0-0xFF（西里尔字母区），排除制表符/符号区；
     * 2) 高位字节数量需 >= 6（至少 3 个双字节字符的证据量）。
     * 原因：KOI8-R 是 1 字节 1 字母、GBK 是 2 字节 1 汉字，短串（如「书.xml」的 CA E9）
     * 在两种编码下都严格合法且无法靠字符统计区分，此类歧义按兼容性保留 GBK 默认，
     * 只有证据量足够时才允许 KOI8-R 覆盖。
     */
    private fun isValidKoi8r(bytes: ByteArray): Boolean {
        var highCount = 0
        for (byte in bytes) {
            val b = byte.toInt() and 0xFF
            if (b >= 0x80) {
                if (b < 0xC0) return false
                highCount++
            }
        }
        return highCount >= 6
    }

    /** 传统 EUC-KR 结构门槛：非 ASCII 字节必须全部 >= 0xA1（排除 CP949 过度宽松导致的误判） */
    private fun isValidEucKr(bytes: ByteArray): Boolean {
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b < 0x80) {
                i++
            } else {
                if (b < 0xA1) return false
                val trail = bytes.getOrNull(i + 1)?.toInt()?.and(0xFF) ?: return false
                if (trail < 0xA1) return false
                i += 2
            }
        }
        return true
    }

    /** 原始串中是否含 C1 控制字符（U+0080-U+009F），用于识别 CP437/Latin-1 高位字节场景 */
    private fun hasC1Control(text: String): Boolean = text.any { it in '\u0080'..'\u009f' }

    /**
     * 语言无关评分：
     * - 可见 ASCII 保留越多越好（+2），控制字符/未定义区重罚；
     * - 汉字 +2；假名仅对 Shift_JIS 加分、其他编码扣分（汉字文件名里出现假名通常是误判）；
     * - 谚文仅对 EUC-KR 小幅加分（韩文与 GBK 天生难区分，不压过 GBK 默认）；
     * - 西里尔仅对 KOI8-R 加分，其他编码出现西里尔扣分；
     * - 其余字母 +2，符号/标点 -3。
     */
    private fun score(text: String, charsetName: String): Int {
        var score = 0
        for (ch in text) {
            val c = ch.code
            when {
                c < 0x20 || c == 0x7F || c in 0x80..0x9F -> score -= 10
                c < 0x7F -> score += 2
                ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> score += 2
                ch in '\u3040'..'\u30FF' -> score += if (charsetName == "Shift_JIS") 4 else -2
                ch in '\uFF61'..'\uFF9F' -> score += if (charsetName == "Shift_JIS") 1 else -2
                ch in '\uAC00'..'\uD7AF' -> score += if (charsetName == "EUC-KR") 2 else -1
                ch in '\u3130'..'\u318F' -> score -= 3
                ch in '\u0400'..'\u04FF' -> score += if (charsetName == "KOI8-R") 4 else -1
                ch in '\uE000'..'\uF8FF' || c == 0xFFFD -> score -= 10
                ch.isLetter() -> score += 2
                else -> score -= 3
            }
        }
        return score
    }

    fun fixGarbledFilename(input: String): String = fixGarbledFilename(input, null)

    /**
     * 修复乱码文件名；[charsetName] 非空时走确定性路径（调用方已知整包编码）。
     * 原有单参数入口行为保持不变。
     */
    fun fixGarbledFilename(input: String, charsetName: String?): String {
        if (input.isEmpty()) return input

        // 显式指定编码：直接严格解码，失败原样返回
        if (!charsetName.isNullOrBlank()) {
            val bytes = input.toByteArray(ISO_8859_1)
            return strictDecode(bytes, Charset.forName(charsetName)) ?: input
        }

        // 如果已经是正常中文，直接返回（保持旧行为）
        if (containsChinese(input)) return input

        // 含 >U+00FF 字符说明已由上层正确解码（UTF-8 flag / 7z UTF-16），不再做字节回退
        if (input.any { it.code > 0xFF }) return input

        val bytes = input.toByteArray(ISO_8859_1)
        // 纯 ASCII：旧实现原样返回，保持一致
        if (bytes.all { (it.toInt() and 0xFF) < 0x80 }) return input

        // UTF-8 严格合法优先（与旧实现 candidate1 一致，且不会破坏已有 UTF-8 结果）
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }

        // 以旧实现的 GBK 结果作为默认基准：严格解码失败时退回宽松解码，复刻旧逻辑，
        // 保证「GBK 解出至少 1 个汉字」的既有输出逐字符不变；其它编码必须同时严格合法且评分更高才能覆盖它。
        val gbkText = strictDecode(bytes, GBK) ?: runCatching { tryFixAsGbkMojibake(input) }.getOrNull()
        var bestText = input
        var bestScore = score(input, "ISO-8859-1")
        if (gbkText != null && gbkText.count { it in '\u4E00'..'\u9FFF' } > 0) {
            bestText = gbkText
            bestScore = score(gbkText, "GBK")
        }

        // 其余候选按优先级：Big5 > Shift_JIS > EUC-KR > KOI8-R > IBM437（均需严格解码通过）
        val candidates = mutableListOf<Pair<String, String>>()
        strictDecode(bytes, BIG5)?.let { candidates.add("Big5" to it) }
        if (hasDoubleByteSjis(bytes)) {
            strictDecode(bytes, SHIFT_JIS)?.let { candidates.add("Shift_JIS" to it) }
        }
        if (isValidEucKr(bytes)) {
            strictDecode(bytes, EUC_KR)?.let { candidates.add("EUC-KR" to it) }
        }
        if (isValidKoi8r(bytes)) {
            strictDecode(bytes, KOI8_R)?.let { candidates.add("KOI8-R" to it) }
        }
        if (hasC1Control(input)) {
            strictDecode(bytes, IBM437)?.let { candidates.add("IBM437" to it) }
        }
        // 注意：ISO-8859-1 解码结果恒等于 input 本身，已作为初始基准，不能再作为候选参与竞争，
        // 否则其“字母全 +2”的评分会把正确的 GBK/其它结果又改回原文。

        for ((name, text) in candidates) {
            val candidateScore = score(text, name)
            if (candidateScore > bestScore) {
                bestScore = candidateScore
                bestText = text
            }
        }
        return bestText
    }
}
