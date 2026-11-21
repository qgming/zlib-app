package com.qgming.zlib.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsbnTest {

    @Test
    fun `valid isbn 10 is accepted`() {
        assertTrue(Isbn.isValid10("0306406152"))
        assertTrue(Isbn.isValid("0-306-40615-2"))
        assertTrue(Isbn.isValid10("080442957X"))
    }

    @Test
    fun `invalid isbn 10 check digit is rejected`() {
        assertFalse(Isbn.isValid10("0306406153"))
        assertFalse(Isbn.isValid10("030640615"))
    }

    @Test
    fun `valid isbn 13 is accepted`() {
        assertTrue(Isbn.isValid13("9780306406157"))
        assertFalse(Isbn.isValid13("9780306406158"))
        assertFalse(Isbn.isValid13("978030640615X"))
    }

    @Test
    fun `normalize strips separators and rejects junk`() {
        assertEquals("9780306406157", Isbn.normalize(" 978-0-306-40615-7 "))
        assertNull(Isbn.normalize("not an isbn"))
        assertNull(Isbn.normalize("B00X4WHP5E"))
        assertNull(Isbn.normalize(""))
        assertNull(Isbn.normalize(null))
    }

    @Test
    fun `isbn conversions round-trip`() {
        assertEquals("9780306406157", Isbn.toIsbn13("0306406152"))
        assertEquals("0306406152", Isbn.toIsbn10("9780306406157"))
        assertNull(Isbn.toIsbn10("9791234567896"))
        assertNull(Isbn.toIsbn13("1234567890"))
    }

    @Test
    fun `extract keeps only valid distinct isbns`() {
        val raw = "978-0-306-40615-7, 0306406152; 9780306406158 | B00X4WHP5E\n0-306-40615-2"
        assertEquals(listOf("9780306406157", "0306406152"), Isbn.extract(raw))
    }
}
