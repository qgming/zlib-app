package com.qgming.zlib.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.HttpUrl.Companion.toHttpUrl

class DomainVerificationCookiesTest {
    private val host = "z-library.im"
    private val endpoint = "https://$host/eapi/info/domains".toHttpUrl()

    @Test
    fun `only bounded allowlisted wall cookies and sanitized user agent are accepted`() {
        assertEquals(
            "__diamwall=clearance",
            DomainVerificationCookies.sanitizeCookieHeader("remix_userid=123; __diamwall=clearance; remix_userkey=secret; other=value"),
        )
        assertEquals(
            "cf_clearance=cf-tok; __cf_bm=bm-tok; __ddg1_=ddg-tok",
            DomainVerificationCookies.sanitizeCookieHeader("remix_userid=1; cf_clearance=cf-tok; __cf_bm=bm-tok; __ddg1_=ddg-tok; remix_userkey=secret"),
        )
        assertEquals("__diamwall=clearance", DomainVerificationCookies.sanitizeCookieHeader("__DIAMWALL=clearance"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("remix_userid=123; remix_userkey=secret"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall=first; __diamwall=second"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall="))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall=bad\r\nInjected: value"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall=bad value"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall=bad,value"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall=\"quoted\""))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("__diamwall=${"a".repeat(4097)}"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader("other=${"a".repeat(16384)}; __diamwall=ok"))
        assertNull(DomainVerificationCookies.sanitizeCookieHeader((1..65).joinToString(";") { "other$it=value" } + ";__diamwall=ok"))
        assertEquals("Mozilla/5.0 Chrome/131.0", DomainVerificationCookies.sanitizeUserAgent("  Mozilla/5.0 Chrome/131.0  "))
        assertNull(DomainVerificationCookies.sanitizeUserAgent(""))
        assertNull(DomainVerificationCookies.sanitizeUserAgent("Mozilla/5.0\r\nInjected: 1"))
        assertNull(DomainVerificationCookies.sanitizeUserAgent("a".repeat(513)))
    }

    @Test
    fun `credentials require exact approved HTTPS EAPI or PAPI origin`() {
        val approved = mutableSetOf(host)
        val cookies = DomainVerificationCookies(approved::contains, { 1L })
        val papiEndpoint = "https://$host/papi/booklist/current-user/0".toHttpUrl()
        assertFalse(cookies.putFromWebView("foreign.example", "__diamwall=clearance"))
        assertFalse(cookies.putFromWebView("https://$host/", "__diamwall=clearance"))
        assertTrue(cookies.putFromWebView(host, "__diamwall=clearance; remix_userkey=discard", userAgent = "Mozilla/5.0 WebView"))
        assertEquals("__diamwall=clearance", cookies.cookieHeaderFor(endpoint))
        assertEquals("__diamwall=clearance", cookies.cookieHeaderFor(papiEndpoint))
        assertEquals("Mozilla/5.0 WebView", cookies.userAgentFor(endpoint))
        assertEquals("Mozilla/5.0 WebView", cookies.userAgentFor(papiEndpoint))
        for (url in listOf(
            "http://$host/eapi/info/domains",
            "https://$host:8443/eapi/info/domains",
            "https://user:pass@$host/eapi/info/domains",
            "https://sub.$host/eapi/info/domains",
            "https://$host.evil.example/eapi/info/domains",
            "https://cdn.example/eapi/info/domains",
            "https://$host/cover.jpg",
            "https://$host/eapiary/info/domains",
            "https://$host/papiary/booklist",
        )) {
            assertNull(cookies.cookieHeaderFor(url.toHttpUrl()), url)
            assertNull(cookies.userAgentFor(url.toHttpUrl()), url)
        }
        approved.clear()
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertNull(cookies.userAgentFor(endpoint))
        approved += host
        assertNull(cookies.cookieHeaderFor(endpoint))
    }

    @Test
    fun `changing or unknown network discards clearance even after switching back`() {
        var network: Long? = 1L
        val cookies = DomainVerificationCookies({ it == host }, { network })
        assertTrue(cookies.putFromWebView(host, "__diamwall=clearance"))
        network = 2L
        assertNull(cookies.cookieHeaderFor(endpoint))
        network = 1L
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertTrue(cookies.putFromWebView(host, "__diamwall=new"))
        network = null
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertFalse(cookies.putFromWebView(host, "__diamwall=unknown"))
        network = 1L
        assertNull(cookies.cookieHeaderFor(endpoint))
    }

    @Test
    fun `delayed verification result must match the network that produced it`() {
        var network = 1L
        val cookies = DomainVerificationCookies({ it == host }, { network })
        network = 2L
        assertFalse(cookies.putFromWebView(host, "__diamwall=stale", verifiedNetwork = 1L))
        assertFalse(cookies.putFromWebView(host, "__diamwall=unknown", verifiedNetwork = null))
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertTrue(cookies.putFromWebView(host, "__diamwall=current", verifiedNetwork = 2L))
        assertEquals("__diamwall=current", cookies.cookieHeaderFor(endpoint))
        cookies.clear()
        assertNull(cookies.cookieHeaderFor(endpoint))
    }

    @Test
    fun `clearance expires and does not survive clock rollback or explicit clearing`() {
        var now = 1000L
        val cookies = DomainVerificationCookies({ it == host }, { 1L }, { now }, ttlMillis = 100L)
        assertTrue(cookies.putFromWebView(host, "__diamwall=clearance"))
        now = 1099L
        assertEquals("__diamwall=clearance", cookies.cookieHeaderFor(endpoint))
        now = 1100L
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertTrue(cookies.putFromWebView(host, "__diamwall=renewed"))
        now = 1000L
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertTrue(cookies.putFromWebView(host, "__diamwall=renewed"))
        cookies.clearHost(host)
        assertNull(cookies.cookieHeaderFor(endpoint))
        assertTrue(cookies.putFromWebView(host, "__diamwall=renewed"))
        cookies.clear()
        assertNull(cookies.cookieHeaderFor(endpoint))
    }

    @Test
    fun `validation navigation rejects other origins and unsafe URI schemes`() {
        assertTrue(DomainVerificationNavigation.isAllowed(host, "https://$host/"))
        assertTrue(DomainVerificationNavigation.isAllowed(host, "https://$host:443/challenge?id=123"))
        for (url in listOf(
            "http://$host/",
            "https://$host:8443/",
            "https://user:pass@$host/",
            "https://sub.$host/",
            "https://$host.evil.example/",
            "file:///data/local/tmp/file.html",
            "content://com.qgming.zlib/secret",
            "javascript:alert(1)",
            "intent://$host/",
            "data:text/html,example",
        )) assertFalse(DomainVerificationNavigation.isAllowed(host, url), url)
    }
}
