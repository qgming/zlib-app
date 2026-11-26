package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.model.BookComment
import com.qgming.zlib.core.model.BookCommentsPage
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

class PapiCommentsClientTest {
    @Test
    fun `GET uses zero based page with cookie only transport and leaves comment markup inert`() = withServer { server, client ->
        server.enqueue(json("""{"success":1,"commentsDisabled":false,"comments":[
            {"id":"102","parent_id":"101","text":"Line one\r\nLine two <b>plain</b>","date":"fallback",
                "user":{"name":" Reader ","dateRelative":"yesterday","avatar":"https://images.cdn-zlib.sk/avatar.jpg","cover":"/img/profile/cover-0.png"}},
            {"id":101,"parent_id":null,"text":"Original","date":"2026-11-06 12:00:00",
                "user":{"avatar":"","cover":"/img/profile/cover-0.png"}},
            {"id":101,"text":"duplicate"}
        ],"reactionsInfo":{"reactions":[{"item_id":"101","count":"3"},{"item_id":102,"count":1},{"item_id":102,"count":-2}]}}"""))
        val page = assertIs<AppResult.Success<BookCommentsPage>>(client.comments(session, "321", 0)).value
        assertEquals(listOf(
            BookComment("102", "101", "Line one\nLine two <b>plain</b>", "Reader", "yesterday", "https://images.cdn-zlib.sk/avatar.jpg", 1),
            BookComment("101", null, "Original", "", "2026-11-06 12:00:00", "https://zlib.bz/img/profile/cover-0.png", 3),
        ), page.comments)
        assertEquals(false, page.disabled)
        assertNull(page.nextPage)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/papi/comments/book/321/0?limit=50", request.path)
        assertEquals("remix_userid=7; remix_userkey=local-test-key", request.getHeader("Cookie"))
        assertNull(request.getHeader("source"))
        assertNull(request.getHeader("remix-userid"))
        assertNull(request.getHeader("remix-userkey"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `indexed likes ignore invalid counts and avatar urls stay on approved HTTPS hosts`() = withServer { server, client ->
        server.enqueue(json("""{"success":1,"comments":[
            {"id":1,"text":"First","user":{"avatar":"http://zlib.bz/img/1.jpg","cover":"/img/profile/default.png"}},
            {"id":2,"text":"Second","user":{"avatar":"https://zlib.bz.evil.test/2.jpg","cover":"//outside.example.test/2.jpg"}},
            {"id":3,"text":"Third","user":{"avatar":"https://user:pass@zlib.bz/3.jpg","cover":"/3.jpg?key=not-sent"}},
            {"id":4,"text":"Fourth","user":{"avatar":"https://zlib.bz:444/4.jpg","cover":"https://zlib.bz/4.jpg#fragment"}},
            {"id":5,"text":"Fifth","user":{"avatar":"https://avatars.cdn-zlib.sk/5.jpg"}}
        ],"reactionsInfo":{"reactions":{"1":{"count":"4"},"2":7,"3":-1,"4":"bad","5":{"count":0}}}}"""))
        val comments = assertIs<AppResult.Success<BookCommentsPage>>(client.comments(session, "321", 0)).value.comments
        assertEquals(listOf(4, 7, 0, 0, 0), comments.map(BookComment::likeCount))
        assertEquals(listOf("https://zlib.bz/img/profile/default.png", null, null, null, "https://avatars.cdn-zlib.sk/5.jpg"),
            comments.map(BookComment::avatarUrl))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `full pages continue up to the cap and disabled is distinct from empty`() = withServer { server, client ->
        val comments = (1..50).joinToString(",") { """{"id":$it,"text":"comment"}""" }
        server.enqueue(json("""{"success":1,"comments":[$comments]}"""))
        assertEquals(2, assertIs<AppResult.Success<BookCommentsPage>>(client.comments(session, "321", 1)).value.nextPage)
        server.enqueue(json("""{"success":1,"comments":[$comments]}"""))
        assertNull(assertIs<AppResult.Success<BookCommentsPage>>(client.comments(session, "321", 19)).value.nextPage)
        server.enqueue(json("""{"success":1,"commentsDisabled":true}"""))
        assertEquals(AppResult.Success(BookCommentsPage(disabled = true)), client.comments(session, "321", 0))
        server.enqueue(json("""{"success":1,"comments":[]}"""))
        assertEquals(AppResult.Success(BookCommentsPage()), client.comments(session, "321", 0))
        server.enqueue(json("""{"success":1,"comments":{}}"""))
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client.comments(session, "321", 0)).error)
        server.enqueue(json("""{"comments":[]}"""))
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(client.comments(session, "321", 0)).error)
    }

    @Test
    fun `untrusted hosts and invalid route arguments never send credentials and redirects are not followed`() = withServer { server, client ->
        assertEquals(AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID)), client.comments(session.copy(domain = "zlib.bz.example.test"), "321", 0))
        assertIs<AppResult.Failure>(client.comments(session, "321/other", 0))
        assertIs<AppResult.Failure>(client.comments(session, "321", -1))
        assertIs<AppResult.Failure>(client.comments(session, "321", 20))
        assertEquals(AppResult.Failure(AppError.NotLoggedIn), client.comments(session.copy(remixUserKey = "bad; value"), "321", 0))
        assertEquals(0, server.requestCount)
        MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/")))
            assertEquals(AppResult.Failure(AppError.DomainBlocked(DomainStatus.REDIRECT_LOOP)), client.comments(session, "321", 0))
            assertEquals(0, other.requestCount)
        }
        server.enqueue(json("""{"success":0,"error":"Please log in"}""", 403))
        assertIs<AppError.Unsupported>(assertIs<AppResult.Failure>(client.comments(session, "321", 0)).error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `text html content type carrying valid comments JSON parses cleanly`() = withServer { server, client ->
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("""{"success":1,"commentsDisabled":false,"comments":[{"id":1,"text":"Hello"}]}"""),
        )
        val page = assertIs<AppResult.Success<BookCommentsPage>>(client.comments(session, "321", 0)).value
        assertEquals(listOf("1"), page.comments.map(BookComment::id))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<!doctype html><html><body>Just a moment...</body></html>"),
        )
        assertEquals(AppResult.Failure(AppError.DomainBlocked(DomainStatus.CHALLENGED)), client.comments(session, "321", 0))
    }

    private fun withServer(block: suspend (MockWebServer, PapiCommentsClient) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                assertEquals("zlib.bz", request.url.host)
                assertEquals(true, request.url.isHttps)
                val url = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(url).build())
            }.build()
            block(server, PapiCommentsClient(http, DomainPolicy::isBuiltIn))
        }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private val session = Session("7", "local-test-key", "zlib.bz", mapOf("private-cookie" to "not sent"))
}
