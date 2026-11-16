package me.lingci.lib.archive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class TextFixerTest {

    private val gbk = Charset.forName("GBK")
    private val iso = Charsets.ISO_8859_1

    /** 模拟上层以 ISO-8859-1 误解码包内条目名后传入的乱码串 */
    private fun mojibake(name: String, charset: Charset): String =
        String(name.toByteArray(charset), iso)

    @Test
    fun gbkSingleCharFilenameIsValidUtf8_stillRecoversChinese() {
        // 「一」GBK = D2 BB，恰为合法 UTF-8（西里尔 һ），曾因 UTF-8 无条件早退被破坏
        assertEquals("一.xml", TextFixer.fixGarbledFilename(mojibake("一.xml", gbk)))
    }

    @Test
    fun gbkMultiCharFilenameRecoversChinese() {
        assertEquals("测试.xml", TextFixer.fixGarbledFilename(mojibake("测试.xml", gbk)))
        assertEquals("书.xml", TextFixer.fixGarbledFilename(mojibake("书.xml", gbk)))
    }

    @Test
    fun genuineUtf8ChineseIsPreserved() {
        assertEquals("中文.xml", TextFixer.fixGarbledFilename(mojibake("中文.xml", Charsets.UTF_8)))
        assertEquals("测试视频.xml", TextFixer.fixGarbledFilename(mojibake("测试视频.xml", Charsets.UTF_8)))
    }

    @Test
    fun pureAsciiUnchanged() {
        assertEquals("file.xml", TextFixer.fixGarbledFilename("file.xml"))
    }

    @Test
    fun alreadyChineseUnchanged() {
        assertEquals("中文.xml", TextFixer.fixGarbledFilename("中文.xml"))
    }
}