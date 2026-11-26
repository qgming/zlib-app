package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.BooklistMembership
import com.qgming.zlib.core.model.BooklistPage
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

class PapiBooklistClient(
    client: OkHttpClient,
    isAllowedHost: (String) -> Boolean,
    verificationCookieProvider: (HttpUrl) -> String? = { null },
    verificationUserAgentProvider: (HttpUrl) -> String? = { null },
) {
    private val transport = PapiTransport(
        client = client,
        isAllowedHost = isAllowedHost,
        verificationCookieProvider = verificationCookieProvider,
        verificationUserAgentProvider = verificationUserAgentProvider,
    )

    suspend fun myBooklists(session: Session): AppResult<List<CloudBooklist>> =
        transport.request(session, "/papi/booklist/current-user/0") { root ->
            listRows(root).map(::cloudBooklist).distinctBy(CloudBooklist::id)
        }

    suspend fun books(session: Session, id: String, page: Int): AppResult<BooklistPage> {
        if (!PAPI_ID.matches(id) || page !in 1..MAX_PAGE) return invalidPapiParameters()
        return transport.request(session, "/papi/booklist/$id/get-books/$page", EMPTY_FORM) { root -> bookPage(root, page) }
    }

    /** All of the current account's lists, along with membership for this book (no pagination). */
    suspend fun memberships(session: Session, bookId: String): AppResult<List<BooklistMembership>> {
        if (!PAPI_ID.matches(bookId)) return invalidPapiParameters()
        return transport.request(session, "/papi/booklist/current-user/$bookId") { root ->
            listRows(root).map { row ->
                val membership = row["readlistBookId"]
                val readlistBookId = when (membership) {
                    null, JsonNull -> null
                    else -> membership.stringOrNull()?.takeIf(PAPI_ID::matches) ?: malformedPapiResponse()
                }
                BooklistMembership(cloudBooklist(row), isMember = readlistBookId != null, readlistBookId = readlistBookId)
            }.groupBy(BooklistMembership::booklistId).values.map { entries ->
                val list = entries.first().booklist.copy(
                    bookCount = entries.firstNotNullOfOrNull { it.booklist.bookCount },
                ).withCoverPreview(entries.flatMap { it.booklist.covers })
                val readlistBookId = entries.firstNotNullOfOrNull(BooklistMembership::readlistBookId)
                BooklistMembership(list, isMember = readlistBookId != null, readlistBookId = readlistBookId)
            }
        }
    }

    /** This GET writes: do not redirect, cache or replay it. It never removes membership. */
    suspend fun addBook(session: Session, booklistId: String, bookId: String): AppResult<Unit> {
        if (!PAPI_ID.matches(booklistId) || !PAPI_ID.matches(bookId)) return invalidPapiParameters()
        return transport.request(session, "/papi/booklist/$booklistId/add-book/$bookId") { Unit }
    }

    /** This GET writes: do not redirect, cache or replay it. */
    suspend fun removeBook(session: Session, booklistId: String, readlistBookId: String): AppResult<Unit> {
        if (!PAPI_ID.matches(booklistId) || !PAPI_ID.matches(readlistBookId)) return invalidPapiParameters()
        return transport.request(
            session = session,
            path = "/papi/booklist/$booklistId/remove-book/$readlistBookId",
            allowEmptySuccess = true,
        ) { Unit }
    }

    /** Returns the new list's ID when available; membership must still be verified after creation. */
    suspend fun createBooklist(session: Session, title: String, bookId: String): AppResult<String?> {
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle.length > CloudBooklist.MAX_TITLE_LENGTH || cleanTitle.any(Char::isISOControl)) {
            return invalidPapiParameters()
        }
        val numericBookId = bookId.takeIf(PAPI_ID::matches)?.toLongOrNull() ?: return invalidPapiParameters()
        val body = buildJsonObject {
            put("title", cleanTitle)
            put("description", "")
            put("private", true)
            put("bookId", numericBookId)
        }.toString().toRequestBody(TEXT_PLAIN)
        return transport.request(session, "/papi/booklist/create", body, createConfirmation = true) { root ->
            root.obj("readlist")?.stringOrNull("id")?.takeIf(PAPI_ID::matches)
        }
    }

    private fun listRows(root: JsonObject): List<JsonObject> =
        (root.array("list") ?: malformedPapiResponse()).map { it as? JsonObject ?: malformedPapiResponse() }

    private fun cloudBooklist(row: JsonObject) = CloudBooklist(
        id = row.stringOrNull("id")?.takeIf(PAPI_ID::matches) ?: malformedPapiResponse(),
        title = row.stringOrNull("title").orEmpty().filterNot(Char::isISOControl).trim()
            .ifEmpty { appText(TextR.string.text_untitled) },
        bookCount = row.intOrNull("books_count")?.takeIf { it >= 0 },
        covers = previewCovers(row),
    )

    private fun bookPage(root: JsonObject, page: Int): BooklistPage {
        val rows = root.array("books") ?: malformedPapiResponse()
        val books = rows.map { entry ->
            val record = (entry as? JsonObject)?.obj("book") ?: malformedPapiResponse()
            if (record.stringOrNull("id")?.matches(PAPI_ID) != true || (record["hash"] as? JsonPrimitive)?.isString != true) {
                malformedPapiResponse()
            }
            val book = BookMapper.fromJson(record) ?: malformedPapiResponse()
            book.copy(href = "")
        }.distinctBy(Book::ref)
        return BooklistPage(books, if (books.isEmpty()) null else nextPage(root, page, books.size))
    }

    private fun previewCovers(row: JsonObject): List<BooklistCover> =
        row.array("books")?.asSequence()?.mapNotNull { entry ->
            val book = entry as? JsonObject ?: return@mapNotNull null
            val id = book.stringOrNull("id")?.takeIf(PAPI_ID::matches) ?: return@mapNotNull null
            BookMapper.usableCover(book.stringOrNull("cover")).takeIf(String::isNotBlank)?.let { BooklistCover(id, it) }
        }?.distinctBy(BooklistCover::bookId)?.take(BooklistCover.COUNT)?.toList().orEmpty()

    private fun nextPage(root: JsonObject, page: Int, itemCount: Int): Int? {
        val pagination = root["pagination"]?.takeUnless { it is JsonNull }
            ?: return (page + 1).takeIf { itemCount >= FALLBACK_PAGE_SIZE && it <= MAX_PAGE }
        val fields = pagination as? JsonObject ?: malformedPapiResponse()
        if ("current" in fields && fields.intOrNull("current") != page) malformedPapiResponse()
        if ("next" in fields) {
            val next = fields["next"] as? JsonPrimitive ?: malformedPapiResponse()
            if (next is JsonNull || next.content in END_OF_PAGINATION) return null
            val number = next.intOrNullValue()?.takeIf { it > page } ?: malformedPapiResponse()
            return number.takeIf { it <= MAX_PAGE }
        }
        return (page + 1).takeIf { page < MAX_PAGE && page < (fields.intOrNull("total_pages") ?: page) }
    }

    private companion object {
        const val MAX_PAGE = 1_000
        const val FALLBACK_PAGE_SIZE = 20
        val TEXT_PLAIN = "text/plain; charset=utf-8".toMediaType()
        val EMPTY_FORM = FormBody.Builder().build()
        val END_OF_PAGINATION = setOf("false", "0")
    }
}
