package com.qgming.zlib.core.network

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class CredentialStripInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var cdn: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        cdn = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        cdn.shutdown()
    }

    private fun requestWithCredentials(url: String): Request = Request.Builder()
        .url(url)
        .header("Cookie", "remix_userid=1; remix_userkey=k")
        .header("remix-userid", "1")
        .header("remix-userkey", "k")
        .build()

    @Test
    fun `credentials are stripped when the target is not allowed`() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { false }))
            .build()

        client.newCall(requestWithCredentials(server.url("/file").toString())).execute().close()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Cookie"))
        assertNull(recorded.getHeader("remix-userid"))
        assertNull(recorded.getHeader("remix-userkey"))
    }

    @Test
    fun `credentials are kept on the first allowed host`() {
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { true }, requireHttps = false))
            .build()

        client.newCall(requestWithCredentials(server.url("/file").toString())).execute().close()

        val recorded = server.takeRequest()
        assertEquals("remix_userid=1; remix_userkey=k", recorded.getHeader("Cookie"))
        assertEquals("1", recorded.getHeader("remix-userid"))
    }

    @Test
    fun `cross-host redirect to a CDN strips credentials`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/book.epub").toString()))
        cdn.enqueue(MockResponse().setBody("content"))

        val allowed = server.port
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { url -> url.port == allowed }, requireHttps = false))
            .build()

        client.newCall(requestWithCredentials(server.url("/file").toString())).execute().close()

        val first = server.takeRequest()
        assertEquals("remix_userid=1; remix_userkey=k", first.getHeader("Cookie"))

        val second = cdn.takeRequest()
        assertEquals("/book.epub", second.path)
        assertNull(second.getHeader("Cookie"))
        assertNull(second.getHeader("remix-userid"))
        assertNull(second.getHeader("remix-userkey"))
    }

    @Test
    fun `a different port is another origin even if both targets are allowed`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdn.url("/x").toString()))
        cdn.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { true }, requireHttps = false))
            .build()

        client.newCall(requestWithCredentials(server.url("/start").toString())).execute().close()

        server.takeRequest()
        val second = cdn.takeRequest()
        assertNull(second.getHeader("Cookie"))
    }

    @Test
    fun `same-origin redirect keeps allowed credentials`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next"))
        server.enqueue(MockResponse().setBody("{}"))
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(CredentialStripInterceptor(isAllowedUrl = { true }, requireHttps = false))
            .build()

        client.newCall(requestWithCredentials(server.url("/start").toString())).execute().close()

        server.takeRequest()
        assertEquals("remix_userid=1; remix_userkey=k", server.takeRequest().getHeader("Cookie"))
    }
}
