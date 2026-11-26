package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.apkFor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class GitHubReleaseClientTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setup() { server = MockWebServer(); server.start() }
    @AfterTest fun teardown() { server.shutdown() }
    private fun client(network: () -> Long? = { null }, now: () -> Long = System::currentTimeMillis) =
        GitHubReleaseClient(server.url("/repos/qgming/zlib-app/releases/latest"), now, network)
    private val release = """{"tag_name":"v0.2.0","body":"A new release","draft":false,"prerelease":false,"html_url":"https://example.org/untrusted"}"""

    @Test
    fun `anonymous JSON request reads stable release and fixes destination to project`() = runBlocking {
        server.enqueue(MockResponse().setBody(release))
        val result = assertIs<AppResult.Success<AppRelease?>>(client().latestRelease()).value!!
        assertEquals("v0.2.0", result.tag)
        assertEquals("A new release", result.notes)
        assertEquals("https://github.com/qgming/zlib-app/releases/tag/v0.2.0", result.pageUrl)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/repos/qgming/zlib-app/releases/latest", request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        listOf("Cookie", "Authorization", "remix-userid", "remix-userkey").forEach { assertNull(request.getHeader(it), it) }
    }

    @Test
    fun `missing stable release is distinct from a failed check`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(assertIs<AppResult.Success<AppRelease?>>(client().latestRelease()).value)
    }

    @Test
    fun `rate limit and server errors are never reported as current and never retried`() = runBlocking {
        val api = client()
        for (code in listOf(403, 503, 429)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(code, assertIs<AppError.Server>(assertIs<AppResult.Failure>(api.latestRelease()).error).code)
        }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `rate limit on latest gates history and honors the later server deadline`() = runBlocking {
        var now = 1_790_000_000_000L
        val deadline = now + 75_000
        val api = client { now }
        server.enqueue(MockResponse().setResponseCode(403)
            .addHeader("X-RateLimit-Remaining", "0")
            .addHeader("X-RateLimit-Reset", (deadline / 1000).toString())
            .addHeader("Retry-After", "30"))
        val error = assertIs<AppResult.Failure>(api.latestRelease())
        assertEquals(deadline, assertIs<AppError.Server>(error.error).retryAtEpochMillis)
        now = deadline - 1
        assertEquals(error, api.releaseHistory())
        assertEquals(1, server.requestCount)
        now = deadline
        server.enqueue(MockResponse().setBody("[$release]"))
        assertIs<AppResult.Success<List<AppRelease>>>(api.releaseHistory())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `new default network can check again without clearing the previous network limit`() = runBlocking {
        var now = 1_790_000_000_000L
        var network: Long? = 1L
        val api = client({ network }) { now }
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "60"))
        val limited = assertIs<AppResult.Failure>(api.latestRelease())
        assertEquals(limited, api.releaseHistory())
        network = null
        assertEquals(limited, api.latestRelease())
        assertEquals(1, server.requestCount)

        network = 2L
        server.enqueue(MockResponse().setBody("[$release]"))
        assertIs<AppResult.Success<List<AppRelease>>>(api.releaseHistory())
        assertEquals(0, server.takeRequest().sequenceNumber)
        assertEquals(0, server.takeRequest().sequenceNumber)

        network = 1L
        assertEquals(limited, api.latestRelease())
        assertEquals(2, server.requestCount)
        now += 60_000
        server.enqueue(MockResponse().setBody(release))
        assertIs<AppResult.Success<AppRelease?>>(api.latestRelease())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `limit on an unknown network is honored when a default network becomes known`() = runBlocking {
        var network: Long? = null
        val api = client({ network }) { 1_790_000_000_000L }
        server.enqueue(MockResponse().setResponseCode(429))
        val limited = assertIs<AppResult.Failure>(api.latestRelease())
        network = 2L
        assertEquals(limited, api.releaseHistory())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `unattributable limit during network change gates all networks`() = runBlocking {
        var network = 1L
        var samples = 0
        val api = client({ if (++samples == 1) 1L else network }) { 1_790_000_000_000L }
        network = 2L
        server.enqueue(MockResponse().setResponseCode(429))
        val limited = assertIs<AppResult.Failure>(api.latestRelease())
        assertEquals(limited, api.releaseHistory())
        network = 3L
        assertEquals(limited, api.latestRelease())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `successful response exhausting the shared quota does not invalidate its result`() = runBlocking {
        val now = 1_790_000_000_000L
        val api = client { now }
        server.enqueue(MockResponse().setBody(release)
            .addHeader("X-RateLimit-Remaining", "0")
            .addHeader("X-RateLimit-Reset", (now / 1000 + 20).toString()))
        assertNotNull(assertIs<AppResult.Success<AppRelease?>>(api.latestRelease()).value)
        assertEquals(429, assertIs<AppError.Server>(assertIs<AppResult.Failure>(api.releaseHistory()).error).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ordinary forbidden response does not enable rate limiting but secondary limit does`() = runBlocking {
        val now = 1_790_000_000_000L
        val api = client { now }
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
        assertNull(assertIs<AppError.Server>(assertIs<AppResult.Failure>(api.latestRelease()).error).retryAtEpochMillis)
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"You have exceeded a secondary rate limit."}"""))
        val error = assertIs<AppResult.Failure>(api.latestRelease())
        assertEquals(now + 60_000L, assertIs<AppError.Server>(error.error).retryAtEpochMillis)
        assertEquals(error, api.latestRelease())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a large success response and an invalid history do not replace valid metadata`() = runBlocking {
        val api = client()
        server.enqueue(MockResponse().setBody(" ".repeat(2 * 1024 * 1024 + 1)))
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(api.latestRelease()).error)
        server.enqueue(MockResponse().setBody("[false]"))
        assertIs<AppError.Parse>(assertIs<AppResult.Failure>(api.releaseHistory()).error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `redirect is refused instead of leaving GitHub request scope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", server.url("/unexpected")))
        server.enqueue(MockResponse().setBody(release))
        assertEquals(302, assertIs<AppError.Server>(assertIs<AppResult.Failure>(client().latestRelease()).error).code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `drafts and prereleases are not offered as stable releases`() = runBlocking {
        val api = client()
        for (body in listOf(release.replace("\"draft\":false", "\"draft\":true"), release.replace("\"prerelease\":false", "\"prerelease\":true"))) {
            server.enqueue(MockResponse().setBody(body))
            assertNull(assertIs<AppResult.Success<AppRelease?>>(api.latestRelease()).value)
        }
    }

    @Test
    fun `malformed and incomplete responses stay actionable errors`() = runBlocking {
        val api = client()
        for (body in listOf("<html>blocked</html>", "{}", release.replace("v0.2.0", ""))) {
            server.enqueue(MockResponse().setBody(body))
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(api.latestRelease()).error)
        }
    }

    @Test fun `history reads real release text and filters drafts`() = runBlocking {
        server.enqueue(MockResponse().setBody("[$release,${release.replace("\"draft\":false", "\"draft\":true")} ]"))
        val result = assertIs<AppResult.Success<List<AppRelease>>>(client().releaseHistory()).value
        assertEquals(listOf("A new release"), result.map { it.notes })
        assertEquals("/repos/qgming/zlib-app/releases?per_page=20", server.takeRequest().path)
    }

    @Test fun `APK assets require valid uploaded size and construct trusted repository URLs`() = runBlocking {
        val assets = """, "assets":[{"name":"zhishi.apk","size":12345,"state":"uploaded","digest":"sha256:${"a".repeat(64)}","browser_download_url":"https://evil.test/a.apk"},{"name":"source.zip","size":100,"state":"uploaded"},{"name":"empty.apk","size":0,"state":"uploaded"}]"""
        server.enqueue(MockResponse().setBody(release.dropLast(1) + assets + "}"))
        val apk = assertIs<AppResult.Success<AppRelease?>>(client().latestRelease()).value!!.apks.single()
        assertEquals(12345, apk.size)
        assertEquals("a".repeat(64), apk.sha256)
        assertEquals("https://github.com/qgming/zlib-app/releases/download/v0.2.0/zhishi.apk", apk.url)
    }

    @Test fun `universal-only release with checksum and notes is selectable by existing arm64 updater`() = runBlocking {
        val body = """{"tag_name":"v1.3.0","body":"Release notes","draft":false,"prerelease":false,"assets":[
            {"name":"zhishi-1.3.0-universal.apk","size":5000000,"state":"uploaded","digest":"sha256:${"b".repeat(64)}"},
            {"name":"SHA256SUMS.txt","size":100,"state":"uploaded"},
            {"name":"v1.3.0.txt","size":200,"state":"uploaded"}
        ]}"""
        server.enqueue(MockResponse().setBody(body))
        val result = assertIs<AppResult.Success<AppRelease?>>(client().latestRelease()).value!!
        val apk = result.apkFor(listOf("arm64-v8a", "armeabi-v7a"))!!
        assertEquals(1, result.apks.size)
        assertEquals("zhishi-1.3.0-universal.apk", apk.name)
        assertEquals("b".repeat(64), apk.sha256)
        assertEquals("https://github.com/qgming/zlib-app/releases/download/v1.3.0/zhishi-1.3.0-universal.apk", apk.url)
    }
}
