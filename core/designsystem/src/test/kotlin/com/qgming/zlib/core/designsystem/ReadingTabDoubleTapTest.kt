package com.qgming.zlib.core.designsystem

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadingTabDoubleTapTest {
    @Test
    fun `only the second reading selection within the interval returns to top`() {
        val taps = ReadingTabDoubleTap(300)
        assertFalse(taps.register(reading = true, timestampMillis = 1_000))
        assertTrue(taps.register(reading = true, timestampMillis = 1_200))
        assertFalse(taps.register(reading = true, timestampMillis = 1_250))
    }

    @Test
    fun `switching destinations interrupts a reading double tap`() {
        val taps = ReadingTabDoubleTap(300)
        assertFalse(taps.register(reading = true, timestampMillis = 1_000))
        assertFalse(taps.register(reading = false, timestampMillis = 1_050))
        assertFalse(taps.register(reading = true, timestampMillis = 1_100))
        assertTrue(taps.register(reading = true, timestampMillis = 1_300))
    }

    @Test
    fun `slow taps remain normal selections and a fresh pair still works`() {
        val taps = ReadingTabDoubleTap(300)
        assertFalse(taps.register(reading = true, timestampMillis = 1_000))
        assertFalse(taps.register(reading = true, timestampMillis = 1_301))
        assertTrue(taps.register(reading = true, timestampMillis = 1_601))
    }
}
