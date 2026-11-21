package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DomainProbeTest {

    private fun probeOn(server: MockWebServer, allowed: (String) -> Boolean = { it == "probe.test" }): DomainProbe {
        val base = server.url("/").toString().trimEnd('/')
        return DomainProbe(
            client = OkHttpClient.Builder().build(),
            isAllowedHost = allowed,
            baseUrlFor = { base },
            requireHttps = false,
            timeoutMillis = 5_000,
        )
    }

    private fun runProbe(server: MockWebServer, allowed: (String) -> Boolean = { it == "probe.test" }) =
        runBlocking { probeOn(server, allowed).probe("probe.test") }

    @Test
    fun `healthy json is classified healthy`() {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"success":1,"domains":[{"domain":"z-lib.gd"},{"domain":"z-lib.sk"}]}"""),
            )
            start()
        }
        try {
            val check = runProbe(server)
            assertEquals(DomainStatus.HEALTHY, check.status)
            assertEquals("probe.test", check.host)
            assertEquals("/eapi/info/domains", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `html challenge is classified challenged`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(403).setBody("<html><title>Just a moment...</title></html>"))
            start()
        }
        try {
            assertEquals(DomainStatus.CHALLENGED, runProbe(server).status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `513 and diamwall are classified diamwall`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(513).setBody("DiamWall"))
            start()
        }
        try {
            assertEquals(DomainStatus.DIAMWALL, runProbe(server).status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `http errors are classified http error`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(500).setBody("oops"))
            start()
        }
        try {
            val check = runProbe(server)
            assertEquals(DomainStatus.HTTP_ERROR, check.status)
            assertEquals("HTTP 500", check.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `self redirect is a redirect loop`() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(302).setHeader("Location", "/eapi/info/domains")
            }
            start()
        }
        try {
            assertEquals(DomainStatus.REDIRECT_LOOP, runProbe(server, allowed = { true }).status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `redirect outside the allowlist is invalid`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.test/"))
            start()
        }
        try {
            val check = runProbe(server, allowed = { it == "probe.test" })
            assertEquals(DomainStatus.INVALID, check.status)
            assertEquals("重定向到非允许域名", check.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `invalid host never hits the network`() {
        val check = runBlocking {
            DomainProbe(OkHttpClient()).probe("http://z-lib.gd/path")
        }
        assertEquals(DomainStatus.INVALID, check.status)
    }

    @Test
    fun `probeAll keeps input order`() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setBody("""{"success":1,"domains":["x"]}""")
            }
            start()
        }
        try {
            val probe = probeOn(server, allowed = { true })
            val results = runBlocking { probe.probeAll(listOf("a.test", "b.test"), concurrency = 2) }
            assertEquals(listOf("a.test", "b.test"), results.map { it.host })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `cookie importer json netscape and header`() {
        val json = CookieImporter.parse("""{"remix_userid":"1","remix_userkey":"k"}""")
        val imported = assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(json).value
        assertEquals("1", imported.remixUserId)
        assertEquals("k", imported.remixUserKey)

        val nested = CookieImporter.parse("""{"cookies":{"remix_userid":"2","remix_user_key":"k2"}}""")
        assertEquals("2", assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(nested).value.remixUserId)

        val header = CookieImporter.parse("Cookie: remix_userid=3; remix_userkey=k3; Path=/; Secure")
        val headerCookies = assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(header).value.cookies
        assertEquals("3", headerCookies["remix_userid"])
        assertEquals("k3", headerCookies["remix_userkey"])
        assertNull(headerCookies["Path"])

        val netscape = """
            # Netscape HTTP Cookie File
            .z-lib.gd	TRUE	/	TRUE	0	remix_userid	4
            .z-lib.gd	TRUE	/	TRUE	0	remix_userkey	k4
            .other.test	TRUE	/	TRUE	0	remix_userid	999
        """.trimIndent()
        val importedNetscape = assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(
            CookieImporter.parse(netscape, expectedHost = "z-lib.gd"),
        ).value
        assertEquals("4", importedNetscape.remixUserId)
        assertEquals("k4", importedNetscape.remixUserKey)

        val missing = CookieImporter.parse("""{"remix_userid":"1"}""")
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(missing).error)
    }
}
