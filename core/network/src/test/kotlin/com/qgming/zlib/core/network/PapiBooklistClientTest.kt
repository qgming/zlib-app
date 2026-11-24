package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.getOrNull
import com.qgming.zlib.core.model.BooklistPage
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class PapiBooklistClientTest {
    @Test
    fun `EAPI login supplies only remix cookies to PAPI and reads books with server next page`() = withServer { server, client ->
        server.enqueue(json("""{"success":1,"user":{"id":7,"remix_userkey":"unit-test-key"}}""")
            .setHeader("Set-Cookie", "other=not-sent; Path=/"))
        server.enqueue(json(fixture("booklists.json")))
        server.enqueue(json(fixture("booklist_books.json")))
        val eapi = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null }, maxAttempts = 1)
        val signedIn = assertNotNull(eapi.login("account@example.test", "synthetic-password").getOrNull()).withDomain(session.domain)
        assertEquals("/eapi/user/login", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
        assertEquals("not-sent", signedIn.cookies["other"])
        val booklists = assertNotNull(client.myBooklists(signedIn).getOrNull())
        assertEquals(listOf("42", "57"), booklists.map { it.id })
        assertEquals(2, booklists.first().bookCount)
        assertNull(booklists.last().bookCount)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/papi/booklist/current-user/0", request.path)
        assertEquals("remix_userid=7; remix_userkey=unit-test-key", request.getHeader("Cookie"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertNull(request.getHeader("remix-userid"))
        assertNull(request.getHeader("remix-userkey"))
        assertNull(request.getHeader("source"))
        val page = assertIs<AppResult.Success<BooklistPage>>(client.books(signedIn, "42", 1)).value
        assertEquals("123:abcd12", page.books.single().ref)
        assertEquals("", page.books.single().href)
        assertEquals(2, page.nextPage)
        val post = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("POST", post.method)
        assertEquals("/papi/booklist/42/get-books/1", post.path)
        assertEquals("application/x-www-form-urlencoded", post.getHeader("Content-Type"))
        assertEquals(0L, post.bodySize)
        assertEquals(request.getHeader("Cookie"), post.getHeader("Cookie"))
    }

    @Test
    fun `empty arrays are valid but invalid or non progressing responses are not`() = withServer { server, client ->
        server.enqueue(json("""{"success":1,"list":[]}"""))
        assertEquals(AppResult.Success(emptyList()), client.myBooklists(session))
        server.enqueue(json("""{"success":1,"books":[],"pagination":{"next":2}}"""))
        assertEquals(AppResult.Success(BooklistPage(emptyList(), null)), client.books(session, "42", 1))
        for (body in listOf(
            """{"success":1,"books":[{"id":9,"hash":"abc"}]}""",
            """{"success":1,"books":[{"book":{"id":9}}]}""",
            """{"success":1,"books":[{"book":{"id":9,"hash":true}}]}""",
            fixture("booklist_books.json").replace("\"2\"", "1"),
            "{invalid-json",
        )) {
            server.enqueue(json(body))
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client.books(session, "42", 1)).error)
        }
    }

    @Test
    fun `explicit end wins over total and missing next falls back to known page count`() = withServer { server, client ->
        val fixture = fixture("booklist_books.json")
        for (end in listOf("false", "null", "0")) {
            server.enqueue(json(fixture.replace("\"next\":\"2\"", "\"next\":$end")))
            assertNull(assertIs<AppResult.Success<BooklistPage>>(client.books(session, "42", 1)).value.nextPage)
        }
        server.enqueue(json(fixture.replace("\"next\":\"2\",", "")))
        assertEquals(2, assertIs<AppResult.Success<BooklistPage>>(client.books(session, "42", 1)).value.nextPage)
    }

    @Test
    fun `my booklists accept up to four distinct cover previews`() = withServer { server, client ->
        val covers = listOf("10", "10", "11", "12", "13", "14").joinToString(",") { id ->
            """{"id":"$id","cover":"https://images.example.test/$id.jpg"}"""
        }
        server.enqueue(json("""{"success":1,"list":[{"id":42,"title":"Reading","books":[$covers]}]}"""))
        val list = assertNotNull(client.myBooklists(session).getOrNull()).single()
        assertEquals(listOf("10", "11", "12", "13"), list.covers.map { it.bookId })
    }

    @Test
    fun `untrusted hosts invalid paths and invalid credential values fail before network`() = withServer { server, client ->
        for (host in listOf("outside.example", "z-lib.gd.example", "z-lib.gd:443", "z-lib.gd/path")) {
            assertEquals(AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID)), client.myBooklists(session.copy(domain = host)))
        }
        assertIs<AppResult.Failure>(client.books(session, "42", 0))
        assertEquals(AppResult.Failure(AppError.NotLoggedIn), client.myBooklists(session.copy(remixUserKey = "key; extra=other")))
        for (id in listOf("", "0", "01", "42/", "../other", "abc")) {
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client.books(session, id, 1)).error)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `redirects auth rejection and verification are not followed retried or treated as empty`() = withServer { server, client ->
        MockWebServer().use { target ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target.url("/")))
            assertEquals(AppResult.Failure(AppError.DomainBlocked(DomainStatus.REDIRECT_LOOP)), client.books(session, "42", 1))
            assertEquals(0, target.requestCount)
        }
        server.enqueue(json("""{"success":0,"error":{"message":"Please login"}}""", 401))
        assertIs<AppError.Unsupported>(assertIs<AppResult.Failure>(client.myBooklists(session)).error)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>challenge</html>"))
        assertEquals(AppResult.Failure(AppError.DomainBlocked(DomainStatus.CHALLENGED)), client.myBooklists(session))
        server.enqueue(json("""{"success":1,"list":[]}""", 503))
        assertIs<AppError.Server>(assertIs<AppResult.Failure>(client.myBooklists(session)).error)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `503 with Retry-After is not replayed by OkHttp`() = withServer { server, client ->
        server.enqueue(json("""{"success":1,"list":[]}""", 503).setHeader("Retry-After", "0"))
        server.enqueue(json("""{"success":1,"list":[]}"""))
        assertIs<AppError.Server>(assertIs<AppResult.Failure>(client.myBooklists(session)).error)
        assertEquals(1, server.requestCount)
    }

    private fun withServer(block: suspend (MockWebServer, PapiBooklistClient) -> Unit) {
        runBlocking {
            MockWebServer().use { server ->
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    assertEquals(session.domain, request.url.host)
                    assertEquals(true, request.url.isHttps)
                    val url = server.url(request.url.encodedPath)
                    chain.proceed(request.newBuilder().url(url).build())
                }.build()
                block(server, PapiBooklistClient(http, DomainPolicy::isBuiltIn))
            }
        }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/papi/$name")).readText()

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private val session = Session("7", "unit-test-key", "z-lib.gd", mapOf("other" to "not-sent"))
}
