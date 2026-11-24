package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppErrorException
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.BooklistPage
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

class PapiBooklistClient(
    client: OkHttpClient,
    private val isAllowedHost: (String) -> Boolean,
) {
    private val transport = client.newBuilder()
        .authenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503 && response.header("Retry-After") != null) {
                response.newBuilder().removeHeader("Retry-After").build()
            } else {
                response
            }
        }
        .build()

    suspend fun myBooklists(session: Session): AppResult<List<CloudBooklist>> =
        requestWithSession(session, "/papi/booklist/current-user/0") { root ->
            val rows = root.array("list") ?: malformed()
            rows.map { entry ->
                val row = entry as? JsonObject ?: malformed()
                val id = row.stringOrNull("id")?.takeIf(BOOKLIST_ID::matches) ?: malformed()
                CloudBooklist(
                    id = id,
                    title = row.stringOrNull("title").orEmpty().filterNot(Char::isISOControl).trim()
                        .ifEmpty { appText(TextR.string.text_untitled) },
                    bookCount = row.intOrNull("books_count")?.takeIf { it >= 0 },
                    covers = previewCovers(row),
                )
            }.distinctBy(CloudBooklist::id)
        }

    suspend fun books(session: Session, id: String, page: Int): AppResult<BooklistPage> {
        if (!BOOKLIST_ID.matches(id) || page !in 1..MAX_PAGE) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_request_parameters)))
        }
        return requestWithSession(session, "/papi/booklist/$id/get-books/$page", EMPTY_FORM) { root ->
            bookPage(root, page)
        }
    }

    private suspend fun <T> requestWithSession(
        session: Session,
        path: String,
        body: RequestBody? = null,
        parse: (JsonObject) -> T,
    ): AppResult<T> {
        val cookie = sessionCookie(session) ?: return AppResult.Failure(AppError.NotLoggedIn)
        val url = requestUrl(session.domain, path)
            ?: return AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID))
        val request = Request.Builder().url(url)
            .header("Accept", EapiHeaders.ACCEPT)
            .header("User-Agent", EapiHeaders.USER_AGENT)
            .header(EapiHeaders.COOKIE, cookie)
            .apply { if (body != null) post(body) }
            .build()
        return try {
            transport.newCall(request).awaitParsed { response -> AppResult.Success(parse(envelope(response))) }
        } catch (failure: AppErrorException) {
            AppResult.Failure(failure.error)
        } catch (failure: IOException) {
            AppResult.Failure(AppError.Network(failure))
        }
    }

    private fun bookPage(root: JsonObject, page: Int): BooklistPage {
        val rows = root.array("books") ?: malformed()
        val books = rows.map { entry ->
            val record = (entry as? JsonObject)?.obj("book") ?: malformed()
            if (record.stringOrNull("id")?.matches(BOOKLIST_ID) != true || (record["hash"] as? JsonPrimitive)?.isString != true) malformed()
            val book = BookMapper.fromJson(record) ?: malformed()
            book.copy(href = "")
        }.distinctBy(Book::ref)
        return BooklistPage(books, if (books.isEmpty()) null else nextPage(root, page))
    }

    private fun previewCovers(row: JsonObject): List<BooklistCover> =
        row.array("books")?.asSequence()?.mapNotNull { entry ->
            val book = entry as? JsonObject ?: return@mapNotNull null
            val id = book.stringOrNull("id")?.takeIf(BOOKLIST_ID::matches) ?: return@mapNotNull null
            BookMapper.usableCover(book.stringOrNull("cover")).takeIf(String::isNotBlank)?.let { BooklistCover(id, it) }
        }?.distinctBy(BooklistCover::bookId)?.take(BooklistCover.COUNT)?.toList().orEmpty()

    private fun requestUrl(host: String, path: String): HttpUrl? {
        if (DomainPolicy.normalizeHostInput(host) != host.lowercase() || !isAllowedHost(host)) return null
        return DomainPolicy.baseUrl(host).toHttpUrlOrNull()?.newBuilder()?.encodedPath(path)?.build()
    }

    private fun requireNoRedirect(response: Response) {
        if (response.code == 513 || response.code == 517 || response.header("Server").equals("DiamWall", true)) {
            throw AppErrorException(AppError.DomainBlocked(DomainStatus.DIAMWALL))
        }
        if (response.code in 300..399) {
            throw AppErrorException(AppError.DomainBlocked(DomainStatus.REDIRECT_LOOP))
        }
    }

    private fun readBody(response: Response): String {
        if (response.body.source().request(MAX_RESPONSE_BYTES + 1)) malformed()
        return response.body.string()
    }

    private fun envelope(response: Response): JsonObject {
        requireNoRedirect(response)
        if (response.code == 429) rateLimited()
        val type = response.body.contentType()
        if (type?.subtype == "html") throw AppErrorException(AppError.DomainBlocked(DomainStatus.CHALLENGED))
        val body = readBody(response)
        val root = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val success = root?.intOrNull("success")
        val errorText = root?.obj("error")?.stringOrNull("message") ?: root?.stringOrNull("error") ?: root?.stringOrNull("message")
        val rejected = success == 0 && errorText?.let { value ->
            AUTH_ERRORS.any { value.contains(it, ignoreCase = true) }
        } == true
        if (response.code == 401 || rejected) {
            throw AppErrorException(AppError.Unsupported(appText(TextR.string.text_booklist_session_not_accepted)))
        }
        if (!response.isSuccessful || success == 0) {
            val message = if (response.isSuccessful) appText(TextR.string.text_booklist_request_refused) else ""
            throw AppErrorException(AppError.Server(response.code, message))
        }
        if (type?.type != "application" || type.subtype != "json" || success != 1) malformed()
        return root
    }

    private fun rateLimited(): Nothing =
        throw AppErrorException(AppError.Unsupported(appText(TextR.string.text_booklist_rate_limited)))

    private fun sessionCookie(session: Session): String? {
        if (!session.isValid || !cookieValueIsSafe(session.remixUserId) || !cookieValueIsSafe(session.remixUserKey)) return null
        return "${Session.USER_ID_COOKIE}=${session.remixUserId}; ${Session.USER_KEY_COOKIE}=${session.remixUserKey}"
    }

    private fun nextPage(root: JsonObject, page: Int): Int? {
        val pagination = root["pagination"]?.takeUnless { it is JsonNull } ?: return null
        val fields = pagination as? JsonObject ?: malformed()
        if ("current" in fields && fields.intOrNull("current") != page) malformed()
        if ("next" in fields) {
            val next = fields["next"] as? JsonPrimitive ?: malformed()
            if (next is JsonNull || next.content in END_OF_PAGINATION) return null
            val number = next.intOrNullValue()?.takeIf { it > page } ?: malformed()
            return number.takeIf { it <= MAX_PAGE }
        }
        return (page + 1).takeIf { page < MAX_PAGE && page < (fields.intOrNull("total_pages") ?: page) }
    }

    private fun cookieValueIsSafe(value: String): Boolean = value.all { it in '!'..'~' && it !in "\",;\\" }

    private fun malformed(): Nothing =
        throw AppErrorException(AppError.Parse(appText(TextR.string.text_unexpected_server_response_please_retry_later)))

    private companion object {
        private const val MAX_PAGE = 1_000
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private val BOOKLIST_ID = Regex("[1-9][0-9]{0,19}")
        private val EMPTY_FORM = FormBody.Builder().build()
        private val END_OF_PAGINATION = setOf("false", "0")
        private val AUTH_ERRORS = listOf("please login", "please log in", "please sign in", "invalid credentials", "session expired", "not authorized", "unauthenticated", "请登录", "请先登录")
    }
}
