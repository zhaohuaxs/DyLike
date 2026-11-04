package me.lingci.dy.player.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HtmlTextTest {

    @Test
    fun numericEntities_areDecoded() {
        // 存量 video_data.name 里实际出现的两种
        assertEquals(
            "https://space.bilibili.com/809048",
            "https&#58;&#47;&#47;space.bilibili.com&#47;809048".decodeHtmlEntities()
        )
    }

    @Test
    fun namedEntities_areDecoded() {
        assertEquals("A & B < C > D \"E\" 'F'", "A &amp; B &lt; C &gt; D &quot;E&quot; &apos;F&apos;"
            .decodeHtmlEntities())
    }

    @Test
    fun hexEntities_areDecoded() {
        assertEquals("A:", "A&#x3A;".decodeHtmlEntities())
        assertEquals("A:", "A&#X3a;".decodeHtmlEntities())
    }

    @Test
    fun plainText_isReturnedAsIs() {
        val text = "没有实体的标题 #标签.mp4"
        // 不含 & 时直接返回原对象，不新建字符串（列表滚动时的热路径）
        assertSame(text, text.decodeHtmlEntities())
    }

    @Test
    fun strayAmpersand_isKept() {
        assertEquals("A & B", "A & B".decodeHtmlEntities())
        assertEquals("5 & 6; 7", "5 & 6; 7".decodeHtmlEntities())
        assertEquals("&unknown;", "&unknown;".decodeHtmlEntities())
    }

    @Test
    fun partialEntity_isNotSwallowed() {
        assertEquals("a & b", "a & b".decodeHtmlEntities())
        assertEquals("&#;", "&#;".decodeHtmlEntities())
        assertEquals("&#0;", "&#0;".decodeHtmlEntities())
    }

    @Test
    fun multipleEntities_inOneString() {
        assertEquals("a:b/c", "a&#58;b&#47;c".decodeHtmlEntities())
    }
}
