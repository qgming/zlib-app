package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.Session
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun client(session: Session? = null, maxAttempts: Int = 3): EapiClient {
        val base = server.url("/").toString().trimEnd('/')
        return EapiClient(
            client = OkHttpClient.Builder().build(),
            baseUrlProvider = { base },
            sessionProvider = { session },
            baseUrlForHost = { base },
            sleeper = {},
            maxAttempts = maxAttempts,
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
    fun `session expired envelope is detected`() {
        server.enqueue(enqueueJson("""{"success":0,"error":"Please login"}""", code = 400))
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        assertEquals(AppError.SessionExpired, assertIs<AppResult.Failure>(result).error)
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
        val result = assertIs<AppResult.Success<com.qgming.zlib.core.model.SearchResult>>(
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
    }

    @Test
    fun `503 is retried with backoff then fails`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("busy")) }
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        val error = assertIs<AppError.Server>(assertIs<AppResult.Failure>(result).error)
        assertEquals(503, error.code)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `non json success is a parse error`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json"))
        val result = kotlinx.coroutines.runBlocking { client(session()).profile() }
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(result).error)
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
