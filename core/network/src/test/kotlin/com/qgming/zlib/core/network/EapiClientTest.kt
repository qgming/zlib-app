package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.BookPage
import com.qgming.zlib.core.model.BookFormat
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.Session
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class EapiClientTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun client(session: Session? = null, maxAttempts: Int = 4, verificationCookie: String? = null): EapiClient {
        val base = server.url("/").toString().trimEnd('/')
        return EapiClient(
            client = OkHttpClient.Builder().build(),
            baseUrlProvider = { base },
            sessionProvider = { session },
            baseUrlForHost = { base },
            sleeper = {},
            maxAttempts = maxAttempts,
            isAllowedUrl = { it.host == server.hostName && it.port == server.port },
            verificationCookieProvider = { verificationCookie },
        )
    }

    private fun session() = Session(
        remixUserId = "7",
        remixUserKey = "key",
        domain = server.hostName,
        cookies = mapOf("remix_userid" to "7", "remix_userkey" to "key"),
    )

    private fun enqueueJson(json: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(json)

    @Test
    fun `login maps user and cookies`() {
        server.enqueue(
            enqueueJson("""{"success":1,"user":{"id":1234567,"remix_userkey":"secret-key"}}""")
                .addHeader("Set-Cookie", "remix_userid=1234567; Path=/; Secure")
                .addHeader("Set-Cookie", "remix_userkey=secret-key; Path=/; Secure"),
        )

        val result = kotlinx.coroutines.runBlocking { client().login(" me@example.com ", "pw") }

        val session = assertIs<AppResult.Success<Session>>(result).value
        assertEquals("1234567", session.remixUserId)
        assertEquals("secret-key", session.remixUserKey)
        assertEquals(server.hostName, session.domain)
        assertEquals("1234567", session.cookies["remix_userid"])
        assertEquals("secret-key", session.cookies["remix_userkey"])

        val request = server.takeRequest()
        assertEquals("/eapi/user/login", request.path)
        assertEquals("email=me%40example.com&password=pw", request.body.readUtf8())
        assertEquals("application/json", request.getHeader("Accept"))
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun `login accepts remix_user_key fallback and cookie-only credentials`() {
        server.enqueue(
            enqueueJson("""{"success":1,"user":{"id":"42","remix_user_key":"alt-key"}}"""),
        )
        val result = kotlinx.coroutines.runBlocking { client().login("a@b.c", "pw") }
        val session = assertIs<AppResult.Success<Session>>(result).value
        assertEquals("42", session.remixUserId)
        assertEquals("alt-key", session.remixUserKey)
    }

    @Test
    fun `login failure maps to AuthFailed`() {
        server.enqueue(enqueueJson("""{"success":0,"error":"Incorrect email or password"}"""))
        val result = kotlinx.coroutines.runBlocking { client().login("a@b.c", "bad") }
        assertIs<AppError.AuthFailed>(assertIs<AppResult.Failure>(result).error)
    }

    @Test
    fun `default transport policy rejects an unapproved origin before creating a request`() {
        val api = EapiClient(OkHttpClient(), { "https://mirror.example.test" }, { null })
        val result = kotlinx.coroutines.runBlocking { api.login("a@b.c", "unit-test-password") }
        assertIs<AppError.DomainBlocked>(assertIs<AppResult.Failure>(result).error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `login reports redirect without submitting again`() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/eapi/user/login"))
        val result = kotlinx.coroutines.runBlocking { client().login("a@b.c", "unit-test-password") }
        assertIs<AppError.DomainBlocked>(assertIs<AppResult.Failure>(result).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `verified EAPI request combines only the challenge cookie and account credentials`() {
        server.enqueue(enqueueJson("""{"success":1,"user":{"id":7}}"""))

        assertIs<AppResult.Success<*>>(kotlinx.coroutines.runBlocking {
            client(session(), verificationCookie = "preferences=unused; __diamwall=challenge-token; remix_userkey=foreign")
                .profile()
        })

        val request = server.takeRequest()
        assertEquals("remix_userid=7; remix_userkey=key; __diamwall=challenge-token", request.getHeader("Cookie"))
        assertEquals("7", request.getHeader(EapiHeaders.REMIX_USER_ID))
        assertEquals("key", request.getHeader(EapiHeaders.REMIX_USER_KEY))
    }

    @Test
    fun `login can use a manual challenge without importing browser account cookies`() {
        server.enqueue(enqueueJson("""{"success":1,"user":{"id":7,"remix_userkey":"key"}}"""))

        val result = kotlinx.coroutines.runBlocking {
            client(verificationCookie = "__diamwall=challenge-token; remix_userid=other; remix_userkey=foreign")
                .login("a@b.c", "unit-test-password")
        }

        val authenticated = assertIs<AppResult.Success<Session>>(result).value
        assertEquals(mapOf("remix_userid" to "7", "remix_userkey" to "key"), authenticated.cookies)
        val request = server.takeRequest()
        assertEquals("__diamwall=challenge-token", request.getHeader("Cookie"))
        assertNull(request.getHeader(EapiHeaders.REMIX_USER_ID))
        assertNull(request.getHeader(EapiHeaders.REMIX_USER_KEY))
    }

    @Test
    fun `verification and account cookies cannot follow a foreign origin`() {
        val destination = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/eapi/user/profile")))

            val result = kotlinx.coroutines.runBlocking {
                client(session(), verificationCookie = "__diamwall=challenge-token").profile()
            }

            assertIs<AppError.DomainBlocked>(assertIs<AppResult.Failure>(result).error)
            assertEquals(1, server.requestCount)
            assertEquals(0, destination.requestCount)
        } finally {
            destination.shutdown()
        }
    }

    @Test
    fun `EAPI cookies do not follow same origin redirects outside EAPI`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login"))

        val result = kotlinx.coroutines.runBlocking {
            client(session(), verificationCookie = "__diamwall=challenge-token").profile()
        }

        assertIs<AppError.DomainBlocked>(assertIs<AppResult.Failure>(result).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `session expired envelope is retried once`() {
        repeat(2) { server.enqueue(enqueueJson("""{"success":0,"error":"Please login"}""", code = 400)) }
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        assertEquals(AppError.SessionExpired, assertIs<AppResult.Failure>(result).error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `daily limit maps to quota without retry`() {
        server.enqueue(enqueueJson("""{"success":0,"error":"Daily limit reached"}""", code = 400))
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        assertIs<AppError.Quota>(assertIs<AppResult.Failure>(result).error)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `profile maps both quota field generations`() {
        server.enqueue(
            enqueueJson(
                """{"success":1,"user":{"id":42,"name":"Ada","email":"ada@example.com",
                "isPremium":1,"confirmed":0,"downloads_today":3,"downloads_limit":10}}""",
            ),
        )
        val account = assertIs<AppResult.Success<com.qgming.zlib.core.model.Account>>(
            kotlinx.coroutines.runBlocking { client(session()).profile() },
        ).value
        assertEquals("Ada", account.name)
        assertTrue(account.isPremium)
        assertEquals(3, account.quota.amount)
        assertEquals(10, account.quota.allowed)
        assertEquals(7, account.quota.remaining)
        assertTrue(account.quota.known)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/eapi/user/profile", request.path)
    }

    @Test
    fun `profile does not invent a standard tier when membership field is absent`() {
        server.enqueue(enqueueJson("""{"success":1,"user":{"id":7,"name":"Ada"}}"""))
        val account = assertIs<AppResult.Success<com.qgming.zlib.core.model.Account>>(
            kotlinx.coroutines.runBlocking { client(session()).profile() },
        ).value
        assertFalse(account.membershipKnown)
        assertFalse(account.quota.known)
    }

    @Test
    fun `search maps loose types and drops hashless books`() {
        server.enqueue(
            enqueueJson(
                """
                {"success":1,"pagination":{"current":"2","total_pages":5},"books":[
                  {"id":115066162,"hash":"e9f13b","title":"Book","author":"Author",
                   "year":2024,"publisher":"Pub","identifier":"978-0-306-40615-7, B00X4WHP5E",
                   "categories":[{"name":"Fiction"},{"title":"Classic"}],"category":"ignored",
                   "edition":"2","pages":"320","language":"english","cover":"https://cover",
                   "extension":"EPUB","filesize":"1234567","interestScore":"4.5",
                   "href":"/book/115066162/e9f13b","description":"desc","subtitle":"sub"},
                  {"id":2,"title":"No hash"},
                  {"id":3,"hash":"","title":"Empty hash"}
                ]}
                """.trimIndent(),
            ),
        )

        val query = SearchQuery(
            message = "dune",
            languages = listOf("English"),
            extensions = listOf("EPUB"),
            yearFrom = 1965,
            yearTo = 2024,
            exact = true,
            order = "popular",
        )
        val result = assertIs<AppResult.Success<BookPage>>(
            kotlinx.coroutines.runBlocking { client(session()).search(query, page = 2) },
        ).value

        assertEquals(1, result.books.size)
        assertEquals(2, result.page)
        assertEquals(5, result.totalPages)
        assertTrue(result.hasNext)

        val book = result.books.first()
        assertEquals("115066162", book.id)
        assertEquals("e9f13b", book.hash)
        assertEquals("Author", book.authors)
        assertEquals("2024", book.year)
        assertEquals("9780306406157", book.isbn13)
        assertEquals("0306406152", book.isbn10)
        assertEquals(listOf("Fiction", "Classic"), book.categories)
        assertEquals("epub", book.extension)
        assertEquals(1_234_567L, book.sizeBytes)
        assertEquals(320, book.pages)
        assertEquals(4.5, book.rating)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("message=dune"))
        assertTrue(body.contains("page=2"))
        assertTrue(body.contains("limit=50"))
        assertTrue(body.contains("languages%5B%5D=english"))
        assertTrue(body.contains("extensions%5B%5D=epub"))
        assertTrue(body.contains("e=1"))
        assertTrue(body.contains("yearFrom=1965"))
        assertTrue(body.contains("order=popular"))
        assertEquals("7", request.getHeader("remix-userid"))
        assertEquals("key", request.getHeader("remix-userkey"))
        assertEquals("android", request.getHeader(EapiHeaders.SOURCE))
    }

    @Test
    fun `search identifies the android client source`() {
        server.enqueue(enqueueJson("""{"success":1,"books":[],"pagination":{"current":1,"total_pages":0}}"""))

        val result = kotlinx.coroutines.runBlocking { client().search(SearchQuery("dune")) }
        assertIs<AppResult.Success<BookPage>>(result)

        val request = server.takeRequest()
        assertEquals("/eapi/book/search", request.path)
        assertEquals("android", request.getHeader(EapiHeaders.SOURCE))
    }

    @Test
    fun `search access denial is classified without retry for both envelope statuses and languages`() {
        for (code in listOf(200, 400, 403)) {
            for (message in listOf("Access denied", "  ACCESS DENIED  ", "拒绝访问")) {
                val previous = server.requestCount
                server.enqueue(enqueueJson("""{"success":0,"error":"$message"}""", code))
                val result = kotlinx.coroutines.runBlocking { client(session()).search(SearchQuery("book")) }
                assertEquals(AppResult.Failure(AppError.SearchRefused), result)
                assertEquals(previous + 1, server.requestCount)
            }
        }
    }

    @Test
    fun `non-search access denial and unrelated search failures retain their server errors`() {
        server.enqueue(enqueueJson("""{"success":0,"error":"Access denied"}""", 400))
        val profile = kotlinx.coroutines.runBlocking { client(session()).profile() }
        assertEquals(AppResult.Failure(AppError.Server(400, "Access denied")), profile)

        server.enqueue(enqueueJson("""{"success":0,"error":"Search unavailable"}""", 400))
        val search = kotlinx.coroutines.runBlocking { client(session()).search(SearchQuery("book")) }
        assertEquals(AppResult.Failure(AppError.Server(400, "Search unavailable")), search)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `saved books use an authenticated paged GET`() {
        server.enqueue(
            enqueueJson(
                """{"success":1,"pagination":{"current":"1","total_pages":"3"},
                    "books":[{"id":7,"hash":"abc","title":"Saved book"},{"id":8,"title":"Missing hash"}]}""",
            ),
        )
        val result = assertIs<AppResult.Success<BookPage>>(
            kotlinx.coroutines.runBlocking { client(session()).savedBooks(page = 0) },
        ).value
        assertEquals(listOf("7:abc"), result.books.map { it.ref })
        assertEquals(1, result.page)
        assertEquals(3, result.totalPages)
        assertTrue(result.hasNext)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/eapi/user/book/saved?page=1&limit=50", request.path)
        assertEquals("7", request.getHeader(EapiHeaders.REMIX_USER_ID))
        assertEquals("key", request.getHeader(EapiHeaders.REMIX_USER_KEY))
        assertEquals("remix_userid=7; remix_userkey=key", request.getHeader("Cookie"))
    }

    @Test
    fun `download history maps date fields without assigning a local file`() {
        server.enqueue(
            enqueueJson(
                """{"success":1,"pagination":{"current":2,"total_pages":2},"books":[
                    {"id":7,"hash":"abc","date_download":"2026-11-20","date":"ignored","downloaded_at":"ignored"},
                    {"id":8,"hash":"def","date":"","downloaded_at":"2026-11-17"},
                    {"id":9,"hash":"ghi","date":"2026-11-16"}]}""",
            ),
        )
        val result = assertIs<AppResult.Success<BookPage>>(
            kotlinx.coroutines.runBlocking { client(session()).downloadHistory(page = 2) },
        ).value
        assertEquals(listOf("2026-11-20", "2026-11-17", "2026-11-16"), result.books.map { it.historyDate })
        assertTrue(result.books.all { it.localUri == null && it.downloadedAt == null })
        assertFalse(result.hasNext)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/eapi/user/book/downloaded?page=2&limit=50", request.path)
    }

    @Test
    fun `account book lists require a session`() {
        val anonymous = client()
        kotlinx.coroutines.runBlocking {
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), anonymous.savedBooks(page = 1))
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), anonymous.downloadHistory(page = 1))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `favorite lookup checks later pages by ID even without a download hash`() {
        server.enqueue(enqueueJson("""{"success":1,"books":[{"id":1,"hash":"abc"}],"pagination":{"current":1,"total_pages":2}}"""))
        server.enqueue(enqueueJson("""{"success":1,"books":[{"id":7}],"pagination":{"current":2,"total_pages":2}}"""))
        val saved = kotlinx.coroutines.runBlocking { client(session()).isBookSaved("7") }
        assertEquals(AppResult.Success(true), saved)
        assertEquals("/eapi/user/book/saved?page=1&limit=50", server.takeRequest().path)
        assertEquals("/eapi/user/book/saved?page=2&limit=50", server.takeRequest().path)
    }

    @Test
    fun `favorite lookup requires a complete list before reporting not saved`() {
        server.enqueue(enqueueJson("""{"success":1,"books":[],"pagination":{"current":1,"total_pages":0}}"""))
        assertEquals(AppResult.Success(false), kotlinx.coroutines.runBlocking { client(session()).isBookSaved("7") })
        server.enqueue(enqueueJson("""{"success":1,"books":[{"id":1}]}"""))
        val result = kotlinx.coroutines.runBlocking { client(session()).isBookSaved("7") }
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(result).error)
    }

    @Test
    fun `save and unsave use the authenticated EAPI and preserve server failures`() {
        server.enqueue(enqueueJson("""{"success":1}"""))
        assertEquals(AppResult.Success(Unit), kotlinx.coroutines.runBlocking { client(session()).setBookSaved("7", true) })
        val savedRequest = server.takeRequest()
        assertEquals("GET", savedRequest.method)
        assertEquals("/eapi/user/book/7/save", savedRequest.path)
        assertEquals("7", savedRequest.getHeader(EapiHeaders.REMIX_USER_ID))
        assertEquals("key", savedRequest.getHeader(EapiHeaders.REMIX_USER_KEY))
        assertNotNull(savedRequest.getHeader("Cookie"))

        server.enqueue(enqueueJson("""{"success":0,"error":"Cannot remove"}""", 400))
        val failure = kotlinx.coroutines.runBlocking { client(session()).setBookSaved("7", false) }
        assertEquals("Cannot remove", assertIs<AppError.Server>(assertIs<AppResult.Failure>(failure).error).message)
        assertEquals("/eapi/user/book/7/unsave", server.takeRequest().path)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `favorite operations reject unsafe IDs and anonymous calls without network requests`() {
        kotlinx.coroutines.runBlocking {
            val authenticated = client(session())
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(authenticated.setBookSaved("7/unsave", true)).error)
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(authenticated.isBookSaved("../7")).error)
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), client().isBookSaved("7"))
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), client().setBookSaved("7", false))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `formats map sparse file records and keep two distinct files of the same extension`() {
        server.enqueue(enqueueJson("""{"success":1,"books":[
            {"id":8,"hash":"epub1","extension":"EPUB","filesize":"2048","filesizeString":"2 KB"},
            {"id":"9","hash":"epub2","extension":"epub","filesize":4096},
            {"id":10,"hash":"pdf1","extension":"pdf","filesizeString":"1.5 MB"},
            {"id":8,"hash":"epub1","extension":"epub"}]}"""))
        val formats = assertIs<AppResult.Success<List<BookFormat>>>(
            kotlinx.coroutines.runBlocking { client(session()).bookFormats("7", "original") },
        ).value
        assertEquals(listOf("8:epub1", "9:epub2", "10:pdf1"), formats.map { it.ref })
        assertEquals(listOf("epub", "epub", "pdf"), formats.map { it.extension })
        assertEquals(2048L, formats.first().sizeBytes)
        assertEquals("2 KB", formats.first().sizeText)
        assertNull(formats.last().sizeBytes)
        assertEquals("1.5 MB", formats.last().sizeText)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/eapi/book/7/original/formats", request.path)
        assertEquals("7", request.getHeader("remix-userid"))
        assertEquals("key", request.getHeader("remix-userkey"))
        assertNotNull(request.getHeader("Cookie"))
    }

    @Test
    fun `formats distinguish an empty response from missing or invalid file records`() {
        server.enqueue(enqueueJson("""{"success":1,"books":[]}"""))
        assertEquals(AppResult.Success(emptyList()), kotlinx.coroutines.runBlocking { client(session()).bookFormats("7", "abc") })
        for (body in listOf(
            """{"success":1}""",
            """{"success":1,"books":[{"id":8,"extension":"pdf"}]}""",
            """{"success":1,"books":[{"id":8,"hash":"def","extension":"../epub"}]}""",
        )) {
            server.enqueue(enqueueJson(body))
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(
                kotlinx.coroutines.runBlocking { client(session()).bookFormats("7", "abc") },
            ).error)
        }
        server.enqueue(enqueueJson("""{"success":0,"error":"Formats unavailable"}""", 400))
        val failure = kotlinx.coroutines.runBlocking { client(session()).bookFormats("7", "abc") }
        assertEquals("Formats unavailable", assertIs<AppError.Server>(assertIs<AppResult.Failure>(failure).error).message)
    }

    @Test
    fun `format and deletion requests reject unsafe references and missing sessions`() {
        kotlinx.coroutines.runBlocking {
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client(session()).bookFormats("../7", "abc")).error)
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), client().bookFormats("7", "abc"))
            for (id in listOf("7:abc", "../7", "7/delete", "abc", "")) {
                assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client(session()).deleteDownloadHistory(id)).error)
            }
            assertEquals(AppResult.Failure(AppError.NotLoggedIn), client().deleteDownloadHistory("7"))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `history deletion uses the authenticated numeric endpoint and preserves refused changes`() {
        server.enqueue(enqueueJson("""{"success":1}"""))
        assertEquals(AppResult.Success(Unit), kotlinx.coroutines.runBlocking { client(session()).deleteDownloadHistory("7") })
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/eapi/user/book/7/delete", request.path)
        assertEquals("7", request.getHeader("remix-userid"))
        assertEquals("key", request.getHeader("remix-userkey"))
        assertNotNull(request.getHeader("Cookie"))
        server.enqueue(enqueueJson("""{"success":0,"error":"Cannot delete"}""", 400))
        val failure = kotlinx.coroutines.runBlocking { client(session()).deleteDownloadHistory("7") }
        assertEquals("Cannot delete", assertIs<AppError.Server>(assertIs<AppResult.Failure>(failure).error).message)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `destructive GET never retries transient status or expired session and never follows redirects`() {
        for (response in listOf(
            MockResponse().setResponseCode(503).setBody("busy"),
            enqueueJson("""{"success":0,"error":"Please login"}""", 400),
            MockResponse().setResponseCode(302).addHeader("Location", server.url("/eapi/user/book/7/delete")),
        )) {
            val previous = server.requestCount
            server.enqueue(response)
            assertIs<AppResult.Failure>(kotlinx.coroutines.runBlocking { client(session()).deleteDownloadHistory("7") })
            assertEquals(previous + 1, server.requestCount)
        }
    }

    @Test
    fun `503 is retried with backoff then fails`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503).setBody("busy")) }
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        val error = assertIs<AppError.Server>(assertIs<AppResult.Failure>(result).error)
        assertEquals(503, error.code)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `non json success is a parse error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(result).error)
    }

    @Test
    fun `search and discovery require a book array but accept an empty array`() = kotlinx.coroutines.runBlocking {
        server.enqueue(enqueueJson("""{"success":1}"""))
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client().search(SearchQuery("book"))).error)
        server.enqueue(enqueueJson("""{"success":1,"books":{}}"""))
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client().mostPopular()).error)
        server.enqueue(enqueueJson("""{"success":1,"books":[]}"""))
        assertTrue(assertIs<AppResult.Success<BookPage>>(client().search(SearchQuery("book"))).value.books.isEmpty())
    }

    @Test
    fun `html challenge maps to domain blocked`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html><title>Just a moment...</title></html>"))
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        val error = assertIs<AppError.DomainBlocked>(assertIs<AppResult.Failure>(result).error)
        assertEquals(com.qgming.zlib.core.common.DomainStatus.CHALLENGED, error.status)
    }

    @Test
    fun `download url is derived from session domain`() {
        val url = client(session()).downloadUrl("1", "abc")
        assertEquals("${server.url("/").toString().trimEnd('/')}/eapi/book/1/abc/file", url)
        assertNull(client(null).downloadUrl("1", "abc"))
    }
}
