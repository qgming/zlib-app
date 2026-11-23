package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.SearchSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SimilarBooksTest {
    @Test fun `similar is a single finite list with safe references and no pagination`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"success":1,"books":[
                {"id":8,"hash":"next","title":"Other","author":"Author","coverData":"<x>","url":"/book/not-the-hash"},
                {"id":8,"hash":"next","title":"Duplicate"},
                {"id":9,"title":"No hash"},{"id":7,"hash":"original","title":"Self"}]}"""))
            val result = assertIs<AppResult.Success<List<Book>>>(client(server).similarBooks("7", "original")).value
            assertEquals(listOf("8:next"), result.map { it.ref })
            assertEquals("Author", result.single().authors)
            assertEquals("/eapi/book/7/original/similar", server.takeRequest().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `similar empty success differs from malformed and server failure`() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            server.enqueue(json("""{"success":1,"books":[]}"""))
            assertEquals(emptyList(), assertIs<AppResult.Success<List<Book>>>(client.similarBooks("7", "original")).value)
            for (body in listOf("""{"success":1}""", """{"success":1,"books":{}}""", """{"success":0,"error":"无效哈希"}""")) {
                server.enqueue(json(body))
                assertIs<AppResult.Failure>(client.similarBooks("7", "original"))
            }
        }
    }

    @Test fun `all documented search sort values preserve exact case and default omits order`() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            for (sort in SearchSort.entries) {
                server.enqueue(json("""{"success":1,"books":[],"pagination":{"current":1,"total_pages":1}}"""))
                assertIs<AppResult.Success<*>>(client.search(SearchQuery("book", order = sort.wireValue), 1))
                val form = ("https://example.test/?" + server.takeRequest().body.readUtf8()).toHttpUrl()
                assertEquals(sort.wireValue, form.queryParameter("order"))
                assertNull(form.queryParameter("direction"))
                assertEquals("50", form.queryParameter("limit"))
            }
        }
    }

    private fun client(server: MockWebServer) = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null }, maxAttempts = 1)
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
