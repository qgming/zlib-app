package com.qgming.zlib.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FileNamesTest {

    @Test
    fun `path components and illegal characters are removed`() {
        assertEquals("passwd_.epub", FileNames.sanitize("../../etc/passwd?.epub"))
        assertEquals("book__name_.epub", FileNames.sanitize("book<>name?.epub"))
        assertEquals("normal title.epub", FileNames.sanitize("  normal   title.epub  "))
    }

    @Test
    fun `blank and traversal names fall back`() {
        assertEquals("download", FileNames.sanitize(null))
        assertEquals("download", FileNames.sanitize("   "))
        assertEquals("download", FileNames.sanitize(".."))
        assertEquals("book", FileNames.sanitize("book..."))
    }

    @Test
    fun `long names keep the extension`() {
        val base = "a".repeat(300)
        val name = FileNames.sanitize("$base.epub")
        assertEquals(120 + ".epub".length, name.length)
        assertEquals("epub", name.substringAfterLast('.'))
    }

    @Test
    fun `multibyte names are bounded in utf8 bytes without splitting characters`() {
        val cjk = "书".repeat(120)
        val name = FileNames.sanitize("$cjk.epub")
        val base = name.substringBeforeLast('.')
        assertEquals("epub", name.substringAfterLast('.'))
        assert(base.toByteArray(Charsets.UTF_8).size <= 180)
        assert(base.length == 60)
    }

    @Test
    fun `leading dots and oversized extensions are sanitized`() {
        assertEquals("hidden.epub", FileNames.sanitize("...hidden.epub"))
        assertEquals("download", FileNames.sanitize(".   ."))
        val longExt = "x".repeat(40)
        val name = FileNames.sanitize("book.$longExt")
        assertEquals(16, name.substringAfterLast('.').length)
    }

    @Test
    fun `extension is appended once`() {
        assertEquals("book.epub", FileNames.withExtension("book", "EPUB"))
        assertEquals("book.epub", FileNames.withExtension("book.epub", "epub"))
        assertEquals("book.tar", FileNames.withExtension("book.tar", null))
    }

    @Test
    fun `content disposition plain and rfc5987`() {
        assertEquals("book.epub", FileNames.fromContentDisposition("attachment; filename=\"book.epub\""))
        assertEquals("book.epub", FileNames.fromContentDisposition("attachment; filename=book.epub"))
        assertEquals("书名.epub", FileNames.fromContentDisposition("attachment; filename*=UTF-8''%E4%B9%A6%E5%90%8D.epub"))
        assertNull(FileNames.fromContentDisposition(null))
        assertNull(FileNames.fromContentDisposition("attachment"))
    }
}
