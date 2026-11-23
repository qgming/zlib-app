package com.qgming.zlib.core.data.download

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Exercises real response headers through OkHttp instead of only hand-built strings. */
class DownloadResponseRangeTest {
    private val server = MockWebServer()
    private val client = OkHttpClient()

    @BeforeTest
    fun setUp() {
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a real 206 response validates and completes the resumed segment`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-199/200")
                .setBody("A".repeat(100)),
        )

        val request = Request.Builder()
            .url(server.url("/file"))
            .header("Range", "bytes=100-")
            .build()

        client.newCall(request).execute().use { response ->
            assertEquals(206, response.code)
            val check = DownloadRange.evaluate(
                status = response.code,
                contentRangeHeader = response.header("Content-Range"),
                contentLength = response.body.contentLength(),
                requestedOffset = 100,
            )
            val append = assertIs<DownloadCheck.Append>(check)
            assertEquals(ContentRange(100, 199, 200), append.range)

            val body = response.body.bytes()
            assertEquals(100, body.size)
            val verdict = DownloadRange.completion(append, startOffset = 100, sessionWritten = body.size.toLong())
            assertEquals(CompletionCheck.Complete, verdict)
        }
    }

    @Test
    fun `a valid segment that does not reach total is still incomplete`() {
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 100-199/1000").setBody("A".repeat(100)))
        client.newCall(Request.Builder().url(server.url("/file")).header("Range", "bytes=100-").build()).execute().use { response ->
            val check = assertIs<DownloadCheck.Append>(DownloadRange.evaluate(
                response.code, response.header("Content-Range"), response.body.contentLength(), 100,
            ))
            assertEquals(CompletionCheck.Short(1000, 200), DownloadRange.completion(check, 100, response.body.bytes().size.toLong()))
        }
    }

    @Test
    fun `a short 206 body fails the declared segment length before writing`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-199/1000")
                .setBody("A".repeat(40)),
        )

        val request = Request.Builder()
            .url(server.url("/file"))
            .header("Range", "bytes=100-")
            .build()

        client.newCall(request).execute().use { response ->
            assertIs<DownloadCheck.Invalid>(
                DownloadRange.evaluate(
                    status = response.code,
                    contentRangeHeader = response.header("Content-Range"),
                    contentLength = response.body.contentLength(),
                    requestedOffset = 100,
                ),
            )
        }
    }

    @Test
    fun `a 200 reply to a range request is classified as a restart`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("B".repeat(250)),
        )

        val request = Request.Builder()
            .url(server.url("/file"))
            .header("Range", "bytes=100-")
            .build()

        client.newCall(request).execute().use { response ->
            val check = DownloadRange.evaluate(
                status = response.code,
                contentRangeHeader = response.header("Content-Range"),
                contentLength = response.body.contentLength(),
                requestedOffset = 100,
            )
            assertEquals(DownloadCheck.Restart(250), check)
            response.body.bytes()
        }
    }
}
