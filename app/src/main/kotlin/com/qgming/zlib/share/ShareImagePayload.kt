package com.qgming.zlib.share

import android.content.Context
import android.content.Intent
import android.text.Html
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.designsystem.formatBookFileSize
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.ShareImageBook
import com.qgming.zlib.core.model.ShareImageContent
import com.qgming.zlib.core.model.ShareImageField
import com.qgming.zlib.core.model.ShareImageKind
import com.qgming.zlib.core.model.shareImageFilename
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** A small display snapshot, never a serialized account, Book or entire cloud booklist. */
internal object ShareImagePayload {
    private const val EXTRA_CONTENT = "com.qgming.zlib.share.CONTENT"
    internal const val MAX_PAYLOAD_BYTES = 24 * 1024
    private const val VERSION = 1

    fun book(context: Context, book: Book): ShareImageContent {
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val fields = buildList<ShareImageField> {
            fun field(label: Int, value: String) {
                if (value.isNotBlank()) add(ShareImageField(context.getString(label), value))
            }
            field(TextR.string.text_publisher, book.publisher)
            field(TextR.string.text_publication_year, book.year)
            field(TextR.string.text_language, book.language)
            field(TextR.string.text_edition, book.edition)
            field(TextR.string.text_pages, book.pages?.takeIf { it > 0 }?.toString().orEmpty())
            field(TextR.string.text_isbn10, book.isbn10)
            field(TextR.string.text_isbn13, book.isbn13)
            field(TextR.string.text_categories, book.categories.take(20).joinToString(" / "))
            field(TextR.string.text_current_format, book.extension.uppercase(locale))
            field(TextR.string.text_file_size, book.sizeBytes?.takeIf { it >= 0 }?.let { formatBookFileSize(it, locale) }.orEmpty())
        }
        return ShareImageContent(
            kind = ShareImageKind.Book,
            title = book.title,
            books = listOf(book.displayBook()),
            fields = fields,
            description = plainDescription(book.description),
        ).bounded()
    }

    fun booklist(booklist: CloudBooklist, books: List<Book>, hasMore: Boolean): ShareImageContent = ShareImageContent(
        kind = ShareImageKind.Booklist,
        title = booklist.title,
        books = books.take(ShareImageContent.MAX_BOOKS).map { it.displayBook() },
        totalBooks = booklist.bookCount?.takeIf { it >= books.size },
        previewIsPartial = hasMore || books.size > ShareImageContent.MAX_BOOKS ||
            booklist.bookCount?.let { it > books.size } == true,
    ).bounded()

    fun put(intent: Intent, content: ShareImageContent): Intent = intent.putExtra(EXTRA_CONTENT, encode(content))

    fun read(intent: Intent): ShareImageContent? =
        runCatching { intent.getStringExtra(EXTRA_CONTENT) }.getOrNull()?.let(::decode)

    fun filename(content: ShareImageContent): String = shareImageFilename(content)

    internal fun encode(content: ShareImageContent): String {
        val safe = content.bounded()
        val encoded = buildJsonObject {
            put("version", VERSION)
            put("kind", if (safe.kind == ShareImageKind.Book) "book" else "booklist")
            put("title", safe.title)
            put("books", buildJsonArray {
                safe.books.forEach { book -> add(buildJsonObject {
                    put("title", book.title)
                    put("authors", book.authors)
                    put("cover", book.coverUrl)
                }) }
            })
            put("fields", buildJsonArray {
                safe.fields.forEach { field -> add(buildJsonObject {
                    put("label", field.label)
                    put("value", field.value)
                }) }
            })
            put("description", safe.description)
            safe.totalBooks?.let { put("totalBooks", it) }
            put("partial", safe.previewIsPartial)
        }.toString()
        check(encoded.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES)
        return encoded
    }

    internal fun decode(encoded: String): ShareImageContent? {
        if (encoded.length > MAX_PAYLOAD_BYTES || encoded.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_BYTES) return null
        return try {
            val root = Json.parseToJsonElement(encoded) as? JsonObject ?: return null
            if (!root.keys.all { it in ROOT_KEYS } || !root.keys.containsAll(REQUIRED_KEYS) || root.integer("version") != VERSION) return null
            val kind = when (root.string("kind")) {
                "book" -> ShareImageKind.Book
                "booklist" -> ShareImageKind.Booklist
                else -> return null
            }
            val bookRows = root["books"] as? JsonArray ?: return null
            if (bookRows.size > ShareImageContent.MAX_BOOKS || kind == ShareImageKind.Book && bookRows.size != 1) return null
            val books = bookRows.map { entry ->
                val row = entry as? JsonObject ?: return null
                if (row.keys != BOOK_KEYS) return null
                ShareImageBook(row.string("title") ?: return null, row.string("authors") ?: return null, row.string("cover") ?: return null)
            }
            val fieldRows = root["fields"] as? JsonArray ?: return null
            if (fieldRows.size > ShareImageContent.MAX_FIELDS) return null
            val fields = fieldRows.map { entry ->
                val row = entry as? JsonObject ?: return null
                if (row.keys != FIELD_KEYS) return null
                ShareImageField(row.string("label") ?: return null, row.string("value") ?: return null)
            }
            val total = if ("totalBooks" in root) root.integer("totalBooks") ?: return null else null
            val partial = (root["partial"] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: return null
            val content = ShareImageContent(
                kind = kind,
                title = root.string("title") ?: return null,
                books = books,
                fields = fields,
                description = root.string("description") ?: return null,
                totalBooks = total,
                previewIsPartial = partial,
            )
            content.takeIf { it == it.bounded() }
        } catch (_: SerializationException) {
            null
        }
    }

    private fun Book.displayBook() = ShareImageBook(title, authors, coverUrl)

    private fun plainDescription(raw: String): String {
        val source = raw.take(16_384)
            .replace(HIDDEN_HTML, "")
            .replace(RESOURCE_HTML, "")
            .replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace("\u2029", "\n\n").replace('\u2028', '\n')
            .replace("\n", "<br>")
        return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
    private fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.intOrNull

    private val ROOT_KEYS = setOf("version", "kind", "title", "books", "fields", "description", "totalBooks", "partial")
    private val REQUIRED_KEYS = ROOT_KEYS - "totalBooks"
    private val BOOK_KEYS = setOf("title", "authors", "cover")
    private val FIELD_KEYS = setOf("label", "value")
    private val HIDDEN_HTML = Regex("""<!--.*?-->|<(script|style|iframe|object)\b[^>]*>.*?</\1\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RESOURCE_HTML = Regex("""<(?:img|source|link|meta)\b[^>]*>""", RegexOption.IGNORE_CASE)
}
