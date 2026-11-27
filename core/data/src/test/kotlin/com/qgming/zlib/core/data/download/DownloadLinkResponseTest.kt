package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer

class DownloadLinkResponseTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
    private val link = "https://cdn.example/book.epub?token=example"
    private val envelope = """{"success":1,"file":{"downloadLink":"$link","allowDownload":true}}"""

    @AfterTest
    fun tearDown() {
        server.shutdown()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test
    fun `JSON links are parsed with incorrect or missing content types`() {
        for (contentType in listOf("text/html; charset=UTF-8", "application/octet-stream", "application/json", null)) {
            enqueue(envelope, contentType)
            response().use {
                val result = assertIs<AppResult.Success<HttpUrl>>(DownloadLinkParser.fromResponse(it))
                assertEquals(link, result.value.toString())
                assertEquals(envelope, it.body.string())
            }
        }
    }

    @Test
    fun `JSON sniffing accepts whitespace and UTF8 BOM`() {
        enqueue("\uFEFF \r\n\t$envelope", "text/html")
        response().use {
            assertEquals(link, assertIs<AppResult.Success<HttpUrl>>(DownloadLinkParser.fromResponse(it)).value.toString())
        }
    }

    @Test
    fun `mislabeled quota and login envelopes retain their meaning`() {
        for ((message, expected) in listOf("Daily limit reached" to "quota", "Please login" to "session")) {
            enqueue("""{"success":0,"error":"$message"}""", "text/html")
            response().use {
                val error = assertIs<AppResult.Failure>(DownloadLinkParser.fromResponse(it)).error
                if (expected == "quota") assertIs<AppError.Quota>(error) else assertEquals(AppError.SessionExpired, error)
            }
        }
    }

    @Test
    fun `an HTTP error cannot supply a usable link`() {
        enqueue(envelope, "text/html", 503)
        response().use {
            val error = assertIs<AppResult.Failure>(DownloadLinkParser.fromResponse(it)).error
            assertEquals(503, assertIs<AppError.Server>(error).code)
        }
    }

    @Test
    fun `JSON with a denied missing or unsafe link fails before file transfer`() {
        val invalid = listOf(
            """{"success":1,"file":{"downloadLink":"$link","allowDownload":false}}""",
            """{"success":1,"file":null}""",
            """{"success":1,"file":"http://cdn.example/book.epub"}""",
            """{"success":1,"file":"https://user:password@cdn.example/book.epub"}""",
            """{"success":1,"file":"https://cdn.example:8443/book.epub"}""",
        )
        for (body in invalid) {
            enqueue(body, "text/html")
            response().use {
                assertIs<AppError.Parse>(assertIs<AppResult.Failure>(DownloadLinkParser.fromResponse(it)).error)
            }
        }
    }

    @Test
    fun `relative JSON links resolve against the EAPI origin`() {
        enqueue("""{"success":1,"file":"/file/book.epub?token=example"}""", null)
        response().use { received ->
            received.newBuilder().request(Request.Builder().url("https://z-lib.gl/eapi/book/1/hash/file").build()).build().use {
                assertEquals(
                    "https://z-lib.gl/file/book.epub?token=example".toHttpUrl(),
                    assertIs<AppResult.Success<HttpUrl>>(DownloadLinkParser.fromResponse(it)).value,
                )
            }
        }
    }

    @Test
    fun `binary files and real HTML are not interpreted as JSON and their bytes are untouched`() {
        val bodies = listOf(
            "PK\u0003\u0004".toByteArray() + ByteArray(100_000) { it.toByte() },
            "%PDF-1.7\nbook".toByteArray(),
            "<html><body>Verification required</body></html>".toByteArray(),
        )
        for (body in bodies) {
            server.enqueue(MockResponse().setBody(Buffer().write(body)))
            response().use {
                assertNull(DownloadLinkParser.fromResponse(it))
                assertContentEquals(body, it.body.bytes())
            }
        }
    }

    @Test
    fun `range responses starting with JSON bytes remain file segments`() {
        enqueue(envelope, "application/json", 206)
        response().use {
            assertNull(DownloadLinkParser.fromResponse(it))
            assertEquals(envelope, it.body.string())
        }
    }

    @Test
    fun `RTF and text starting with a brace remain direct book responses`() {
        val bodies = listOf(
            "{\\rtf1\\ansi book}",
            "{\\rtf1\\ansi ${"book ".repeat(15_000)}}",
            "{A plain text book}",
            "{invalid JSON}",
            """{"chapter":"A book about JSON"}""",
        )
        for (body in bodies) {
            enqueue(body, "application/octet-stream")
            response().use {
                assertNull(DownloadLinkParser.fromResponse(it))
                assertEquals(body, it.body.string())
            }
        }
    }

    @Test
    fun `oversized JSON is rejected without consuming the response`() {
        val body = """{"success":1,"file":"$link","padding":"${"x".repeat(64 * 1024)}"}"""
        enqueue(body, "text/html")
        response().use {
            assertIs<AppError.Parse>(assertIs<AppResult.Failure>(DownloadLinkParser.fromResponse(it)).error)
            assertEquals(body, it.body.string())
        }
    }

    private fun enqueue(body: String, contentType: String?, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body).apply {
            if (contentType != null) setHeader("Content-Type", contentType)
        })
    }

    private fun response() = client.newCall(Request.Builder().url(server.url("/eapi/book/1/hash/file")).build()).execute()
}
