package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppErrorException
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DefaultAppDispatchers
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.common.map
import com.qgming.zlib.core.common.mapCatching
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookDetails
import com.qgming.zlib.core.model.BookFormat
import com.qgming.zlib.core.model.BookPage
import com.qgming.zlib.core.model.OnlineBookPreview
import com.qgming.zlib.core.model.Quota
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.SearchFilterOptions
import com.qgming.zlib.core.model.SearchLanguage
import com.qgming.zlib.core.model.SearchSort
import com.qgming.zlib.core.model.Session
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Minimal EAPI client: OkHttp + form encoding + lenient JSON envelope parsing.
 *
 * Deliberately not Retrofit (ADR-011): the base URL changes at runtime, all list endpoints
 * are form-encoded, and errors may arrive with non-2xx statuses.
 */
class EapiClient(
    private val client: OkHttpClient,
    private val baseUrlProvider: () -> String?,
    private val sessionProvider: () -> Session?,
    private val json: Json = EapiJson.instance,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val baseUrlForHost: (String) -> String = { DomainPolicy.baseUrl(it) },
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 4,
    private val isAllowedUrl: (HttpUrl) -> Boolean = { DomainPolicy.isStandardHttps(it) && DomainPolicy.isAllowed(it.host) },
    private val verificationCookieProvider: (HttpUrl) -> String? = { null },
    private val verificationUserAgentProvider: (HttpUrl) -> String? = { null },
) {
    private val guard = EapiRedirectGuardInterceptor(isAllowedUrl)
    private val readClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followSslRedirects(false)
        .addNetworkInterceptor(guard)
        .build()
    private val mutationClient = client.newBuilder()
        .oneShotTransport()
        .addNetworkInterceptor(guard)
        .build()

    // ---------------------------------------------------------------- auth

    suspend fun login(email: String, password: String): AppResult<Session> {
        val form = FormBody.Builder()
            .add("email", email.trim())
            .add("password", password)
            .build()
        return execute(EapiEndpoints.LOGIN, form, session = null, allowRetry = false)
            .mapCatching { exchange ->
                val root = exchange.root
                val user = root.obj("user")
                val cookies = parseSetCookies(exchange.setCookies)

                val userId = user?.stringOrNull("id")
                    ?: cookies[Session.USER_ID_COOKIE]
                    ?: root.stringOrNull("remix_userid")
                    ?: root.intOrNull("user_id")?.toString()
                    ?: ""
                val userKey = user?.stringOrNull("remix_userkey")
                    ?: user?.stringOrNull("remix_user_key")
                    ?: cookies[Session.USER_KEY_COOKIE]
                    ?: cookies[Session.USER_KEY_COOKIE_ALT]
                    ?: root.stringOrNull("remix_userkey")
                    ?: root.stringOrNull("remix_user_key")
                    ?: ""

                if (!Session.isCredentialValue(userId) || !Session.isCredentialValue(userKey)) {
                    throw AppErrorException(AppError.Parse(appText(TextR.string.text_signed_in_but_the_response_is_missing_remix_credentials)))
                }

                Session(
                    remixUserId = userId,
                    remixUserKey = userKey,
                    domain = exchange.responseHost,
                ).withAuthenticationCookies()
            }
    }

    // ---------------------------------------------------------------- account

    suspend fun profile(session: Session? = null): AppResult<Account> {
        val auth = session ?: sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return execute(EapiEndpoints.PROFILE, form = null, auth, isGet = true)
            .mapCatching { exchange -> mapAccount(exchange.root, auth) }
    }

    suspend fun recommended(): AppResult<List<Book>> =
        bookList(EapiEndpoints.RECOMMENDED, sessionProvider())

    suspend fun mostPopular(): AppResult<List<Book>> =
        bookList(EapiEndpoints.MOST_POPULAR, sessionProvider())

    suspend fun similarBooks(id: String, hash: String): AppResult<List<Book>> {
        if (!BookMapper.isSafeRef(id, hash)) return AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_book_reference)))
        return execute("/eapi/book/$id/$hash/similar", null, sessionProvider(), isGet = true).mapCatching {
            if (it.root.array("books") == null) throw AppErrorException(AppError.Parse(appText(TextR.string.text_similar_books_response_is_missing_the_books_list)))
            BookMapper.listFrom(it.root).filterNot { book -> book.ref == "$id:$hash" }.distinctBy(Book::ref)
        }
    }

    suspend fun searchFilterOptions(): AppResult<SearchFilterOptions> {
        val languages = execute("/eapi/info/languages", null, session = null, isGet = true)
        if (languages is AppResult.Failure) return languages
        val extensions = execute("/eapi/info/extensions", null, session = null, isGet = true)
        if (extensions is AppResult.Failure) return extensions
        return (languages as AppResult.Success).mapCatching { response ->
            val languageObject = response.root.obj("languages") ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_invalid_language_list)))
            val extensionArray = (extensions as AppResult.Success).value.root.array("extensions")
                ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_invalid_format_list)))
            val options = languageObject.map { (code, value) ->
                val label = value.stringOrNull() ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_invalid_language_name)))
                if (code.isBlank() || code.length > 80 || code.any { it.isISOControl() }) throw AppErrorException(AppError.Parse(appText(TextR.string.text_invalid_language_code)))
                SearchLanguage(code, label)
            }
            val formats = extensionArray.map {
                it.stringOrNull()?.lowercase()?.takeIf { code -> code.matches(Regex("[a-z0-9]{1,20}")) }
                    ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_invalid_format_code)))
            }.distinct()
            if (options.isEmpty() || formats.isEmpty()) throw AppErrorException(AppError.Parse(appText(TextR.string.text_filter_options_are_empty)))
            SearchFilterOptions(options, formats)
        }
    }

    suspend fun savedBooks(page: Int): AppResult<BookPage> =
        accountBooks(EapiEndpoints.SAVED, page, includeHistoryDate = false)

    suspend fun downloadHistory(page: Int): AppResult<BookPage> =
        accountBooks(EapiEndpoints.DOWNLOADED, page, includeHistoryDate = true)

    suspend fun deleteDownloadHistory(id: String): AppResult<Unit> {
        if (!NUMERIC_BOOK_ID.matches(id)) return AppResult.Failure(AppError.Parse(appText(TextR.string.text_deleting_a_record_requires_a_numeric_book_id)))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return execute(EapiEndpoints.deleteHistory(id), null, auth, isGet = true, allowRetry = false)
            .mapCatching { }
    }

    /** Favorite membership is by numeric ID, including list entries without a download hash. */
    suspend fun isBookSaved(id: String): AppResult<Boolean> {
        if (!NUMERIC_BOOK_ID.matches(id)) return AppResult.Failure(AppError.Parse(appText(TextR.string.text_favorites_require_a_numeric_book_id)))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        var totalPages: Int? = null
        for (page in 1..20) {
            when (val result = favoriteIdPage(page, auth)) {
                is AppResult.Failure -> return result
                is AppResult.Success -> {
                    val slice = result.value
                    if (totalPages != null && totalPages != slice.totalPages) return invalidFavoritePagination()
                    if (id in slice.ids) return AppResult.Success(true)
                    if (page >= slice.totalPages) return AppResult.Success(false)
                    totalPages = slice.totalPages
                }
            }
        }
        return invalidFavoritePagination()
    }

    data class FavoriteIdPage(val ids: Set<String>, val totalPages: Int)

    suspend fun favoriteIdPage(page: Int, session: Session): AppResult<FavoriteIdPage> {
        if (sessionProvider() != session) return AppResult.Failure(AppError.SessionExpired)
        if (page <= 0) return invalidFavoritePagination()
        return execute("${EapiEndpoints.SAVED}?page=$page&limit=50", null, session, isGet = true)
            .mapCatching { exchange ->
                if (sessionProvider() != session) throw AppErrorException(AppError.SessionExpired)
                val root = exchange.root
                val books = root.array("books") ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_response_is_missing_the_books_field)))
                val ids = books.map { entry ->
                    (entry as? JsonObject)?.stringOrNull("id")?.takeIf(NUMERIC_BOOK_ID::matches)
                        ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_the_favorites_list_is_missing_book_ids)))
                }.toSet()
                val pagination = root.obj("pagination")
                val current = pagination?.intOrNull("current")
                val total = pagination?.intOrNull("total_pages")
                if (page == 1 && ids.isEmpty() && total == 0) return@mapCatching FavoriteIdPage(ids, 0)
                if (current != page || total == null || total < current || (ids.isEmpty() && current < total)) {
                    throw AppErrorException(invalidFavoritePagination().error)
                }
                FavoriteIdPage(ids, total)
            }
    }

    private fun invalidFavoritePagination() = AppResult.Failure(AppError.Parse(appText(TextR.string.text_cannot_determine_favorites_pagination)))

    suspend fun setBookSaved(id: String, saved: Boolean, session: Session? = sessionProvider()): AppResult<Unit> {
        if (!NUMERIC_BOOK_ID.matches(id)) return AppResult.Failure(AppError.Parse(appText(TextR.string.text_favorites_require_a_numeric_book_id)))
        val auth = session ?: return AppResult.Failure(AppError.NotLoggedIn)
        if (sessionProvider() != auth) return AppResult.Failure(AppError.SessionExpired)
        val path = if (saved) EapiEndpoints.saveBook(id) else EapiEndpoints.unsaveBook(id)
        return execute(path, null, auth, isGet = true, allowRetry = false).mapCatching { Unit }
    }

    private suspend fun accountBooks(path: String, page: Int, includeHistoryDate: Boolean): AppResult<BookPage> {
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        val requestedPage = page.coerceAtLeast(1)
        return execute("$path?page=$requestedPage&limit=50", form = null, session = auth, isGet = true)
            .mapCatching { exchange -> exchange.root.toBookPage(requestedPage, includeHistoryDate) }
    }

    private suspend fun bookList(path: String, session: Session?): AppResult<List<Book>> =
        execute(path, form = null, session = session, isGet = true)
            .mapCatching { exchange -> exchange.root.requireBookList() }

    // ---------------------------------------------------------------- domains

    /** Domain info is public; never send credentials here. */
    suspend fun domains(): AppResult<List<String>> = execute(
        path = EapiEndpoints.DOMAINS,
        form = null,
        session = null,
        isGet = true,
    ).mapCatching { exchange ->
        val array = exchange.root.array("domains") ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_response_is_missing_the_domains_field)))
        val hosts = array.mapNotNull { element ->
            val raw = element.stringOrNull()
                ?: (element as? JsonObject)?.let {
                    it.stringOrNull("domain") ?: it.stringOrNull("host") ?: it.stringOrNull("url")
                }
            raw?.let(DomainPolicy::normalizeHostInput)
        }.distinct()
        hosts
    }

    // ---------------------------------------------------------------- books

    suspend fun search(query: SearchQuery, page: Int = 1, limit: Int = 50): AppResult<BookPage> {
        if (query.message.isBlank()) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_search_keywords_cannot_be_empty)))
        }
        val requestedPage = page.coerceAtLeast(1)
        val builder = FormBody.Builder()
            .add("message", query.message.trim())
            .add("page", requestedPage.toString())
            .add("limit", limit.coerceIn(1, 50).toString())
        if (query.exact) builder.add("e", "1")
        query.yearFrom?.let { builder.add("yearFrom", it.toString()) }
        query.yearTo?.let { builder.add("yearTo", it.toString()) }
        query.order?.takeIf { value -> SearchSort.entries.any { it.wireValue == value } }?.let { builder.add("order", it) }
        query.languages.forEach { builder.add("languages[]", it.lowercase()) }
        query.extensions.forEach { builder.add("extensions[]", it.lowercase()) }

        return when (val result = execute(EapiEndpoints.SEARCH, builder.build(), sessionProvider())
            .mapCatching { exchange -> exchange.root.toBookPage(requestedPage) }) {
            is AppResult.Success -> result
            is AppResult.Failure -> {
                val error = result.error
                if (error is AppError.Server &&
                    (error.message.equals("Access denied", ignoreCase = true) || error.message == "拒绝访问")
                ) {
                    AppResult.Failure(AppError.SearchRefused)
                } else {
                    result
                }
            }
        }
    }

    private fun JsonObject.toBookPage(requestedPage: Int, includeHistoryDate: Boolean = false): BookPage {
        val pagination = obj("pagination")
        val books = requireBookList(includeHistoryDate)
        val current = pagination?.intOrNull("current")?.takeIf { it > 0 } ?: requestedPage
        val hasExplicitEnd = pagination?.get("next")?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "false" } == true
        return BookPage(
            books = books,
            page = current,
            totalPages = if (books.isEmpty() || hasExplicitEnd) current else pagination?.intOrNull("total_pages")?.coerceAtLeast(current) ?: current,
        )
    }

    private fun JsonObject.requireBookList(includeHistoryDate: Boolean = false): List<Book> {
        if (array("books") == null) throw AppErrorException(AppError.Parse(appText(TextR.string.text_response_is_missing_the_books_field)))
        return BookMapper.listFrom(this, includeHistoryDate)
    }

    suspend fun bookDetail(id: String, hash: String): AppResult<Book> =
        bookDetails(id, hash).map(BookDetails::book)

    suspend fun bookDetails(id: String, hash: String): AppResult<BookDetails> =
        readBookDetails(id, hash, sessionProvider())

    suspend fun resolveOnlinePreview(id: String, hash: String, session: Session?): AppResult<OnlineBookPreview?> =
        readBookDetails(id, hash, session, cacheControl = PREVIEW_CACHE_CONTROL).map(BookDetails::onlinePreview)

    private suspend fun readBookDetails(
        id: String,
        hash: String,
        session: Session?,
        cacheControl: CacheControl? = null,
    ): AppResult<BookDetails> {
        if (!BookMapper.isSafeRef(id, hash)) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_book_id_or_hash)))
        }
        return execute(EapiEndpoints.detail(id, hash), null, session, isGet = true, cacheControl = cacheControl)
            .mapCatching { exchange ->
                val obj = exchange.root.obj("book") ?: exchange.root
                val book = BookMapper.fromJson(obj)
                    ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_book_details_are_missing_an_id_or_hash)))
                val preview = if (book.id == id && book.hash == hash && obj.boolOrNull("readOnlineAvailable") != false) {
                    bookOnlinePreview(obj, exchange.responseHost)
                } else {
                    null
                }
                BookDetails(book, preview)
            }
    }

    private fun bookOnlinePreview(book: JsonObject, responseHost: String): OnlineBookPreview? {
        val url = book.stringOrNull("readOnlineUrl") ?: return null
        val host = DomainPolicy.normalizeHostInput(responseHost) ?: return null
        return OnlineBookPreview.fromDetail(url, book.stringOrNull("href").orEmpty(), host)
    }

    suspend fun bookFormats(id: String, hash: String): AppResult<List<BookFormat>> {
        if (!BookMapper.isSafeRef(id, hash)) return AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_book_id_or_hash)))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return execute(EapiEndpoints.formats(id, hash), null, auth, isGet = true).mapCatching { exchange ->
            val rows = exchange.root.array("books")
                ?: throw AppErrorException(AppError.Parse(appText(TextR.string.text_format_response_is_missing_the_books_list)))
            val formats = rows.map { element ->
                val row = element as? JsonObject
                val formatId = row?.stringOrNull("id").orEmpty()
                val formatHash = row?.stringOrNull("hash").orEmpty()
                val extension = row?.stringOrNull("extension").orEmpty().trim().lowercase()
                if (!BookMapper.isSafeRef(formatId, formatHash) || !FILE_EXTENSION.matches(extension)) {
                    throw AppErrorException(AppError.Parse(appText(TextR.string.text_the_format_list_contains_invalid_file_information_please_retry)))
                }
                BookFormat(
                    id = formatId,
                    hash = formatHash,
                    extension = extension,
                    sizeBytes = row?.longOrNull("filesize")?.takeIf { it >= 0 },
                    sizeText = row?.stringOrNull("filesizeString").orEmpty(),
                )
            }.distinctBy(BookFormat::ref)
            SafeLog.d(TAG, "formats $id:$hash returned=${formats.size}")
            formats
        }
    }

    /** Resolves the current download URL. Signed query strings must never be persisted/logged. */
    fun downloadUrl(id: String, hash: String, session: Session? = sessionProvider()): String? {
        val auth = session ?: return null
        if (!BookMapper.isSafeRef(id, hash)) return null
        return endpoint(baseUrlForHost(auth.domain), EapiEndpoints.file(id, hash))?.toString()
    }

    // ---------------------------------------------------------------- transport

    private suspend fun execute(
        path: String,
        form: FormBody?,
        session: Session?,
        isGet: Boolean = false,
        allowRetry: Boolean = true,
        cacheControl: CacheControl? = null,
    ): AppResult<Exchange> {
        if (session != null && !session.isValid) return AppResult.Failure(AppError.NotLoggedIn)
        val base = baseUrlFor(session) ?: return AppResult.Failure(AppError.NoDomain)
        val url = endpoint(base, path) ?: return AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID))
        val deadline = System.nanoTime() + REQUEST_BUDGET_NANOS
        var attempt = 1
        var lastError: AppError? = null
        var expiredRetried = false

        val attemptLimit = if (allowRetry) maxAttempts.coerceAtLeast(1) else 1
        while (attempt <= attemptLimit) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return AppResult.Failure(lastError ?: AppError.Network(InterruptedIOException("EAPI timeout")))
            if (!isAllowedUrl(url)) return AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID))
            val outcome = try {
                withContext(dispatchers.io) {
                    executeOnce(url, form, session, isGet, allowRetry, cacheControl, minOf(remaining, CALL_TIMEOUT_NANOS))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (blocked: BlockedRedirectException) {
                Outcome.Err(AppError.DomainBlocked(DomainStatus.INVALID), retryable = false)
            } catch (illegal: IllegalArgumentException) {
                Outcome.Err(AppError.Parse(appText(TextR.string.text_invalid_request_parameters)), retryable = false)
            } catch (io: IOException) {
                Outcome.Err(AppError.Network(io), retryable = true)
            }
            when (outcome) {
                is Outcome.Ok -> return AppResult.Success(outcome.exchange)
                is Outcome.Err -> {
                    lastError = outcome.error
                    // ADR-016: a session-expired response gets one retry on the same domain.
                    if (allowRetry && outcome.error == AppError.SessionExpired && !expiredRetried) {
                        expiredRetried = true
                        continue
                    }
                    if (!outcome.retryable || attempt >= attemptLimit) break
                    val delayMillis = 1_000L shl (attempt - 1)
                    if (TimeUnit.MILLISECONDS.toNanos(delayMillis) >= deadline - System.nanoTime()) break
                    sleeper(delayMillis)
                    attempt++
                }
            }
        }
        return AppResult.Failure(lastError ?: AppError.Unknown(IllegalStateException("EAPI request failed")))
    }

    private fun baseUrlFor(session: Session?): String? =
        if (session == null) baseUrlProvider() else baseUrlForHost(session.domain)

    private fun endpoint(base: String, path: String): HttpUrl? {
        val origin = base.toHttpUrlOrNull()?.takeIf {
            isAllowedUrl(it) && it.encodedPath == "/" && it.encodedQuery == null && it.encodedFragment == null
        } ?: return null
        return origin.resolve(path)?.takeIf { it.hasSameOrigin(origin) && isAllowedUrl(it) }
    }

    private suspend fun executeOnce(
        url: HttpUrl,
        form: FormBody?,
        session: Session?,
        isGet: Boolean,
        allowRetry: Boolean,
        cacheControl: CacheControl?,
        timeoutNanos: Long,
    ): Outcome {
        SafeLog.d(TAG, "request ${SafeLog.redactUrl(url.toString())}")
        val builder = Request.Builder().url(url).markEapi()
        if (isGet) {
            builder.get()
        } else {
            builder.post(form ?: EMPTY_FORM)
        }
        val verificationCookie = DomainVerificationCookies.sanitizeCookieHeader(verificationCookieProvider(url))
        val userAgent = if (verificationCookie != null) {
            DomainVerificationCookies.sanitizeUserAgent(verificationUserAgentProvider(url)) ?: EapiHeaders.USER_AGENT
        } else {
            EapiHeaders.USER_AGENT
        }
        builder.header("Accept", EapiHeaders.ACCEPT)
        builder.header("User-Agent", userAgent)
        if (cacheControl != null) builder.cacheControl(cacheControl)
        // ADR-076: the search endpoint rejects requests that do not identify the mobile client.
        builder.header(EapiHeaders.SOURCE, EapiHeaders.SOURCE_ANDROID)
        val cookies = listOfNotNull(session?.cookieHeader, verificationCookie).filter(String::isNotBlank)
        if (cookies.isNotEmpty()) builder.header(EapiHeaders.COOKIE, cookies.joinToString("; "))
        if (session != null) {
            builder.header(EapiHeaders.REMIX_USER_ID, session.remixUserId)
            builder.header(EapiHeaders.REMIX_USER_KEY, session.remixUserKey)
        }

        val transport = if (allowRetry) readClient else mutationClient
        val call = transport.newCall(builder.build())
        call.timeout().timeout(timeoutNanos, TimeUnit.NANOSECONDS)
        return call.executeParsedOnIo { response ->
            val code = response.code
            if (!response.request.url.hasSameOrigin(url) || !isAllowedUrl(response.request.url)) {
                return@executeParsedOnIo Outcome.Err(AppError.DomainBlocked(DomainStatus.INVALID), retryable = false)
            }
            if (!allowRetry && response.isRedirect) {
                return@executeParsedOnIo Outcome.Err(AppError.DomainBlocked(DomainStatus.INVALID), retryable = false)
            }
            val body = response.body.stringUpTo(MAX_RESPONSE_BYTES)
                ?: return@executeParsedOnIo Outcome.Err(AppError.Parse(appText(TextR.string.text_unexpected_server_response_please_retry_later)), retryable = false)
            val setCookies = response.headers.values("Set-Cookie")
            val root = parseObject(body)

            if (root != null) {
                when (root.intOrNull("success")) {
                    1 -> if (response.isSuccessful) return@executeParsedOnIo Outcome.Ok(Exchange(root, setCookies, response.request.url.host))
                    0 -> {
                        val error = mapEnvelopeError(root, code)
                        return@executeParsedOnIo Outcome.Err(error, retryable = error is AppError.Server && code in RETRYABLE_STATUS)
                    }
                    else -> if (code in 200..299) {
                        return@executeParsedOnIo Outcome.Err(AppError.Parse(appText(TextR.string.text_response_is_missing_the_success_field)), retryable = false)
                    }
                }
            }

            if (code == 513 || code == 517 || body.contains("diamwall", ignoreCase = true)) {
                return@executeParsedOnIo Outcome.Err(AppError.DomainBlocked(DomainStatus.DIAMWALL), retryable = false)
            }
            if (looksLikeHtml(body)) {
                return@executeParsedOnIo Outcome.Err(AppError.DomainBlocked(DomainStatus.CHALLENGED), retryable = false)
            }
            if (code in RETRYABLE_STATUS) {
                return@executeParsedOnIo Outcome.Err(AppError.Server(code, appText(TextR.string.text_service_temporarily_unavailable_http, code)), retryable = true)
            }
            if (code == 401 || code == 403) {
                return@executeParsedOnIo Outcome.Err(AppError.DomainBlocked(DomainStatus.CHALLENGED), retryable = false)
            }
            Outcome.Err(
                error = if (code in 200..299) {
                    AppError.Parse(appText(TextR.string.text_the_server_returned_a_non_json_response))
                } else {
                    AppError.Server(code, appText(TextR.string.text_request_failed_http, code))
                },
                retryable = false,
            )
        }
    }

    private fun mapEnvelopeError(root: JsonObject, httpCode: Int): AppError {
        val message = root.stringOrNull("error").orEmpty().trim()
        val lower = message.lowercase()
        return when {
            lower.contains("daily limit") || lower.contains("daily download") ->
                AppError.Quota(remaining = null, resetHint = message)

            lower.contains("invalid credentials") || lower.contains("please login") ||
                lower.contains("session expired") || lower.contains("not authorized") ->
                AppError.SessionExpired

            lower.contains("incorrect email or password") -> AppError.AuthFailed(message)
            lower.contains("authorization failed") -> AppError.AuthFailed(message)
            else -> AppError.Server(httpCode, message.ifBlank { appText(TextR.string.text_request_failed_http_2, httpCode) })
        }
    }

    private fun mapAccount(root: JsonObject, session: Session): Account {
        val user = root.obj("user") ?: root
        val amount = user.intOrNull("downloads_today") ?: user.intOrNull("dailyDownloadsCount")
        val allowed = user.intOrNull("downloads_limit") ?: user.intOrNull("dailyDownloadLimit")
        return Account(
            id = user.stringOrNull("id") ?: session.remixUserId,
            name = user.stringOrNull("name").orEmpty(),
            email = user.stringOrNull("email").orEmpty(),
            isPremium = user.boolOrNull("isPremium") == true,
            confirmed = user.boolOrNull("confirmed") == true,
            quota = Quota.from(amount, allowed),
            membershipKnown = user.boolOrNull("isPremium") != null,
        )
    }

    private fun parseObject(body: String): JsonObject? = runCatching {
        json.parseToJsonElement(body) as? JsonObject
    }.getOrNull()

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<") && (trimmed.contains("<html", true) || trimmed.contains("<!doctype", true))
    }

    private data class Exchange(
        val root: JsonObject,
        val setCookies: List<String>,
        val responseHost: String,
    )

    private sealed interface Outcome {
        data class Ok(val exchange: Exchange) : Outcome
        data class Err(val error: AppError, val retryable: Boolean) : Outcome
    }

    companion object {
        private const val TAG = "Eapi"
        private const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        private val REQUEST_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(45)
        private val CALL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(25)
        private val RETRYABLE_STATUS = setOf(502, 503, 504)
        private val NUMERIC_BOOK_ID = Regex("[0-9]{1,32}")
        private val FILE_EXTENSION = Regex("[a-z0-9]{1,20}")
        private val PREVIEW_CACHE_CONTROL = CacheControl.Builder().noCache().noStore().build()
        private val EMPTY_FORM: FormBody = FormBody.Builder().build()
    }
}

/** Parses `Set-Cookie` headers into a simple name→value map. */
internal fun parseSetCookies(headers: List<String>): Map<String, String> = headers.mapNotNull { header ->
    val pair = header.substringBefore(';').trim()
    val index = pair.indexOf('=')
    if (index <= 0) return@mapNotNull null
    val name = pair.substring(0, index).trim()
    val value = pair.substring(index + 1).trim().removeSurrounding("\"")
    if (name.isEmpty() || value.isEmpty() || value.any { it.isISOControl() }) null else name to value
}.toMap()

/** Shared JSON configuration for all EAPI parsing. */
object EapiJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }
}
