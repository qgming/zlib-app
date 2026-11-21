package com.qgming.zlib.core.network

import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class EapiRedirectGuardInterceptorTest {

    private lateinit var origin: MockWebServer
    private lateinit var other: MockWebServer

    @BeforeTest
    fun setUp() {
        origin = MockWebServer().apply { start() }
        other = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        origin.shutdown()
        other.shutdown()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(
            EapiRedirectGuardInterceptor(isAllowedUrl = { url -> url.port == origin.port }),
        )
        .build()

    @Test
    fun `eapi redirect to another host is rejected before it is followed`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/x").toString()))
        other.enqueue(MockResponse().setBody("{}"))

        val request = Request.Builder().url(origin.url("/eapi/login")).markEapi().build()

        assertFailsWith<IOException> { client().newCall(request).execute().close() }
        assertEquals(0, other.requestCount)
    }

    @Test
    fun `non-eapi requests may still follow cdn redirects`() {
        origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/book.epub").toString()))
        other.enqueue(MockResponse().setBody("content"))

        val request = Request.Builder().url(origin.url("/file")).build()
        client().newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("content", response.body?.string())
        }
        assertEquals(1, other.requestCount)
    }
}
