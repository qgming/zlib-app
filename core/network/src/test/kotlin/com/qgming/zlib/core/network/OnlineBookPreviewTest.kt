package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.OnlineBookPreview
import com.qgming.zlib.core.model.Session
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class OnlineBookPreviewTest {

    @Test
    fun `each mirror resolves its own signed reader link byte for byte`() {
        listOf("z-lib.gd", "zlib.bz").forEach { host ->
            withServer(listOf(host)) { server, eapi ->
                val signed =
                    "https://reader.$host/read/123/abcd12?token=Zm9v%2Fbar%3D&expires=1764000000&sign=a%2Bb%2Fc%3D"
                server.enqueue(json(detailBody(readerUrl = signed)))

                val preview = assertNotNull(resolved(eapi, host))

                assertEquals(signed, preview.readerUrl)
                assertEquals("https://$host/book/123/abcd12", preview.publicBookUrl)

                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("GET", request.method)
                assertEquals("/eapi/book/123/abcd12", request.path)
                assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
                assertEquals("remix_userid=7; remix_userkey=unit-test-key", request.getHeader("Cookie"))
                assertEquals("7", request.getHeader(EapiHeaders.REMIX_USER_ID))
                assertEquals("unit-test-key", request.getHeader(EapiHeaders.REMIX_USER_KEY))
            }
        }
    }

    @Test
    fun `the supplied session selects the domain even when another mirror is the provider`() {
        withServer(listOf("z-lib.gd", "zlib.bz")) { server, eapi ->
            val first = "https://reader.z-lib.gd/read/123/abcd12?expires=1764000000&sign=first%2Ftoken"
            val second = "https://reader.zlib.bz/read/123/abcd12?expires=1764003600&sign=second%2Btoken"
            server.enqueue(json(detailBody(readerUrl = first)))
            server.enqueue(json(detailBody(readerUrl = second)))

            assertEquals(first, assertNotNull(resolved(eapi, "z-lib.gd")).readerUrl)
            assertEquals(second, assertNotNull(resolved(eapi, "zlib.bz")).readerUrl)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `repeated resolution always asks the network and returns the refreshed signature`() {
        withServer(listOf("z-lib.gd", "z-lib.gd")) { server, eapi ->
            val first = "https://reader.z-lib.gd/read/123/abcd12?expires=1764000000&sign=first%2Ftoken"
            val second = "https://reader.z-lib.gd/read/123/abcd12?expires=1764003600&sign=second%2Btoken"
            server.enqueue(json(detailBody(readerUrl = first)))
            server.enqueue(json(detailBody(readerUrl = second)))

            assertEquals(first, assertNotNull(resolved(eapi, "z-lib.gd")).readerUrl)
            assertEquals(second, assertNotNull(resolved(eapi, "z-lib.gd")).readerUrl)

            repeat(2) {
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("/eapi/book/123/abcd12", request.path)
                assertEquals("no-cache, no-store", request.getHeader("Cache-Control"))
            }
        }
    }

    @Test
    fun `the response host does not restrict the signed reader link`() {
        withServer(listOf("z-lib.gd"), responseHost = "z-library.ec") { server, eapi ->
            server.enqueue(json(detailBody()))

            val preview = assertNotNull(resolved(eapi, "z-lib.gd"))
            assertEquals(
                "https://reader.z-lib.gd/read/123/abcd12?expires=1764000000&sign=default%2Fsign",
                preview.readerUrl,
            )
            assertEquals("https://z-library.ec/book/123/abcd12", preview.publicBookUrl)
            assertEquals("/eapi/book/123/abcd12", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
        }
    }

    @Test
    fun `mismatched reference missing availability and invalid reader links yield no link`() {
        val bodies = listOf(
            detailBody(id = "124"),
            detailBody(hash = "ffff99"),
            detailBody(available = "false"),
            detailBody(available = "0"),
            detailBody(readerUrl = "http://reader.z-lib.gd/read/123/abcd12?sign=x"),
            detailBody(readerUrl = "https://reader.z-lib.gd/read/123/abcd12?remix_userkey=leak"),
            detailBody(readerUrl = "https://z-lib.gd@reader.z-lib.gd/read/123/abcd12"),
            detailBody(readerUrl = "https://z-lib.gd:8443/read/123/abcd12"),
            detailBody(readerUrl = null),
        )
        withServer(List(bodies.size) { "z-lib.gd" }) { server, eapi ->
            bodies.forEach { body ->
                server.enqueue(json(body))
                assertNull(resolved(eapi, "z-lib.gd"))
            }
            assertEquals(bodies.size, server.requestCount)
        }
    }

    private fun withServer(
        expectedHosts: List<String>,
        responseHost: String? = null,
        block: suspend (MockWebServer, EapiClient) -> Unit,
    ) = runBlocking {
        MockWebServer().use { server ->
            val requestedHosts = Collections.synchronizedList(mutableListOf<String>())
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val original = chain.request()
                assertTrue(original.url.isHttps)
                requestedHosts.add(original.url.host)
                val path = original.url.encodedPath + (original.url.encodedQuery?.let { "?$it" } ?: "")
                val response = chain.proceed(original.newBuilder().url(server.url(path)).build())
                val restored = responseHost?.let { host ->
                    original.newBuilder().url(original.url.newBuilder().host(host).build()).build()
                } ?: original
                response.newBuilder().request(restored).build()
            }.build()
            val eapi = EapiClient(
                client = http,
                baseUrlProvider = { "https://z-library.qa" },
                sessionProvider = { null },
                sleeper = {},
                maxAttempts = 1,
            )
            block(server, eapi)
            assertEquals(expectedHosts, requestedHosts)
        }
    }

    private suspend fun resolved(eapi: EapiClient, domain: String): OnlineBookPreview? =
        assertIs<AppResult.Success<OnlineBookPreview?>>(
            eapi.resolveOnlinePreview("123", "abcd12", session(domain)),
        ).value

    private fun session(domain: String) = Session(
        remixUserId = "7",
        remixUserKey = "unit-test-key",
        domain = domain,
        cookies = mapOf("remix_userid" to "7", "remix_userkey" to "unit-test-key"),
    )

    private fun detailBody(
        id: String = "123",
        hash: String = "abcd12",
        readerUrl: String? =
            "https://reader.z-lib.gd/read/123/abcd12?expires=1764000000&sign=default%2Fsign",
        available: String = "true",
        href: String = "/book/123/abcd12",
    ): String {
        val fields = mutableListOf(
            """"id":"$id"""",
            """"hash":"$hash"""",
            """"title":"Synthetic preview"""",
            """"href":"$href"""",
            """"readOnlineAvailable":$available""",
        )
        if (readerUrl != null) fields += """"readOnlineUrl":"$readerUrl""""
        return """{"success":1,"book":{${fields.joinToString(",")}}}"""
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)
}
