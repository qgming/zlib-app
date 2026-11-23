package com.qgming.zlib.core.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DownloadFileNamesTest {
    @Test
    fun `part name is derived from the complete reference and is stable`() {
        val first = DownloadFileNames.partName("7:abc123")
        val second = DownloadFileNames.partName("7:abc123")

        assertEquals(first, second)
        assertTrue(first.startsWith("zlib-7-"), first)
        assertTrue(first.endsWith(".part"), first)
        assertTrue(first.length <= 80, first)
    }

    @Test
    fun `different hashes of the same book never share a part file`() {
        assertNotEquals(
            DownloadFileNames.partName("7:epub"),
            DownloadFileNames.partName("7:pdf"),
        )
        assertNotEquals(
            DownloadFileNames.partName("7:abc"),
            DownloadFileNames.partName("8:abc"),
        )
    }

    @Test
    fun `otherwise unsafe references still yield distinct safe names`() {
        val first = DownloadFileNames.partName("../../evil:hash/one")
        val second = DownloadFileNames.partName("../../evil:hash/two")

        assertNotEquals(first, second)
        assertTrue(first.none { it == '/' || it == ':' }, first)
        assertTrue(first.endsWith(".part"), first)
    }

    @Test
    fun `final name keeps the readable title when it is free`() {
        val name = DownloadFileNames.uniqueFinalName("A Book.epub", exists = { false })
        assertEquals("A Book.epub", name)
    }

    @Test
    fun `final name walks upward without overwriting`() {
        val existing = setOf("A Book.epub", "A Book (2).epub")
        val name = DownloadFileNames.uniqueFinalName("A Book.epub", exists = existing::contains)
        assertEquals("A Book (3).epub", name)
    }

    @Test
    fun `final name handles names without an extension`() {
        val name = DownloadFileNames.uniqueFinalName("A Book", exists = { it == "A Book" })
        assertEquals("A Book (2)", name)
    }

    @Test
    fun `blank base falls back to a usable suffix`() {
        val name = DownloadFileNames.uniqueFinalName("", fallbackSuffix = "book-7", exists = { false })
        assertEquals("book-7", name)
    }

    @Test
    fun `existing names are never returned even after many collisions`() {
        val existing = buildSet {
            add("Title.pdf")
            for (index in 2L..50L) add("Title ($index).pdf")
        }
        val name = DownloadFileNames.uniqueFinalName("Title.pdf", exists = existing::contains)
        assertEquals("Title (51).pdf", name)
    }

    @Test
    fun `final name prefers content disposition over the url and fallback`() {
        val name = DownloadFileNames.finalName(
            contentDisposition = "attachment; filename=\"From Header.epub\"",
            urlSegment = "from-url.pdf",
            fallback = "Title.epub",
            extension = "epub",
        )
        assertEquals("From Header.epub", name)
    }

    @Test
    fun `final name decodes an RFC 5987 disposition`() {
        val name = DownloadFileNames.finalName(
            contentDisposition = "attachment; filename*=UTF-8''%E4%B8%AD%E6%96%87.epub",
            urlSegment = "ignored.epub",
            fallback = "Title",
            extension = "epub",
        )
        assertEquals("中文.epub", name)
    }

    @Test
    fun `final name uses the last url segment when the header is absent`() {
        val name = DownloadFileNames.finalName(
            contentDisposition = null,
            urlSegment = "CDN Book.fb2",
            fallback = "Title",
            extension = "fb2",
        )
        assertEquals("CDN Book.fb2", name)
    }

    @Test
    fun `final name uses the last url segment when the header filename is blank`() {
        val name = DownloadFileNames.finalName(
            contentDisposition = "attachment; filename=\"\"",
            urlSegment = "From URL.epub",
            fallback = "Title",
            extension = "epub",
        )
        assertEquals("From URL.epub", name)
    }

    @Test
    fun `final name falls back to the title name`() {
        assertEquals(
            "Title.epub",
            DownloadFileNames.finalName(null, null, "Title", "epub"),
        )
    }

    @Test
    fun `final name adds the selected extension when the source name has none`() {
        assertEquals("book.pdf", DownloadFileNames.finalName(null, "book", "Title", "pdf"))
        assertEquals("download.pdf", DownloadFileNames.finalName(null, null, "", "pdf"))
    }

    @Test
    fun `final name sanitizes path traversal and illegal characters`() {
        val name = DownloadFileNames.finalName(
            contentDisposition = "attachment; filename=\"../../evil:name?.pdf\"",
            urlSegment = "ignored.pdf",
            fallback = "Title.epub",
            extension = "pdf",
        )
        assertEquals("evil_name_.pdf", name)
    }
}
