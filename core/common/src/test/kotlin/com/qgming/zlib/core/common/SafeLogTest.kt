package com.qgming.zlib.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafeLogTest {

    @Test
    fun `download url query is dropped`() {
        val url = "https://cdn.example.com/file/book.epub?token=secret&expires=1"
        assertEquals("https://cdn.example.com/file/book.epub?<redacted>", SafeLog.redactUrl(url))
        assertEquals("https://cdn.example.com/file", SafeLog.redactUrl("https://cdn.example.com/file"))
        assertEquals("https://z-lib.gd/x", SafeLog.redactUrl("https://user:pw@z-lib.gd/x"))
    }

    @Test
    fun `sensitive headers are masked`() {
        assertEquals("***", SafeLog.redactHeader("remix-userkey", "abc"))
        assertEquals("remix_userid=***", SafeLog.redactHeader("Cookie", "remix_userid=1"))
        assertEquals(
            "remix_userid=***; remix_userkey=***",
            SafeLog.redactHeader("Cookie", "remix_userid=1; remix_userkey=abc"),
        )
        assertEquals("application/json", SafeLog.redactHeader("Accept", "application/json"))
    }

    @Test
    fun `free form text redacts credentials`() {
        val text = "password=hunter2 remix_userkey: abc123 cookie=remix_userkey=zzz"
        val redacted = SafeLog.redactText(text)
        assertFalse(redacted.contains("hunter2"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("zzz"))
        assertTrue(redacted.contains("***"))
    }
}
