package com.qgming.zlib.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class BookGridColumnsTest {
    @Test
    fun `automatic columns follow current window width at each boundary`() {
        val cases = mapOf(0 to 2, 320 to 2, 359 to 2, 360 to 3, 393 to 3, 599 to 3, 600 to 4, 839 to 4, 840 to 5, 1280 to 5)
        cases.forEach { (width, columns) ->
            assertEquals(columns, BookGridColumns.AUTO.countForWidth(width), "Width $width dp")
        }
    }

    @Test
    fun `manual density wins over automatic width changes`() {
        val selections = mapOf(BookGridColumns.TWO to 2, BookGridColumns.THREE to 3, BookGridColumns.FOUR to 4, BookGridColumns.FIVE to 5)
        selections.forEach { (mode, columns) ->
            listOf(320, 393, 600, 840, 1280).forEach { width ->
                assertEquals(columns, mode.countForWidth(width))
            }
        }
    }

    @Test
    fun `new or unknown preferences use auto and manual choices survive persistence`() {
        assertEquals(BookGridColumns.AUTO, BookGridColumns.fromPreference(null))
        assertEquals(BookGridColumns.AUTO, BookGridColumns.fromPreference("SIX"))
        BookGridColumns.entries.forEach { mode ->
            assertEquals(mode, BookGridColumns.fromPreference(mode.name))
        }
    }
}
