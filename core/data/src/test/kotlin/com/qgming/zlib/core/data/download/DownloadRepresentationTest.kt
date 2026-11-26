package com.qgming.zlib.core.data.download

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class DownloadRepresentationTest {
    private val original = "https://example.test/book.epub?signature=test".toHttpUrl()

    @Test
    fun `resume needs the same origin resource and strong validator`() {
        val saved = assertNotNull(DownloadRepresentation.from(original, "\"current\""))
        assertTrue(saved.matchesResponse(original, "\"current\""))
        assertFalse(saved.matchesResponse(original, "\"different\""))
        assertFalse(saved.matchesResponse(original.newBuilder().host("mirror.test").build(), "\"current\""))
        assertFalse(saved.matchesUrl(original.newBuilder().setQueryParameter("signature", "changed").build()))
        assertFalse(saved.resourceSha256.contains("signature"))
    }

    @Test
    fun `weak invalid and absent validators cannot be saved for resume`() {
        assertNull(DownloadRepresentation.from(original, "W/\"value\""))
        assertNull(DownloadRepresentation.from(original, "value"))
        assertNull(DownloadRepresentation.from(original, null))
    }
}
