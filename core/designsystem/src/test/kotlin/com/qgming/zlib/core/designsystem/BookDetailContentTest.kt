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
            interestScore = 4.5,
            qualityScore = 4.0,
            year = " 2024 ",
            categories = listOf("", " Fiction ", "Literary"),
            extension = "epub",
            sizeBytes = 1_572_864,
        ).detailChips(Locale.US)

        assertEquals(
            listOf(BookDetailField.Rating, BookDetailField.Year, BookDetailField.Format, BookDetailField.FileSize),
            chips.map(BookDetailChip::field),
        )
        assertEquals(listOf("4.5 / 4.0", "2024", "EPUB", "1.5 MB"), chips.map(BookDetailChip::value))
    }

    @Test
    fun `missing and invalid values do not create placeholder chips`() {
        assertTrue(book.copy(interestScore = Double.NaN, qualityScore = -1.0, categories = listOf(" "), sizeBytes = -1).detailChips(Locale.US).isEmpty())
        assertEquals(listOf(BookDetailChip(BookDetailField.Year, "2024")), book.copy(year = "2024").detailChips(Locale.US))
    }

    @Test
    fun `scores and byte sizes display with the supplied locale`() {
        assertEquals(listOf("4,5 / 4,0", "1,5 MB"), book.copy(interestScore = 4.5, qualityScore = 4.0, sizeBytes = 1_572_864).detailChips(Locale.FRANCE).map(BookDetailChip::value))
        assertEquals("2 KB", formatBookFileSize(2_048, Locale.US))
        assertEquals("400 B", formatBookFileSize(400, Locale.US))
    }

    @Test
    fun `each score keeps its meaning when the other is absent or invalid`() {
        assertEquals("4.5 / —", book.copy(interestScore = 4.5).ratings(Locale.US)?.text)
        assertEquals("— / 3.0", book.copy(interestScore = Double.POSITIVE_INFINITY, qualityScore = 3.0).ratings(Locale.US)?.text)
        assertEquals("0.0 / 0.0", book.copy(interestScore = 0.0, qualityScore = 0.0).ratings(Locale.US)?.text)
        assertTrue(book.copy(rating = 4.5).detailChips(Locale.US).isEmpty())
    }

    @Test
    fun `search shows five inline chips with ratings first and localized content language`() {
        val result = book.copy(interestScore = 4.5, qualityScore = 4.0, year = "2024", language = "english", extension = "epub", sizeBytes = 2048)
        val chinese = result.detailChips(Locale.US, search = true, languageLocale = Locale.SIMPLIFIED_CHINESE)
        assertEquals(listOf(BookDetailField.Rating, BookDetailField.Year, BookDetailField.Language, BookDetailField.Format, BookDetailField.FileSize), chinese.map(BookDetailChip::field))
        assertEquals(listOf("4.5 / 4.0", "2024", "英语", "EPUB", "2 KB"), chinese.map(BookDetailChip::value))
        assertEquals("English", result.detailChips(Locale.US, search = true).first { it.field == BookDetailField.Language }.value)
        assertEquals(listOf(BookDetailField.Rating, BookDetailField.Year, BookDetailField.Format, BookDetailField.FileSize), result.detailChips(Locale.US).map(BookDetailChip::field))
        assertTrue(book.detailChips(Locale.US, search = true).isEmpty())
    }

    @Test
    fun `empty and numeric zero years are omitted without hiding zero scores or sizes`() {
        for (year in listOf("", " ", "0", " 0 ", "0000", "0.0", "-0", "0.00")) {
            val result = book.copy(interestScore = 0.0, qualityScore = 0.0, year = year, sizeBytes = 0)
            for (search in listOf(false, true)) {
                val chips = result.detailChips(Locale.US, search = search)
                assertEquals(listOf(BookDetailField.Rating, BookDetailField.FileSize), chips.map(BookDetailChip::field), "year=$year, search=$search")
                assertEquals(listOf("0.0 / 0.0", "0 B"), chips.map(BookDetailChip::value))
            }
        }
    }

    @Test
    fun `known publication years keep their text in search and detail`() {
        for (year in listOf("2024", "0042", "2024–2025", "约 2024")) {
            val result = book.copy(year = " $year ")
            for (search in listOf(false, true)) {
                assertEquals(listOf(BookDetailChip(BookDetailField.Year, year)), result.detailChips(Locale.US, search = search))
            }
        }
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
