package com.qgming.zlib.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedViewCapabilitiesTest {
    @Test
    fun `explicit coordinate token wins over description`() {
        assertEquals(SharedViewCoordinates.SCREEN, SharedViewCapabilities.coordinates("rect|SCREEN|WINDOW mentioned in description"))
        assertEquals(SharedViewCoordinates.WINDOW, SharedViewCapabilities.coordinates("rect| window |bounds"))
    }

    @Test
    fun `old metadata and missing metadata use documented defaults`() {
        assertEquals(SharedViewCoordinates.SCREEN, SharedViewCapabilities.coordinates(null))
        assertEquals(SharedViewCoordinates.SCREEN, SharedViewCapabilities.coordinates("rect"))
        assertEquals(SharedViewCoordinates.WINDOW, SharedViewCapabilities.coordinates("rect|View bounds in WINDOW"))
    }

    @Test
    fun `missing capability never enables a return type`() {
        assertFalse(SharedViewCapabilities.supportsAnimation(null, "VIEW_ANIM_FADE_OUT"))
        assertFalse(SharedViewCapabilities.supportsAnimation("boolean", "VIEW_ANIM_FADE_OUT"))
        assertTrue(SharedViewCapabilities.supportsAnimation("int", "VIEW_ANIM_FADE_OUT"))
        assertTrue(SharedViewCapabilities.supportsAnimation("int|Back animation type", "VIEW_ANIM_FADE_OUT"))
    }

    @Test
    fun `return and fallback sets are checked independently with exact names`() {
        val returns = "int|VIEW_ANIM_DEFAULT, VIEW_ANIM_FADE_OUT|Back animation"
        val fallback = "int|VIEW_ANIM_DEFAULT,VIEW_ANIM_NONE|Fallback animation"
        assertTrue(SharedViewCapabilities.supportsAnimation(returns, "VIEW_ANIM_FADE_OUT"))
        assertFalse(SharedViewCapabilities.supportsAnimation(fallback, "VIEW_ANIM_FADE_OUT"))
        assertFalse(SharedViewCapabilities.supportsAnimation(returns, "VIEW_ANIM_FADE"))
    }
}
