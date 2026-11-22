package com.qgming.zlib.core.network

import com.qgming.zlib.core.model.ReleaseApk
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ReleaseApkDownloaderTest {
    private lateinit var server: MockWebServer
    private val directory = Files.createTempDirectory("release-transfer").toFile()
    @BeforeTest fun setup() { server = MockWebServer(); server.start() }
    @AfterTest fun teardown() { server.shutdown(); directory.deleteRecursively() }
    private fun downloader() = ReleaseApkDownloader { url, _ -> url.host == server.hostName && url.port == server.port }
    private fun asset(body: String, digest: String? = null) = ReleaseApk("app.apk", server.url("/app.apk").toString(), body.toByteArray().size.toLong(), digest)

    @Test fun `download verifies digest and reports bytes without credentials`() = runBlocking {
        val body = "APK fixture bytes"
        val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        server.enqueue(MockResponse().setBody(body))
        val target = directory.resolve("download.part")
        var completed = 0L
        downloader().download(asset(body, digest), target) { written, total -> completed = written; assertEquals(body.length.toLong(), total) }
        assertEquals(body, target.readText())
        assertEquals(body.length.toLong(), completed)
        val request = server.takeRequest()
        listOf("Cookie", "Authorization", "remix-userid", "remix-userkey").forEach { assertNull(request.getHeader(it)) }
    }

    @Test fun `truncated oversized or wrong digest files fail`() = runBlocking {
        for ((response, expected) in listOf(
            MockResponse().setBody("short") to asset("longer expected"),
            MockResponse().setBody("too much data") to asset("tiny"),
            MockResponse().setBody("bytes") to asset("bytes", "0".repeat(64)),
        )) {
            server.enqueue(response)
            assertFailsWith<IOException> { downloader().download(expected, directory.resolve("bad.part")) { _, _ -> } }
        }
    }

    @Test fun `redirects must stay within the release download policy`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://example.org/unknown.apk"))
        assertFailsWith<IOException> { downloader().download(asset("bytes"), directory.resolve("redirect.part")) { _, _ -> } }
        assertEquals(1, server.requestCount)
    }

    @Test fun `redirected download succeeds and redirect loops are bounded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/asset")))
        server.enqueue(MockResponse().setBody("bytes"))
        downloader().download(asset("bytes"), directory.resolve("redirected.part")) { _, _ -> }
        repeat(6) { server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/loop"))) }
        assertFailsWith<IOException> { downloader().download(asset("bytes"), directory.resolve("loop.part")) { _, _ -> } }
        assertEquals(8, server.requestCount)
    }

    @Test fun `cancellation waits for writer so no bytes are written after join`() = runBlocking {
        val body = "a".repeat(64 * 1024)
        server.enqueue(MockResponse().setBody(body).throttleBody(1024, 30, TimeUnit.MILLISECONDS))
        val started = CompletableDeferred<Unit>()
        val file = directory.resolve("cancel.part")
        val worker = launch { downloader().download(asset(body), file) { _, _ -> started.complete(Unit) } }
        withTimeout(5000) { started.await() }
        withTimeout(5000) { worker.cancelAndJoin() }
        assertTrue(file.length() < body.length)
        assertTrue(file.delete())
        assertFalse(file.exists())
    }

    @Test fun `production policy only permits HTTPS repository assets and known GitHub CDNs`() {
        assertTrue(allowedReleaseAssetUrl("https://github.com/qgming/zlib-app/releases/download/v1.0.0/app.apk".toHttpUrl(), true))
        assertTrue(allowedReleaseAssetUrl("https://release-assets.githubusercontent.com/files/app.apk?token=fixture".toHttpUrl()))
        for (url in listOf("http://github.com/qgming/zlib-app/releases/download/v1/a.apk", "https://github.com/other/repo/releases/download/v1/a.apk", "https://github.com:444/qgming/zlib-app/releases/download/v1/a.apk", "https://github.com.evil.test/qgming/zlib-app/releases/download/v1/a.apk", "https://example.org/app.apk", "https://user:pass@github.com/qgming/zlib-app/releases/download/v1/a.apk")) {
            assertFalse(allowedReleaseAssetUrl(url.toHttpUrl()), url)
        }
        assertFalse(allowedReleaseAssetUrl("https://release-assets.githubusercontent.com/file.apk".toHttpUrl(), true))
    }
}
