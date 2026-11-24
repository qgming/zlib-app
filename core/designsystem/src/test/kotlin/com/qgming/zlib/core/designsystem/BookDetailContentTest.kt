package com.qgming.zlib.core.designsystem

import com.qgming.zlib.core.model.Book
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookDetailContentTest {
    private val book = Book(id = "42", hash = "test", title = "Book")

    @Test
    fun `chips keep reading order and show the current file attributes separately`() {
        val chips = book.copy(
            rating = 4.5,
            year = " 2024 ",
            categories = listOf("", " Fiction ", "Literary"),
            extension = "epub",
            sizeBytes = 1_572_864,
        ).detailChips(Locale.US)

        assertEquals(
            listOf(BookDetailField.Rating, BookDetailField.Year, BookDetailField.Format, BookDetailField.FileSize),
            chips.map(BookDetailChip::field),
        )
        assertEquals(listOf("4.5", "2024", "EPUB", "1.5 MB"), chips.map(BookDetailChip::value))
    }

    @Test
    fun `missing and invalid values do not create placeholder chips`() {
        assertTrue(book.copy(rating = Double.NaN, categories = listOf(" "), sizeBytes = -1).detailChips(Locale.US).isEmpty())
        assertEquals(listOf(BookDetailChip(BookDetailField.Year, "2024")), book.copy(year = "2024").detailChips(Locale.US))
    }

    @Test
    fun `scores and byte sizes display with the supplied locale`() {
        assertEquals(listOf("4,5", "1,5 MB"), book.copy(rating = 4.5, sizeBytes = 1_572_864).detailChips(Locale.FRANCE).map(BookDetailChip::value))
        assertEquals("2 KB", formatBookFileSize(2_048, Locale.US))
        assertEquals("400 B", formatBookFileSize(400, Locale.US))
    }

    @Test
    fun `a book with only badge information has no publication card`() {
        assertFalse(book.copy(rating = 4.0, year = "2024", extension = "epub", sizeBytes = 2_048, categories = listOf(" ")).hasPublicationInfo)
        assertTrue(book.copy(publisher = "Press").hasPublicationInfo)
        assertTrue(book.copy(pages = 320).hasPublicationInfo)
        assertTrue(book.copy(categories = listOf("Fiction")).hasPublicationInfo)
    }

    @Test
    fun `publication fields keep every available value without duplicating the year`() {
        val fields = book.copy(
            publisher = " Example Press ",
            year = "2024",
            language = "English",
            edition = "2nd",
            pages = 320,
            isbn10 = "0141439513",
            isbn13 = "9780141439518",
            categories = listOf(" Fiction > Classic ", " ", "Literary"),
        ).publicationFields()

        assertEquals(
            listOf(
                PublicationField.Publisher to "Example Press",
                PublicationField.Language to "English",
                PublicationField.Edition to "2nd",
                PublicationField.Pages to "320",
                PublicationField.Isbn10 to "0141439513",
                PublicationField.Isbn13 to "9780141439518",
                PublicationField.Categories to "Fiction > Classic / Literary",
            ),
            fields,
        )
    }
}
