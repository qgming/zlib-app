package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class BookMapperTest {

    @Test
    fun `scores map independently from numeric and string fields and language stays raw`() {
        val book = BookMapper.fromJson(buildJsonObject {
            put("id", 1); put("hash", "abc123")
            put("interestScore", "4.5"); put("qualityScore", 3.0)
            put("language", "traditional chinese")
        })!!
        assertEquals(4.5, book.interestScore)
        assertEquals(3.0, book.qualityScore)
        assertEquals("traditional chinese", book.language)
    }

    @Test
    fun `missing invalid and negative scores never fall back to the other score`() {
        for (invalid in listOf("NaN", "Infinity", "-1", "invalid", "")) {
            val book = BookMapper.fromJson(buildJsonObject {
                put("id", 1); put("hash", "abc123")
                put("interestScore", invalid); put("qualityScore", "4.0")
            })!!
            assertNull(book.interestScore, invalid)
            assertEquals(4.0, book.qualityScore)
        }
        val book = BookMapper.fromJson(buildJsonObject {
            put("id", 1); put("hash", "abc123"); put("interestScore", 0)
        })!!
        assertEquals(0.0, book.interestScore)
        assertNull(book.qualityScore)
    }

    @Test
    fun `hash path traversal is rejected`() {
        val obj = buildJsonObject {
            put("id", "1")
            put("hash", "../../user/book/1/delete")
            put("title", "bad")
        }
        assertNull(BookMapper.fromJson(obj))
    }

    @Test
    fun `isbn fields keep their correct lengths`() {
        val obj = buildJsonObject {
            put("id", "1")
            put("hash", "abc123")
            put("title", "book")
            put("isbn13", "0306406152")
        }
        val book = BookMapper.fromJson(obj)!!
        assertEquals("0306406152", book.isbn10)
        assertEquals("9780306406157", book.isbn13)
    }

    @Test
    fun `categories as a plain string are split`() {
        val obj = buildJsonObject {
            put("id", "1")
            put("hash", "abc123")
            put("title", "book")
            put("categories", "Fiction; Classic")
        }
        assertEquals(listOf("Fiction", "Classic"), BookMapper.fromJson(obj)!!.categories)
    }

    @Test
    fun `unusable covers are omitted so details can supply the actual collection`() {
        val covers = listOf(
            "https://covers.example/collections//abc.jpg",
            "/img/cover-not-exists.png",
            "https://covers.example/img/cover-not-exists.png",
            "http://covers.example/abc.jpg",
            "https://user:key@covers.example/abc.jpg",
            "https://127.0.0.1/abc.jpg",
            "https://localhost/abc.jpg",
            "https://covers.example:8443/abc.jpg",
            "https://[::1]/abc.jpg",
        )
        covers.forEach { cover ->
            val book = BookMapper.fromJson(buildJsonObject {
                put("id", "1")
                put("hash", "abc")
                put("cover", cover)
            })!!
            assertEquals("", book.coverUrl, cover)
        }
        val cover = "https://covers.example/collections/userbooks/abc.jpg"
        val book = BookMapper.fromJson(buildJsonObject {
            put("id", "1")
            put("hash", "abc")
            put("cover", cover)
        })!!
        assertEquals(cover, book.coverUrl)
    }

    @Test
    fun `cookie importer rejects control characters`() {
        val result = CookieImporter.parse("""{"remix_userid":"1","remix_userkey":"a\nb"}""")
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(result).error)
    }

    @Test
    fun `cookie importer accepts plain header`() {
        val result = CookieImporter.parse("remix_userid=1; remix_userkey=abc")
        val imported = assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(result).value
        assertEquals("1", imported.remixUserId)
        assertEquals("abc", imported.remixUserKey)
        assertTrue(imported.cookies.size >= 2)
    }
}
