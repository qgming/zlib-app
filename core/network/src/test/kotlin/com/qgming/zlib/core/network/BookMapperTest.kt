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
