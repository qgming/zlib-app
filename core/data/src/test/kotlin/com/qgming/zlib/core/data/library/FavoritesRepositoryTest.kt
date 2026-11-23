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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRepositoryTest {
    @Test fun `confirmed membership is reused briefly but refresh and account changes invalidate`() = runTest {
        MockWebServer().use { server ->
            val dispatcher = StandardTestDispatcher(testScheduler)
            val dispatchers = object : AppDispatchers {
                override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
            }
            val sessions = SessionManager(object : SessionStore {
                var value = SessionVault()
                override suspend fun readVault() = value
                override suspend fun saveVault(vault: SessionVault) { value = vault }
                override suspend fun clear() { value = SessionVault() }
            }, dispatchers)
            sessions.save(Session("1", "key1", "z-lib.sk"))
            val api = EapiClient(OkHttpClient(), { server.url("/").toString() }, sessions::current,
                dispatchers = dispatchers, baseUrlForHost = { server.url("/").toString() }, maxAttempts = 1)
            var now = 0L
            val repository = FavoritesRepository(api, sessions) { now }
            server.enqueue(page(true))
            assertEquals(true, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(true, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(1, server.requestCount)
            now = 30_000_000_001L
            server.enqueue(page(false))
            assertEquals(false, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(2, server.requestCount)
            repository.invalidate(); server.enqueue(page(false))
            assertEquals(false, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            sessions.save(Session("2", "key2", "z-lib.sk")); server.enqueue(page(true))
            assertEquals(true, assertIs<AppResult.Success<Boolean>>(repository.isSaved("7")).value)
            assertEquals(4, server.requestCount)
        }
    }

    private fun page(saved: Boolean) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"success":1,"books":[${if (saved) "{\"id\":7}" else ""}],"pagination":{"current":1,"total_pages":1}}""",
    )
}
