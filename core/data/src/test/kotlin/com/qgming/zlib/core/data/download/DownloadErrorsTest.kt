package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadErrorsTest {
    @Test
    fun `a JSON gateway failure preserves HTTP status for retry`() {
        val error = downloadHttpError(503, eapi = true, body = """{"error":"gateway unavailable"}""")
        assertEquals(503, assertIs<AppError.Server>(error).code)
    }

    @Test
    fun `only an EAPI failure can mark an account expired or out of quota`() {
        val expired = """{"success":0,"error":"Please login"}"""
        val quota = """{"success":0,"error":"Daily limit reached"}"""
        assertEquals(AppError.SessionExpired, downloadHttpError(400, eapi = true, body = expired))
        assertIs<AppError.Quota>(downloadHttpError(400, eapi = true, body = quota))
        assertEquals(403, assertIs<AppError.Server>(downloadHttpError(403, eapi = false, body = expired)).code)
        assertEquals(403, assertIs<AppError.Server>(downloadHttpError(403, eapi = false, body = quota)).code)
    }
}
