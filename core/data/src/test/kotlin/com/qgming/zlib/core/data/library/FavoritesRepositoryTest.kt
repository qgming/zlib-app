package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class FavoritesRepositoryTest {
    @Test fun `confirmed membership is reused briefly but refresh and account changes invalidate`() {
        var now = 0L
        fixture({ now }) { server, sessions, repository ->
            server.enqueue(page(1, 1, "7"))
            assertEquals(true, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(true, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(1, server.requestCount)
            now = 30_000_000_001L
            server.enqueue(page(1, 1))
            assertEquals(false, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(2, server.requestCount)
            repository.invalidate(); server.enqueue(page(1, 1))
            assertEquals(false, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            sessions.save(Session("2", "key2", "z-lib.sk")); server.enqueue(page(1, 1, "7"))
            assertEquals(true, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(4, server.requestCount)
        }
    }

    @Test fun `different books reuse fetched pages and a complete index can confirm absence`() = fixture({ 0L }) { server, _, repository ->
        server.enqueue(page(1, 2, "7", "8"))
        server.enqueue(page(2, 2, "11"))
        assertEquals(AppResult.Success(true), repository.isSaved("7"))
        assertEquals(AppResult.Success(true), repository.isSaved("8"))
        assertEquals(1, server.requestCount)
        assertEquals(AppResult.Success(false), repository.isSaved("4"))
        assertEquals(AppResult.Success(true), repository.isSaved("11"))
        assertEquals(2, server.requestCount)
    }

    @Test fun `a per lookup limit leaves membership unknown and retry can continue without repeating pages`() = fixture({ 0L }) { server, _, repository ->
        for (number in 1..9) server.enqueue(page(number, 9, "$number"))
        assertIs<AppResult.Failure>(repository.isSaved("20"))
        assertEquals(8, server.requestCount)
        assertEquals(AppResult.Success(false), repository.isSaved("20"))
        assertEquals(9, server.requestCount)
    }

    private fun fixture(now: () -> Long, block: suspend (MockWebServer, SessionManager, FavoritesRepository) -> Unit) = runBlocking {
        MockWebServer().use { server ->
            val dispatchers = object : AppDispatchers {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Unconfined
            }
            val sessions = SessionManager(object : SessionStore {
                var value = SessionVault()
                override suspend fun readVault() = value
                override suspend fun saveVault(vault: SessionVault) { value = vault }
                override suspend fun clear() { value = SessionVault() }
            }, dispatchers)
            sessions.save(Session("1", "key1", "z-lib.sk"))
            val api = EapiClient(OkHttpClient(), { server.url("/").toString() }, sessions::current,
                dispatchers = dispatchers, baseUrlForHost = { server.url("/").toString() }, maxAttempts = 1,
                isAllowedUrl = { it.host == server.hostName && it.port == server.port })
            block(server, sessions, FavoritesRepository(api, sessions, now))
        }
    }

    private fun page(current: Int, total: Int, vararg ids: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"success":1,"books":[${ids.joinToString(",") { """{"id":$it}""" }}],"pagination":{"current":$current,"total_pages":$total}}""",
    )
}
