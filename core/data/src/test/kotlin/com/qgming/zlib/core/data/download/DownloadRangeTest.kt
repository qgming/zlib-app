package com.qgming.zlib.core.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DownloadRangeTest {
    @Test
    fun `unknown and negative stat sizes never produce a range offset`() {
        assertEquals(0L, DownloadRange.resumeOffset(null))
        assertEquals(0L, DownloadRange.resumeOffset(-1))
        assertEquals(0L, DownloadRange.resumeOffset(0))
        assertEquals(42L, DownloadRange.resumeOffset(42))
        assertNull(DownloadRange.requestHeader(0))
        assertNull(DownloadRange.requestHeader(-5))
        assertEquals("bytes=42-", DownloadRange.requestHeader(42))
    }

    @Test
    fun `content range parsing accepts a single segment with or without total`() {
        assertEquals(ContentRange(0, 99, 100), DownloadRange.parseContentRange("bytes 0-99/100"))
        assertEquals(ContentRange(100, 199, null), DownloadRange.parseContentRange("bytes 100-199/*"))
        assertEquals(ContentRange(5, 5, 6), DownloadRange.parseContentRange("BYTES 5-5/6"))
    }

    @Test
    fun `content range parsing rejects malformed or inconsistent segments`() {
        assertNull(DownloadRange.parseContentRange(null))
        assertNull(DownloadRange.parseContentRange(""))
        assertNull(DownloadRange.parseContentRange("bytes 100-199"))
        assertNull(DownloadRange.parseContentRange("items 0-99/100"))
        assertNull(DownloadRange.parseContentRange("bytes 100-99/200"))
        assertNull(DownloadRange.parseContentRange("bytes 0-99/99"))
        assertNull(DownloadRange.parseContentRange("bytes -1-99/100"))
        assertNull(DownloadRange.parseContentRange("bytes 0-99/abc"))
    }

    @Test
    fun `a 200 response always restarts from the beginning`() {
        val check = DownloadRange.evaluate(200, null, contentLength = 1234, requestedOffset = 800)
        assertEquals(DownloadCheck.Restart(1234), check)
        assertEquals(DownloadCheck.Restart(null), DownloadRange.evaluate(200, null, -1, 800))
    }

    @Test
    fun `a matching 206 continues at the requested offset`() {
        val check = DownloadRange.evaluate(
            status = 206,
            contentRangeHeader = "bytes 800-999/1234",
            contentLength = 200,
            requestedOffset = 800,
        )
        val append = assertIs<DownloadCheck.Append>(check)
        assertEquals(ContentRange(800, 999, 1234), append.range)
    }

    @Test
    fun `a 206 starting anywhere else is rejected`() {
        val check = DownloadRange.evaluate(
            status = 206,
            contentRangeHeader = "bytes 700-999/1234",
            contentLength = 300,
            requestedOffset = 800,
        )
        assertIs<DownloadCheck.Invalid>(check)
    }

    @Test
    fun `a 206 without a usable content range is rejected`() {
        assertIs<DownloadCheck.Invalid>(
            DownloadRange.evaluate(206, null, contentLength = 100, requestedOffset = 100),
        )
        assertIs<DownloadCheck.Invalid>(
            DownloadRange.evaluate(206, "bytes */1000", contentLength = 0, requestedOffset = 100),
        )
    }

    @Test
    fun `a 206 whose response length disagrees with content range is rejected`() {
        val check = DownloadRange.evaluate(
            status = 206,
            contentRangeHeader = "bytes 100-199/1000",
            contentLength = 50,
            requestedOffset = 100,
        )
        assertIs<DownloadCheck.Invalid>(check)
    }

    @Test
    fun `multipart and unsupported statuses are rejected`() {
        assertIs<DownloadCheck.Invalid>(
            DownloadRange.evaluate(
                status = 206,
                contentRangeHeader = "bytes 0-9/10",
                contentLength = 10,
                requestedOffset = 0,
                contentType = "multipart/byteranges; boundary=x",
            ),
        )
        assertIs<DownloadCheck.Invalid>(DownloadRange.evaluate(418, null, 0, 0))
    }

    @Test
    fun `completion requires the exact append segment and the declared total`() {
        val append = DownloadCheck.Append(ContentRange(100, 199, 200))
        assertEquals(CompletionCheck.Complete, DownloadRange.completion(append, 100, 100))
        assertEquals(CompletionCheck.Short(100, 50), DownloadRange.completion(append, 100, 50))
        assertEquals(CompletionCheck.Mismatch(100, 120), DownloadRange.completion(append, 100, 120))
    }

    @Test
    fun `an append that ends before the declared total is not complete`() {
        val partial = DownloadCheck.Append(ContentRange(100, 149, 200))
        assertEquals(CompletionCheck.Short(200, 150), DownloadRange.completion(partial, 100, 50))
    }

    @Test
    fun `an append with an unknown total cannot be declared complete`() {
        val append = DownloadCheck.Append(ContentRange(100, 199, null))
        assertEquals(CompletionCheck.UnknownTotal(200), DownloadRange.completion(append, 100, 100))
    }

    @Test
    fun `a 200 body must match its declared length to complete`() {
        assertEquals(CompletionCheck.Complete, DownloadRange.completion(DownloadCheck.Restart(100), 50, 100))
        assertEquals(CompletionCheck.Short(100, 40), DownloadRange.completion(DownloadCheck.Restart(100), 50, 40))
        assertEquals(
            CompletionCheck.UnknownTotal(40),
            DownloadRange.completion(DownloadCheck.Restart(null), 0, 40),
        )
    }
}
