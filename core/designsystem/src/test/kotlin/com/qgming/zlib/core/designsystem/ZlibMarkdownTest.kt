package com.qgming.zlib.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ZlibMarkdownTest {

    @Test
    fun `parses headings lists blockquotes code blocks horizontal rules and paragraphs`() {
        val sample = """
            # 芝士读书 1.5.0

            > 提示：请先检查网络。

            ## 新增功能

            - **书单管理**：支持点击移除图书
              - 二级说明，含 `code` 与 *斜体*
            - ~~已废弃~~ 与 [发布页](https://github.com/qgming/zlib-app/releases)

            ---

            1. 第一项
            2、第二项

            ```kotlin
            val version = "1.5.0"
            ```

            普通说明段落。
        """.trimIndent()

        val blocks = parseMarkdownBlocks(sample)
        assertEquals(11, blocks.size)

        val h1 = assertIs<MarkdownBlock.Heading>(blocks[0])
        assertEquals(1, h1.level)
        assertEquals(listOf(MarkdownInline.Plain("芝士读书 1.5.0")), h1.content)

        val quote = assertIs<MarkdownBlock.BlockQuote>(blocks[1])
        assertEquals(1, quote.blocks.size)
        assertIs<MarkdownBlock.Paragraph>(quote.blocks[0])

        val h2 = assertIs<MarkdownBlock.Heading>(blocks[2])
        assertEquals(2, h2.level)
        assertEquals(listOf(MarkdownInline.Plain("新增功能")), h2.content)

        val b1 = assertIs<MarkdownBlock.BulletItem>(blocks[3])
        assertEquals(0, b1.indent)
        assertEquals(
            listOf(
                MarkdownInline.Bold(listOf(MarkdownInline.Plain("书单管理"))),
                MarkdownInline.Plain("：支持点击移除图书"),
            ),
            b1.content,
        )

        val b2 = assertIs<MarkdownBlock.BulletItem>(blocks[4])
        assertEquals(1, b2.indent)
        assertTrue(b2.content.any { it == MarkdownInline.Code("code") })
        assertTrue(b2.content.any { it == MarkdownInline.Italic(listOf(MarkdownInline.Plain("斜体"))) })

        val b3 = assertIs<MarkdownBlock.BulletItem>(blocks[5])
        assertTrue(b3.content.any { it == MarkdownInline.Strikethrough(listOf(MarkdownInline.Plain("已废弃"))) })
        assertTrue(
            b3.content.any {
                it == MarkdownInline.Link(listOf(MarkdownInline.Plain("发布页")), "https://github.com/qgming/zlib-app/releases")
            },
        )

        assertIs<MarkdownBlock.HorizontalRule>(blocks[6])

        val o1 = assertIs<MarkdownBlock.OrderedItem>(blocks[7])
        assertEquals("1.", o1.marker)
        assertEquals(listOf(MarkdownInline.Plain("第一项")), o1.content)

        val o2 = assertIs<MarkdownBlock.OrderedItem>(blocks[8])
        assertEquals("2、", o2.marker)
        assertEquals(listOf(MarkdownInline.Plain("第二项")), o2.content)

        val code = assertIs<MarkdownBlock.CodeBlock>(blocks[9])
        assertEquals("kotlin", code.language)
        assertEquals("val version = \"1.5.0\"", code.code)

        val paragraph = assertIs<MarkdownBlock.Paragraph>(blocks[10])
        assertEquals(listOf(MarkdownInline.Plain("普通说明段落。")), paragraph.content)
    }

    @Test
    fun `parses trailing paragraph after code block and preserves snake case words`() {
        val sample = """
            ```
            line1
            ```

            支持 `remix_userid` 和普通 snake_case_name 文本。
        """.trimIndent()
        val blocks = parseMarkdownBlocks(sample)
        assertEquals(2, blocks.size)
        val paragraph = assertIs<MarkdownBlock.Paragraph>(blocks[1])
        assertEquals(
            listOf(
                MarkdownInline.Plain("支持 "),
                MarkdownInline.Code("remix_userid"),
                MarkdownInline.Plain(" 和普通 snake_case_name 文本。"),
            ),
            paragraph.content,
        )
    }

    @Test
    fun `rejects unsafe link schemes and falls back to plain text`() {
        assertFalse(isSafeMarkdownUrl("javascript:alert(1)"))
        assertFalse(isSafeMarkdownUrl("file:///etc/passwd"))
        assertFalse(isSafeMarkdownUrl("https://example.com/with space"))
        assertTrue(isSafeMarkdownUrl("https://github.com/qgming/zlib-app"))

        val inlines = parseMarkdownInlines("点击 [危险](javascript:alert(1)) 文本")
        assertEquals(listOf(MarkdownInline.Plain("点击 [危险](javascript:alert(1)) 文本")), inlines)
    }

    @Test
    fun `builds annotated string with styles and clickable link annotations`() {
        val inlines = parseMarkdownInlines("**粗体** *斜体* ~~删除~~ `代码` [链接](https://example.com)")
        val opened = mutableListOf<String>()
        val annotated = buildMarkdownAnnotatedString(
            inlines = inlines,
            linkColor = Color.Blue,
            codeBackground = Color.LightGray,
            codeTextColor = Color.Black,
            onOpenUrl = { opened += it },
        )

        assertTrue(annotated.text.contains("粗体"))
        assertTrue(annotated.text.contains("斜体"))
        assertTrue(annotated.text.contains("删除"))
        assertTrue(annotated.text.contains("代码"))
        assertTrue(annotated.text.contains("链接"))

        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        assertTrue(annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertTrue(annotated.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })

        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com", (links.single().item as androidx.compose.ui.text.LinkAnnotation.Clickable).tag)
        (links.single().item as androidx.compose.ui.text.LinkAnnotation.Clickable).linkInteractionListener?.onClick(links.single().item)
        assertEquals(listOf("https://example.com"), opened)
    }

    @Test
    fun `renders legacy plain text release notes cleanly`() {
        val legacy = """
            芝士读书 1.4.0

            功能

            1、新增“我的书单”。

            2、新增图书评论查看。
        """.trimIndent()

        val blocks = parseMarkdownBlocks(legacy)
        assertEquals(4, blocks.size)
        assertIs<MarkdownBlock.Paragraph>(blocks[0])
        assertIs<MarkdownBlock.Paragraph>(blocks[1])
        assertEquals("1、", assertIs<MarkdownBlock.OrderedItem>(blocks[2]).marker)
        assertEquals("2、", assertIs<MarkdownBlock.OrderedItem>(blocks[3]).marker)
    }
}
