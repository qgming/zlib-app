package com.qgming.zlib.feature.detail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BookDescriptionTest {
    @Test
    fun `plain paragraphs and Windows Unicode and escaped newlines stay separate`() {
        assertEquals(
            "第一行<br>第二行<br><br>第二段<br>第三行<br><br>第三段",
            BookDescription.html("第一行\r\n第二行\\n\\n第二段\u2028第三行\u2029第三段"),
        )
    }

    @Test
    fun `mixed HTML paragraphs and line breaks never run together`() {
        assertEquals(
            "第一段<br>段内换行<br><br>第二段<br><br>第三段",
            BookDescription.html("<p>第一段<br />段内换行</p>\n<div>第二段</div><p>第三段</p>"),
        )
    }

    @Test
    fun `list markers retain ordered and unordered meaning`() {
        assertEquals(
            "1. 第一项<br>2. 第二项<br><br>• 其他项目",
            BookDescription.html("<ol><li>第一项</li><li>第二项</li></ol><ul><li>其他项目</li></ul>"),
        )
    }

    @Test
    fun `emphasis survives while remote images links and styling are removed`() {
        assertEquals(
            "<b>重点</b>与<i>斜体</i>，参考资料。",
            BookDescription.html("""<strong class="title">重点</strong>与<em>斜体</em>，<a href="https://example.test">参考资料</a>。<img src="https://example.test/cover.jpg">"""),
        )
    }

    @Test
    fun `entities and comparison punctuation stay readable by the HTML text renderer`() {
        assertEquals(
            "Tom &amp; Jerry &lt;3 &#8220;引号&#8221;：1 &lt; 2 &gt; 0",
            BookDescription.html("Tom &amp; Jerry &lt;3 &#8220;引号&#8221;：1 < 2 > 0"),
        )
    }

    @Test
    fun `hidden markup never appears in description and excessive blank space is bounded`() {
        val html = BookDescription.html("""<!--private--><style>.title { color: red }</style><script>hidden()</script><p>正文</p><br><br><br><p>下一段</p>""")
        assertEquals("正文<br><br>下一段", html)
        assertFalse(html.contains("hidden"))
        assertEquals("", BookDescription.html("\n<br>\n<p></p>\n"))
    }
}
