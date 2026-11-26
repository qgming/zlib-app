package com.qgming.zlib.share

import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.ShareImageBook
import com.qgming.zlib.core.model.ShareImageContent
import com.qgming.zlib.core.model.ShareImageField
import com.qgming.zlib.core.model.ShareImageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ShareImagePayloadTest {
    @Test
    fun `booklist mapping shares display data only and preserves an unknown total`() {
        val books = (1..8).map { index -> Book(
            id = "private-id-$index",
            hash = "private-hash-$index",
            title = "Book $index",
            authors = "Author $index",
            coverUrl = if (index == 1) "https://covers.example.test/1.jpg?signature=synthetic" else "https://covers.example.test/$index.jpg",
            href = "https://reader.example.test/private?key=synthetic",
            localUri = "content://private/$index",
        ) }
        val content = ShareImagePayload.booklist(CloudBooklist("private-list-id", "Reading", null), books, hasMore = false)
        assertEquals(6, content.books.size)
        assertNull(content.totalBooks)
        assertTrue(content.previewIsPartial)
        assertEquals("", content.books.first().coverUrl)
        val encoded = ShareImagePayload.encode(content)
        for (excluded in listOf("private-id", "private-hash", "private-list-id", "content://", "reader.example", "signature", "synthetic")) {
            assertFalse(encoded.contains(excluded), excluded)
        }
        assertEquals(content, ShareImagePayload.decode(encoded))
    }

    @Test
    fun `preview partial state follows more pages and known total without inventing a count`() {
        val books = listOf(Book("1", "hash", "First"))
        val list = CloudBooklist("42", "Reading", null)
        assertFalse(ShareImagePayload.booklist(list, books, hasMore = false).previewIsPartial)
        assertTrue(ShareImagePayload.booklist(list, books, hasMore = true).previewIsPartial)
        val known = ShareImagePayload.booklist(list.copy(bookCount = 20), books, hasMore = false)
        assertEquals(20, known.totalBooks)
        assertTrue(known.previewIsPartial)
        assertNull(ShareImagePayload.booklist(list.copy(bookCount = -1), books, hasMore = false).totalBooks)
        assertNull(ShareImagePayload.booklist(list.copy(bookCount = 0), books, hasMore = false).totalBooks)
    }

    @Test
    fun `bounded snapshot round trips within the UTF8 activity payload budget`() {
        val content = ShareImageContent(
            kind = ShareImageKind.Book,
            title = "书".repeat(1_000),
            books = listOf(ShareImageBook("书".repeat(1_000), "作者".repeat(1_000), "https://covers.example.test/book.jpg")),
            fields = (1..30).map { ShareImageField("字段".repeat(100), "值".repeat(1_000)) },
            description = "简介".repeat(5_000),
            rating = "4.8",
        )
        val encoded = ShareImagePayload.encode(content)
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= ShareImagePayload.MAX_PAYLOAD_BYTES)
        assertEquals(content.bounded(), ShareImagePayload.decode(encoded))
        assertEquals("4.8", ShareImagePayload.decode(encoded)?.rating)
        assertTrue(ShareImagePayload.filename(content).length <= 80)
    }

    @Test
    fun `reader accepts legacy payloads without rating and rejects malformed rating`() {
        val book = ShareImageContent(
            kind = ShareImageKind.Book,
            title = "Sample",
            books = listOf(ShareImageBook("Sample", "Author", "https://covers.example.test/1.jpg")),
            rating = "4.5",
        )
        val root = Json.parseToJsonElement(ShareImagePayload.encode(book)) as JsonObject
        val withoutRating = JsonObject(root - "rating").toString()
        assertEquals("", ShareImagePayload.decode(withoutRating)?.rating)
        assertNull(ShareImagePayload.decode(JsonObject(root + ("rating" to JsonPrimitive("x".repeat(17)))).toString()))
        assertNull(ShareImagePayload.decode(JsonObject(root + ("rating" to JsonPrimitive(4.5))).toString()))

        val list = ShareImagePayload.booklist(CloudBooklist("42", "Reading", null), listOf(Book("1", "hash", "First")), false)
        val listRoot = Json.parseToJsonElement(ShareImagePayload.encode(list)) as JsonObject
        assertNull(ShareImagePayload.decode(JsonObject(listRoot + ("rating" to JsonPrimitive("4.5"))).toString()))
    }

    @Test
    fun `light and dark share palettes provide distinct high-contrast themes`() {
        val light = SharePalette.of(darkTheme = false)
        val dark = SharePalette.of(darkTheme = true)
        assertEquals(SharePalette.Light, light)
        assertEquals(SharePalette.Dark, dark)
        assertTrue(light.canvas != dark.canvas)
        assertTrue(light.surface != dark.surface)
        assertTrue(light.ink != dark.ink)
        assertTrue(light.chipFill != dark.chipFill)
        assertTrue(light.classicCloths.isNotEmpty())
        assertTrue(dark.classicCloths.isNotEmpty())
        // Light canvas is bright and ink is dark; dark canvas is dark and ink is bright.
        assertTrue(luminance(light.canvas) > 0.8)
        assertTrue(luminance(light.ink) < 0.2)
        assertTrue(luminance(dark.canvas) < 0.2)
        assertTrue(luminance(dark.ink) > 0.8)
    }

    private fun luminance(argb: Int): Double {
        val r = ((argb ushr 16) and 0xFF) / 255.0
        val g = ((argb ushr 8) and 0xFF) / 255.0
        val b = (argb and 0xFF) / 255.0
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    @Test
    fun `reader rejects malformed oversized unknown or noncanonical snapshots`() {
        val content = ShareImagePayload.booklist(CloudBooklist("42", "Reading", null), listOf(Book("1", "hash", "First")), false)
        val root = Json.parseToJsonElement(ShareImagePayload.encode(content)) as JsonObject
        val invalid = listOf(
            "not json", "[]", "x".repeat(ShareImagePayload.MAX_PAYLOAD_BYTES + 1),
            JsonObject(root + ("session" to JsonPrimitive("must-not-travel"))).toString(),
            JsonObject(root + ("version" to JsonPrimitive(2))).toString(),
            JsonObject(root + ("version" to JsonPrimitive("1"))).toString(),
            JsonObject(root + ("partial" to JsonPrimitive("false"))).toString(),
            JsonObject(root + ("title" to JsonPrimitive("x".repeat(201)))).toString(),
            JsonObject(root + ("description" to JsonPrimitive("Unexpected booklist field"))).toString(),
            JsonObject(root + ("books" to JsonArray(List(7) { (root.getValue("books") as JsonArray).first() }))).toString(),
            JsonObject(root - "books").toString(),
        )
        invalid.forEach { assertNull(ShareImagePayload.decode(it)) }
    }

    @Test
    fun `reader rejects signed cover locations rather than relaying them`() {
        val content = ShareImagePayload.booklist(CloudBooklist("42", "Reading", null), listOf(Book("1", "hash", "First")), false)
        val root = Json.parseToJsonElement(ShareImagePayload.encode(content)) as JsonObject
        val book = (root.getValue("books") as JsonArray).first() as JsonObject
        val signed = JsonObject(book + ("cover" to JsonPrimitive("https://covers.example.test/1.jpg?key=synthetic")))
        assertNull(ShareImagePayload.decode(JsonObject(root + ("books" to JsonArray(listOf(signed)))).toString()))
    }
}
