package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class SearchFilterOptionsTest {
    @Test fun `dynamic options preserve service codes and never send credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"success":1,"languages":{"japanese":"日语","chinese":"中文","old english":"古英语"}}"""))
            server.enqueue(json("""{"success":1,"extensions":["epub","TXT","cbz"]}"""))
            val client = EapiClient(OkHttpClient(), { server.url("/").toString() }, { Session("7", "secret", "z-lib.sk") }, maxAttempts = 1)
            val result = assertIs<AppResult.Success<com.qgming.zlib.core.model.SearchFilterOptions>>(client.searchFilterOptions()).value
            assertEquals(listOf("japanese", "chinese", "old english"), result.languages.map { it.code })
            assertEquals(listOf("epub", "txt", "cbz"), result.extensions)
            repeat(2) {
                val request = server.takeRequest()
                assertNull(request.getHeader("Cookie"))
                assertNull(request.getHeader("remix-userid"))
                assertNull(request.getHeader("remix-userkey"))
            }
        }
    }

    @Test fun `malformed and empty candidate lists fail rather than replace fallback`() = runBlocking {
        MockWebServer().use { server ->
            val client = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null }, maxAttempts = 1)
            for (body in listOf("""{"success":1,"languages":[]}""", """{"success":1,"languages":{}}""")) {
                server.enqueue(json(body)); server.enqueue(json("""{"success":1,"extensions":["epub"]}"""))
                assertIs<AppResult.Failure>(client.searchFilterOptions())
            }
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
