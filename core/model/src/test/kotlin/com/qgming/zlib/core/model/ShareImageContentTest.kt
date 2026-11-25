package com.qgming.zlib.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareImageContentTest {
    @Test
    fun `booklist snapshots contain at most six loaded books and retain unknown totals`() {
        val content = ShareImageContent(ShareImageKind.Booklist, "Reading", (1..8).map(::book)).bounded()
        assertEquals((1..6).map { "Book $it" }, content.books.map(ShareImageBook::title))
        assertNull(content.totalBooks)
        assertTrue(content.previewIsPartial)
        assertFalse(content.copy(books = content.books.take(2), previewIsPartial = false).bounded().previewIsPartial)
        assertEquals(20, content.copy(totalBooks = 20).bounded().totalBooks)
        assertNull(content.copy(totalBooks = -1).bounded().totalBooks)
        assertNull(content.copy(totalBooks = 3).bounded().totalBooks)
    }

    @Test
    fun `bounded text removes controls links private paths and caps every display field`() {
        val content = ShareImageContent(
            ShareImageKind.Book,
            "  Title\u0000\u202e\nAuthor  " + "x".repeat(500),
            listOf(ShareImageBook("T".repeat(500), "A".repeat(500), "https://covers.example.test/book.jpg")),
            fields = (1..15).map { ShareImageField("Label".repeat(50), "Value".repeat(100)) },
            description = "\u0000First\r\nSecond\tline\n\n\nhttps://reader.example.test/book?token=synthetic " +
                "content://downloads/private file:///storage/private h\u200bttps://reader.example.test/?token=hidden " + "文".repeat(2_000),
            totalBooks = 12,
            previewIsPartial = true,
        ).bounded()
        assertTrue(content.title.length <= ShareImageContent.MAX_TITLE_LENGTH)
        assertTrue(content.title.startsWith("Title Author"))
        assertTrue(content.books.single().title.length <= ShareImageContent.MAX_TITLE_LENGTH)
        assertTrue(content.books.single().authors.length <= ShareImageContent.MAX_AUTHORS_LENGTH)
        assertEquals(ShareImageContent.MAX_FIELDS, content.fields.size)
        assertTrue(content.fields.all { it.label.length <= ShareImageContent.MAX_FIELD_LABEL_LENGTH && it.value.length <= ShareImageContent.MAX_FIELD_VALUE_LENGTH })
        assertTrue(content.description.length <= ShareImageContent.MAX_DESCRIPTION_LENGTH)
        assertTrue(content.description.startsWith("First\nSecond line\n\n"))
        assertFalse(content.description.contains("synthetic"))
        assertFalse(content.description.contains("private"))
        assertFalse(content.description.contains("hidden"))
        assertFalse(content.description.any { it.isISOControl() && it != '\n' })
        assertNull(content.totalBooks)
        assertFalse(content.previewIsPartial)
        assertEquals(content, content.bounded())
    }

    @Test
    fun `cover URLs allow only unsigned HTTPS image locations`() {
        val unsafe = listOf(
            "http://covers.example.test/book.jpg", "file:///private/book.jpg", "content://private/book.jpg",
            "https://user:password@covers.example.test/book.jpg", "https://covers.example.test:8443/book.jpg",
            "https://covers.example.test/book.jpg?signature=synthetic", "https://covers.example.test/book.jpg#key",
            "https://covers.example.test/book.jpg?", "https://covers.example.test/book.jpg#",
            "https://covers.example.test", "https://covers.example.test/", "//covers.example.test/book.jpg",
            "https://covers.example.test/book\n.jpg", "https://covers.example.test\\book.jpg",
        )
        for (url in unsafe) {
            val safe = ShareImageContent(ShareImageKind.Book, "Book", listOf(book(1).copy(coverUrl = url))).bounded()
            assertEquals("", safe.books.single().coverUrl, url)
        }
        val allowed = "https://covers.example.test:443/path/book.jpg"
        assertEquals(allowed, ShareImageContent(ShareImageKind.Book, "Book", listOf(book(1).copy(coverUrl = allowed))).bounded().books.single().coverUrl)
    }

    @Test
    fun `filenames are a bounded single PNG filename including reserved and empty titles`() {
        for (title in listOf("../../book:<>\"/\\|?*", "CON", "LPT1.txt", ".PNG", " . ", "书".repeat(200), "😀".repeat(100))) {
            val filename = shareImageFilename(ShareImageContent(ShareImageKind.Book, title, listOf(book(1))))
            assertTrue(filename.endsWith(".png"))
            assertTrue(filename.length <= 80)
            assertFalse(filename.any { it in "<>:\"/\\|?*" || it.isISOControl() })
            assertFalse(filename.startsWith('.'))
            assertTrue(filename.removeSuffix(".png").isNotBlank())
        }
        assertEquals("_CON.png", shareImageFilename(ShareImageContent(ShareImageKind.Book, "CON", emptyList())))
        assertEquals("Reading.png", shareImageFilename(ShareImageContent(ShareImageKind.Booklist, "Reading.PNG", emptyList())))
        assertEquals("booklist-share.png", shareImageFilename(ShareImageContent(ShareImageKind.Booklist, " . ", emptyList())))
    }

    @Test
    fun `truncation never splits an emoji surrogate pair`() {
        val safe = ShareImageContent(ShareImageKind.Book, "x".repeat(199) + "😀", listOf(book(1))).bounded()
        assertEquals(199, safe.title.length)
        assertFalse(safe.title.last().isHighSurrogate())
    }

    private fun book(index: Int) = ShareImageBook("Book $index", "Author $index", "https://covers.example.test/$index.jpg")
}
