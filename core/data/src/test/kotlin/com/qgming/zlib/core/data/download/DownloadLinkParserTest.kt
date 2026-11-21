package com.qgming.zlib.core.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadLinkParserTest {

    @Test
    fun `extracts downloadLink from file object`() {
        val body = """
            {"success":1,"file":{"downloadLink":"https://cdn.example/book?token=x",
            "description":"x","author":"y","extension":"epub","allowDownload":true}}
        """.trimIndent()
        assertEquals("https://cdn.example/book?token=x", DownloadLinkParser.extract(body))
    }

    @Test
    fun `extracts plain file url`() {
        assertEquals(
            "https://cdn.example/book.epub",
            DownloadLinkParser.extract("""{"success":1,"file":"https://cdn.example/book.epub"}"""),
        )
    }

    @Test
    fun `refuses when download is not allowed`() {
        assertNull(
            DownloadLinkParser.extract(
                """{"success":1,"file":{"downloadLink":"https://x","allowDownload":false}}""",
            ),
        )
    }

    @Test
    fun `refuses error and garbage bodies`() {
        assertNull(DownloadLinkParser.extract("""{"success":0,"error":"Daily limit reached"}"""))
        assertNull(DownloadLinkParser.extract("not json"))
        assertNull(DownloadLinkParser.extract("""{"success":1,"file":null}"""))
    }
}
