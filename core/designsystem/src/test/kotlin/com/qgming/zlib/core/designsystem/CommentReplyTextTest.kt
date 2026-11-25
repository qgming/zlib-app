package com.qgming.zlib.core.designsystem

import androidx.compose.ui.graphics.Color
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText
import kotlin.test.Test
import kotlin.test.assertEquals

class CommentReplyTextTest {
    @Test
    fun `localized reply phrase styles only the actual parent mention`() {
        val tint = Color.Blue
        for ((template, expected) in listOf(
            appText(TextR.string.text_comment_reply_to, REPLY_MENTION) to "回复@读者：谢谢",
            "Reply to $REPLY_MENTION: " to "Reply to @读者: 谢谢",
        )) {
            val result = annotateReplyBody(template, "@读者", "谢谢", tint)
            assertEquals(expected, result.text)
            val style = result.spanStyles.single()
            assertEquals("@读者", result.text.substring(style.start, style.end))
            assertEquals(tint, style.item.color)
            assertEquals(emptyList(), result.getLinkAnnotations(0, result.length))
        }
    }
}
