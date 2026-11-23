package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppErrorException
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DefaultAppDispatchers
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.common.mapCatching
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookFormat
import com.qgming.zlib.core.model.BookPage
import com.qgming.zlib.core.model.Quota
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.SearchFilterOptions
import com.qgming.zlib.core.model.SearchLanguage
import com.qgming.zlib.core.model.SearchSort
import com.qgming.zlib.core.model.Session
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
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
) {
    // A destructive GET must not be replayed by either OkHttp or the application retry loop.
    private val mutationClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    // ---------------------------------------------------------------- auth

    suspend fun login(email: String, password: String): AppResult<Session> {
        val base = baseUrlProvider() ?: return AppResult.Failure(AppError.NoDomain)
        val host = hostOf(base) ?: return AppResult.Failure(AppError.NoDomain)
        val form = FormBody.Builder()
            .add("email", email.trim())
            .add("password", password)
            .build()
        return execute(EapiEndpoints.LOGIN, form, session = null)
            .mapCatching { exchange ->
                val root = exchange.root
                val user = root.obj("user")
                val cookies = parseSetCookies(exchange.setCookies).toMutableMap()

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

                if (userId.isBlank() || userKey.isBlank()) {
                    throw AppErrorException(AppError.Parse("登录成功但响应中缺少 remix 凭据"))
                }

                if (cookies[Session.USER_ID_COOKIE].isNullOrBlank()) cookies[Session.USER_ID_COOKIE] = userId
                if (cookies[Session.USER_KEY_COOKIE].isNullOrBlank() && cookies[Session.USER_KEY_COOKIE_ALT].isNullOrBlank()) {
                    cookies[Session.USER_KEY_COOKIE] = userKey
                }

                Session(
                    remixUserId = userId,
                    remixUserKey = userKey,
                    domain = host,
                    cookies = cookies,
                )
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
        if (!BookMapper.isSafeRef(id, hash)) return AppResult.Failure(AppError.Parse("图书引用无效"))
        return execute("/eapi/book/$id/$hash/similar", null, sessionProvider(), isGet = true).mapCatching {
            if (it.root.array("books") == null) throw AppErrorException(AppError.Parse("相似图书响应缺少 books 列表"))
            BookMapper.listFrom(it.root).filterNot { book -> book.ref == "$id:$hash" }.distinctBy(Book::ref)
        }
    }

    suspend fun searchFilterOptions(): AppResult<SearchFilterOptions> {
        val languages = execute("/eapi/info/languages", null, session = null, isGet = true)
        if (languages is AppResult.Failure) return languages
        val extensions = execute("/eapi/info/extensions", null, session = null, isGet = true)
        if (extensions is AppResult.Failure) return extensions
        return (languages as AppResult.Success).mapCatching { response ->
            val languageObject = response.root.obj("languages") ?: throw AppErrorException(AppError.Parse("语言列表格式异常"))
            val extensionArray = (extensions as AppResult.Success).value.root.array("extensions")
                ?: throw AppErrorException(AppError.Parse("格式列表格式异常"))
            val options = languageObject.map { (code, value) ->
                val label = value.stringOrNull() ?: throw AppErrorException(AppError.Parse("语言名称无效"))
                if (code.isBlank() || code.length > 80 || code.any { it.isISOControl() }) throw AppErrorException(AppError.Parse("语言代码无效"))
                SearchLanguage(code, label)
            }
            val formats = extensionArray.map {
                it.stringOrNull()?.lowercase()?.takeIf { code -> code.matches(Regex("[a-z0-9]{1,20}")) }
                    ?: throw AppErrorException(AppError.Parse("格式代码无效"))
            }.distinct()
            if (options.isEmpty() || formats.isEmpty()) throw AppErrorException(AppError.Parse("筛选列表为空"))
            SearchFilterOptions(options, formats)
        }
    }

    suspend fun savedBooks(page: Int): AppResult<BookPage> =
        accountBooks(EapiEndpoints.SAVED, page, includeHistoryDate = false)

    suspend fun downloadHistory(page: Int): AppResult<BookPage> =
        accountBooks(EapiEndpoints.DOWNLOADED, page, includeHistoryDate = true)

    suspend fun deleteDownloadHistory(id: String): AppResult<Unit> {
        if (!NUMERIC_BOOK_ID.matches(id)) return AppResult.Failure(AppError.Parse("删除记录需要数字图书 ID"))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return execute(EapiEndpoints.deleteHistory(id), null, auth, isGet = true, allowRetry = false)
            .mapCatching { }
    }

    /** Favorite membership is by numeric ID, including list entries without a download hash. */
    suspend fun isBookSaved(id: String): AppResult<Boolean> {
        if (!NUMERIC_BOOK_ID.matches(id)) return AppResult.Failure(AppError.Parse("收藏需要数字图书 ID"))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        var page = 1
        while (true) {
            if (sessionProvider() != auth) return AppResult.Failure(AppError.SessionExpired)
            val result = execute("${EapiEndpoints.SAVED}?page=$page&limit=50", null, auth, isGet = true)
            if (result is AppResult.Failure) return result
            val root = (result as AppResult.Success).value.root
            val books = root.array("books") ?: return AppResult.Failure(AppError.Parse("响应缺少 books 字段"))
            val ids = books.map { (it as? JsonObject)?.stringOrNull("id") }
            if (ids.any { it == id }) return AppResult.Success(true)
            if (ids.any { it == null }) return AppResult.Failure(AppError.Parse("收藏列表缺少图书 ID"))
            val pagination = root.obj("pagination")
            val current = pagination?.intOrNull("current")
            val total = pagination?.intOrNull("total_pages")
            if (page == 1 && books.isEmpty() && total == 0) return AppResult.Success(false)
            if (current != page || total == null || total < current) {
                return AppResult.Failure(AppError.Parse("无法确认收藏列表的分页状态"))
            }
            if (current == total) return AppResult.Success(false)
            page++
        }
    }

    suspend fun setBookSaved(id: String, saved: Boolean): AppResult<Unit> {
        if (!NUMERIC_BOOK_ID.matches(id)) return AppResult.Failure(AppError.Parse("收藏需要数字图书 ID"))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        val path = if (saved) EapiEndpoints.saveBook(id) else EapiEndpoints.unsaveBook(id)
        return execute(path, null, auth, isGet = true).mapCatching { Unit }
    }

    private suspend fun accountBooks(path: String, page: Int, includeHistoryDate: Boolean): AppResult<BookPage> {
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        val requestedPage = page.coerceAtLeast(1)
        return execute("$path?page=$requestedPage&limit=50", form = null, session = auth, isGet = true)
            .mapCatching { exchange ->
                if (exchange.root.array("books") == null) {
                    throw AppErrorException(AppError.Parse("响应缺少 books 字段"))
                }
                exchange.root.toBookPage(requestedPage, includeHistoryDate)
            }
    }

    private suspend fun bookList(path: String, session: Session?): AppResult<List<Book>> =
        execute(path, form = null, session = session, isGet = true)
            .mapCatching { exchange -> BookMapper.listFrom(exchange.root) }

    // ---------------------------------------------------------------- domains

    /** Domain info is public; never send credentials here. */
    suspend fun domains(): AppResult<List<String>> = execute(
        path = EapiEndpoints.DOMAINS,
        form = null,
        session = null,
        isGet = true,
    ).mapCatching { exchange ->
        val array = exchange.root.array("domains") ?: throw AppErrorException(AppError.Parse("缺少 domains 字段"))
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
            return AppResult.Failure(AppError.Parse("搜索关键词不能为空"))
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

        return execute(EapiEndpoints.SEARCH, builder.build(), sessionProvider())
            .mapCatching { exchange -> exchange.root.toBookPage(requestedPage) }
    }

    private fun JsonObject.toBookPage(requestedPage: Int, includeHistoryDate: Boolean = false): BookPage {
        val pagination = obj("pagination")
        val books = BookMapper.listFrom(this, includeHistoryDate)
        val current = pagination?.intOrNull("current")?.takeIf { it > 0 } ?: requestedPage
        val hasExplicitEnd = pagination?.get("next")?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "false" } == true
        return BookPage(
            books = books,
            page = current,
            totalPages = if (books.isEmpty() || hasExplicitEnd) current else pagination?.intOrNull("total_pages")?.coerceAtLeast(current) ?: current,
        )
    }

    suspend fun bookDetail(id: String, hash: String): AppResult<Book> {
        if (!BookMapper.isSafeRef(id, hash)) {
            return AppResult.Failure(AppError.Parse("图书 ID 或 hash 无效"))
        }
        val auth = sessionProvider()
        return execute(EapiEndpoints.detail(id, hash), null, auth, isGet = true)
            .mapCatching { exchange ->
                val obj = exchange.root.obj("book") ?: exchange.root
                BookMapper.fromJson(obj) ?: throw AppErrorException(AppError.Parse("详情缺少 id/hash"))
            }
    }

    suspend fun bookFormats(id: String, hash: String): AppResult<List<BookFormat>> {
        if (!BookMapper.isSafeRef(id, hash)) return AppResult.Failure(AppError.Parse("图书 ID 或 hash 无效"))
        val auth = sessionProvider() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return execute(EapiEndpoints.formats(id, hash), null, auth, isGet = true).mapCatching { exchange ->
            val rows = exchange.root.array("books")
                ?: throw AppErrorException(AppError.Parse("格式响应缺少 books 列表"))
            val formats = rows.map { element ->
                val row = element as? JsonObject
                val formatId = row?.stringOrNull("id").orEmpty()
                val formatHash = row?.stringOrNull("hash").orEmpty()
                val extension = row?.stringOrNull("extension").orEmpty().trim().lowercase()
                if (!BookMapper.isSafeRef(formatId, formatHash) || !FILE_EXTENSION.matches(extension)) {
                    throw AppErrorException(AppError.Parse("格式列表包含无效文件信息，请重试"))
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
        return "${baseUrlForHost(auth.domain).trimEnd('/')}${EapiEndpoints.file(id, hash)}"
    }

    // ---------------------------------------------------------------- transport

    private suspend fun execute(
        path: String,
        form: FormBody?,
        session: Session?,
        isGet: Boolean = false,
        allowRetry: Boolean = true,
    ): AppResult<Exchange> {
        val base = baseUrlFor(session) ?: return AppResult.Failure(AppError.NoDomain)
        val url = base.trimEnd('/') + path
        var attempt = 1
        var lastError: AppError? = null
        var expiredRetried = false

        val attemptLimit = if (allowRetry) maxAttempts.coerceAtLeast(1) else 1
        while (attempt <= attemptLimit) {
            val outcome = try {
                withContext(dispatchers.io) { executeOnce(url, form, session, isGet, allowRetry) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (blocked: BlockedRedirectException) {
                Outcome.Err(AppError.DomainBlocked(DomainStatus.INVALID), retryable = false)
            } catch (illegal: IllegalArgumentException) {
                Outcome.Err(AppError.Parse("请求参数无效"), retryable = false)
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
                    sleeper(1_000L shl (attempt - 1))
                    attempt++
                }
            }
        }
        return AppResult.Failure(lastError ?: AppError.Unknown(IllegalStateException("EAPI request failed")))
    }

    private fun baseUrlFor(session: Session?): String? =
        session?.domain?.takeIf { it.isNotBlank() }?.let(baseUrlForHost) ?: baseUrlProvider()

    private fun executeOnce(
        url: String,
        form: FormBody?,
        session: Session?,
        isGet: Boolean,
        allowRetry: Boolean,
    ): Outcome {
        SafeLog.d(TAG, "request ${SafeLog.redactUrl(url)}")
        val builder = Request.Builder().url(url).markEapi()
        if (isGet) {
            builder.get()
        } else {
            builder.post(form ?: EMPTY_FORM)
        }
        builder.header("Accept", EapiHeaders.ACCEPT)
        builder.header("User-Agent", EapiHeaders.USER_AGENT)
        if (session != null) {
            if (session.cookieHeader.isNotBlank()) builder.header("Cookie", session.cookieHeader)
            builder.header(EapiHeaders.REMIX_USER_ID, session.remixUserId)
            builder.header(EapiHeaders.REMIX_USER_KEY, session.remixUserKey)
        }

        val transport = if (allowRetry) client else mutationClient
        transport.newCall(builder.build()).execute().use { response ->
            val code = response.code
            val body = response.body?.string().orEmpty()
            if (!allowRetry && response.isRedirect) {
                return Outcome.Err(AppError.Server(code, "删除请求发生重定向，请刷新记录后重试"), retryable = false)
            }
            val setCookies = response.headers.values("Set-Cookie")
            val root = parseObject(body)

            if (root != null) {
                when (root.intOrNull("success")) {
                    1 -> return Outcome.Ok(Exchange(root, setCookies, code))
                    0 -> return Outcome.Err(mapEnvelopeError(root, code), retryable = false)
                    else -> if (code in 200..299) {
                        return Outcome.Err(AppError.Parse("响应缺少 success 字段"), retryable = false)
                    }
                }
            }

            if (code == 513 || code == 517 || body.contains("diamwall", ignoreCase = true)) {
                return Outcome.Err(AppError.DomainBlocked(DomainStatus.DIAMWALL), retryable = false)
            }
            if (looksLikeHtml(body)) {
                return Outcome.Err(AppError.DomainBlocked(DomainStatus.CHALLENGED), retryable = false)
            }
            if (code == 502 || code == 503 || code == 504) {
                return Outcome.Err(AppError.Server(code, "服务暂时不可用（HTTP $code）"), retryable = true)
            }
            if (code == 401 || code == 403) {
                return Outcome.Err(AppError.DomainBlocked(DomainStatus.CHALLENGED), retryable = false)
            }
            return Outcome.Err(
                error = if (code in 200..299) {
                    AppError.Parse("服务器返回了非 JSON 响应")
                } else {
                    AppError.Server(code, "请求失败（HTTP $code）")
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
            else -> AppError.Server(httpCode, message.ifBlank { "请求失败（HTTP $httpCode）" })
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

    private fun hostOf(base: String): String? = runCatching { URI(base).host }.getOrNull()

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<") && (trimmed.contains("<html", true) || trimmed.contains("<!doctype", true))
    }

    private data class Exchange(
        val root: JsonObject,
        val setCookies: List<String>,
        val code: Int,
    )

    private sealed interface Outcome {
        data class Ok(val exchange: Exchange) : Outcome
        data class Err(val error: AppError, val retryable: Boolean) : Outcome
    }

    companion object {
        private const val TAG = "Eapi"
        private val NUMERIC_BOOK_ID = Regex("[0-9]{1,32}")
        private val FILE_EXTENSION = Regex("[a-z0-9]{1,20}")
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
