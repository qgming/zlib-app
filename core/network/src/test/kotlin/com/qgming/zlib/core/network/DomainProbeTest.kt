package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DomainProbeTest {

    private fun probeOn(
        server: MockWebServer,
        allowed: (String) -> Boolean = { it == "probe.test" },
        trusted: (String) -> Boolean = allowed,
        followForeignRedirects: Boolean = true,
        verificationCookieProvider: (HttpUrl) -> String? = { null },
        verificationUserAgentProvider: (HttpUrl) -> String? = { null },
    ): DomainProbe {
        val base = server.url("/").toString().trimEnd('/')
        return DomainProbe(
            client = OkHttpClient.Builder().build(),
            isAllowedHost = allowed,
            isTrustedHost = trusted,
            baseUrlFor = { base },
            requireHttps = false,
            timeoutMillis = 5_000,
            followForeignRedirects = followForeignRedirects,
            verificationCookieProvider = verificationCookieProvider,
            verificationUserAgentProvider = verificationUserAgentProvider,
        )
    }

    private fun runProbe(
        server: MockWebServer,
        allowed: (String) -> Boolean = { it == "probe.test" },
        trusted: (String) -> Boolean = allowed,
        followForeignRedirects: Boolean = true,
    ) = runBlocking { probeOn(server, allowed, trusted, followForeignRedirects).probe("probe.test") }

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
            assertTrue((check.latencyMs ?: 0L) >= 1L)
            assertEquals("/eapi/info/domains", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `catalog hosts accept valid empty domain arrays without granting unknown hosts the same trust`() {
        val server = MockWebServer().apply {
            repeat(2) { enqueue(MockResponse().setBody("""{"success":1,"domains":[]}""")) }
            start()
        }
        try {
            assertEquals(DomainStatus.HEALTHY, runProbe(server).status)
            assertEquals(DomainStatus.INVALID, runProbe(server, trusted = { false }).status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `manual challenge cookie is anonymous and never restored after a foreign redirect`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/"))
            enqueue(MockResponse().setBody("""{"success":1,"domains":["z-lib.gd"]}"""))
            start()
        }
        try {
            var cookieRequests = 0
            val probe = probeOn(server, verificationCookieProvider = {
                cookieRequests++
                "remix_userid=foreign; __diamwall=challenge-token; preferences=unused"
            })
            assertEquals(DomainStatus.HEALTHY, runBlocking { probe.probe("probe.test") }.status)
            val initial = server.takeRequest()
            assertEquals("__diamwall=challenge-token", initial.getHeader("Cookie"))
            assertNull(initial.getHeader(EapiHeaders.REMIX_USER_ID))
            assertNull(initial.getHeader(EapiHeaders.REMIX_USER_KEY))
            val redirected = server.takeRequest()
            assertNull(redirected.getHeader("Cookie"))
            assertNull(redirected.getHeader(EapiHeaders.REMIX_USER_ID))
            assertNull(redirected.getHeader(EapiHeaders.REMIX_USER_KEY))
            assertEquals(1, cookieRequests)
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
    fun `diamwall self redirect is classified diamwall`() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setResponseCode(307)
                    .setHeader("Server", "DiamWall")
                    .setHeader("Location", request.requestUrl.toString())
                    .addHeader("Set-Cookie", "__diamwall=0x1; Path=/; Secure")
            }
            start()
        }
        try {
            val check = runProbe(server, allowed = { true })
            assertEquals(DomainStatus.DIAMWALL, check.status)
            assertEquals("DiamWall 人机验证", check.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `self redirect is a redirect loop`() {
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(302).setHeader("Location", request.requestUrl.toString())
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
    fun `redirect outside the allowlist is invalid when discovery is off`() {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://evil.test/"))
            start()
        }
        try {
            val check = runProbe(server, allowed = { it == "probe.test" }, followForeignRedirects = false)
            assertEquals(DomainStatus.INVALID, check.status)
            assertEquals("重定向到非允许域名", check.detail)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `verified foreign redirect target is reported on the redirector`() {
        val server = MockWebServer().apply {
            dispatcher = scriptedDispatcher(
                MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/welcome"),
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    """{"success":1,"domains":[{"domain":"z-library.sk"},{"domain":"1lib.sk"}]}""",
                ),
            )
            start()
        }
        try {
            val check = runProbe(server)
            assertEquals(DomainStatus.HEALTHY, check.status)
            assertEquals("probe.test", check.host)
            assertEquals("other.test", check.redirectTarget)
            assertEquals("other.test", check.finalHost)
            assertTrue(check.detail.contains("other.test"))
            assertEquals("/eapi/info/domains", server.takeRequest().path)
            assertEquals("/eapi/info/domains", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `foreign redirect target without a known domain family is rejected`() {
        val server = MockWebServer().apply {
            dispatcher = scriptedDispatcher(
                MockResponse().setResponseCode(302).setHeader("Location", "https://phish.test/"),
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"success":1,"domains":[{"domain":"phish.test"}]}"""),
            )
            start()
        }
        try {
            val check = runProbe(server)
            assertEquals(DomainStatus.INVALID, check.status)
            assertNull(check.redirectTarget)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `foreign redirect chain is followed to the final verified host`() {
        val server = MockWebServer().apply {
            dispatcher = scriptedDispatcher(
                MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/"),
                MockResponse().setResponseCode(301).setHeader("Location", "https://third.test/"),
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"success":1,"domains":[{"domain":"z-lib.gd"}]}"""),
            )
            start()
        }
        try {
            val check = runProbe(server)
            assertEquals(DomainStatus.HEALTHY, check.status)
            assertEquals("third.test", check.redirectTarget)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `foreign redirect target with html challenge is challenged`() {
        val server = MockWebServer().apply {
            dispatcher = scriptedDispatcher(
                MockResponse().setResponseCode(302).setHeader("Location", "https://other.test/"),
                MockResponse().setResponseCode(403).setBody("<html><title>Just a moment...</title></html>"),
            )
            start()
        }
        try {
            val check = runProbe(server)
            assertEquals(DomainStatus.CHALLENGED, check.status)
            assertNull(check.redirectTarget)
        } finally {
            server.shutdown()
        }
    }

    private fun scriptedDispatcher(vararg responses: MockResponse): Dispatcher =
        object : Dispatcher() {
            private var index = 0

            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = responses[minOf(index, responses.lastIndex)]
                index++
                return response
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
    fun `a host that is only a probe candidate must prove a known domain family`() {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"success":1,"domains":[{"domain":"discovered.test"}]}"""),
            )
            start()
        }
        try {
            val check = runProbe(server, allowed = { true }, trusted = { false })
            assertEquals(DomainStatus.INVALID, check.status)
            assertEquals("未通过域名家族校验", check.detail)
        } finally {
            server.shutdown()
        }
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
    fun `probeFirstUsable returns immediately on first healthy host without waiting for slow hosts`() {
        val slowGate = java.util.concurrent.CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    if (path.startsWith("/slow.test/")) {
                        slowGate.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    if (path.startsWith("/bad.test/")) {
                        return MockResponse().setResponseCode(503).setBody("down")
                    }
                    return MockResponse().setBody("""{"success":1,"domains":["z-library.sk"]}""")
                }
            }
            start()
        }
        try {
            val probe = DomainProbe(
                OkHttpClient(),
                isAllowedHost = { true },
                isTrustedHost = { true },
                baseUrlFor = { host -> server.url("/$host").toString() },
                requireHttps = false,
            )
            val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
            val winner = runBlocking {
                probe.probeFirstUsable(
                    hosts = listOf("bad.test", "slow.test", "fast.test"),
                    concurrency = 3,
                    onEach = { seen.add(it.host) },
                )
            }
            assertEquals("fast.test", winner?.host)
            assertEquals(DomainStatus.HEALTHY, winner?.status)
            assertTrue("fast.test" in seen)
        } finally {
            slowGate.countDown()
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

    @Test
    fun `cookie importer reads HttpOnly rows and retains only one required pair`() {
        val netscape = listOf(
            "#HttpOnly_.z-lib.gd\tTRUE\t/\tTRUE\t0\tremix_userid\t7",
            "#HttpOnly_.z-lib.gd\tTRUE\t/\tTRUE\t0\tremix_user_key\tunit-key",
            ".z-lib.gd\tTRUE\t/\tTRUE\t0\tpreferences\tunused",
        ).joinToString("\n")
        val imported = assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(
            CookieImporter.parse(netscape, expectedHost = "zh.z-lib.gd"),
        ).value
        assertEquals(mapOf("remix_userid" to "7", "remix_userkey" to "unit-key"), imported.cookies)
    }

    @Test
    fun `cookie importer honors domain scope in browser exports`() {
        val exported = """{"cookies":[
            {"name":"remix_userid","value":"8","domain":".z-lib.gd"},
            {"name":"remix_userkey","value":"unit-key","domain":".z-lib.gd"},
            {"name":"remix_userid","value":"12","domain":"other.test"},
            {"name":"preferences","value":"unused","domain":".z-lib.gd"}
        ]}"""
        val imported = assertIs<AppResult.Success<CookieImporter.ImportedCookies>>(
            CookieImporter.parse(exported, expectedHost = "zh.z-lib.gd"),
        ).value
        assertEquals(mapOf("remix_userid" to "8", "remix_userkey" to "unit-key"), imported.cookies)
        assertIs<AppResult.Failure>(CookieImporter.parse(exported, expectedHost = "other.test"))
        assertIs<AppResult.Failure>(CookieImporter.parse(exported, expectedHost = "zlib.bz"))
    }
}
