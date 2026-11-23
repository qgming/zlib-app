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
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class GitHubReleaseClientTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setup() { server = MockWebServer(); server.start() }
    @AfterTest fun teardown() { server.shutdown() }
    private fun client() = GitHubReleaseClient(server.url("/repos/qgming/zlib-app/releases/latest"))
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
        for (code in listOf(403, 429, 503)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(code, assertIs<AppError.Server>(assertIs<AppResult.Failure>(api.latestRelease()).error).code)
        }
        assertEquals(3, server.requestCount)
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
