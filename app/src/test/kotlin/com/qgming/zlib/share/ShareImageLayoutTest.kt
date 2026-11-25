package com.qgming.zlib.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShareImageLayoutTest {
    @Test
    fun `short content keeps its original scale and measured height`() {
        val page = ShareImageLayout.page(bodyBottom = 624f, footerHeight = 96)

        assertEquals(1f, page.bodyScale)
        assertEquals(0f, page.bodyLeft)
        assertEquals(624 + ShareImageLayout.FOOTER_GAP, page.footerTop)
        assertEquals(page.footerTop + 96 + ShareImageLayout.BOTTOM_SPACE, page.height)
    }

    @Test
    fun `large measured text scales the body while leaving branding unscaled and visible`() {
        for (bodyBottom in listOf(2_792.25f, 3_000f, 8_000f, 32_000f)) {
            val footerHeight = 112
            val page = ShareImageLayout.page(bodyBottom, footerHeight)
            val bodyBottomOnImage = bodyBottom * page.bodyScale

            assertTrue(page.height <= ShareImageLayout.MAX_HEIGHT)
            assertTrue(page.bodyScale in 0f..1f)
            assertTrue(bodyBottomOnImage <= page.footerTop - ShareImageLayout.FOOTER_GAP + 0.01f)
            assertTrue(page.footerTop + footerHeight <= page.height - ShareImageLayout.BOTTOM_SPACE)
            assertEquals((ShareImageLayout.WIDTH * (1f - page.bodyScale)) / 2f, page.bodyLeft, 0.001f)
        }
    }

    @Test
    fun `fractional body height rounds upward before placing footer`() {
        val page = ShareImageLayout.page(bodyBottom = 1_234.1f, footerHeight = 96)

        assertTrue(page.footerTop >= 1_234.1f + ShareImageLayout.FOOTER_GAP)
        assertEquals(1f, page.bodyScale)
    }

    @Test
    fun `invalid geometry is rejected before allocating a bitmap`() {
        for (bodyBottom in listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { ShareImageLayout.page(bodyBottom, 96) }
        }
        assertFailsWith<IllegalArgumentException> { ShareImageLayout.page(100f, 0) }
        assertFailsWith<IllegalArgumentException> { ShareImageLayout.page(100f, ShareImageLayout.MAX_HEIGHT) }
    }
}
