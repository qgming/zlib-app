package com.qgming.zlib.feature.detail

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.library.FavoritesRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class BookFavoriteViewModelTest {
    @Test
    fun `cloud favorite toggles only after success and prevents duplicate taps`() = runTest {
        withFavorite { server, _, favorites, viewModel ->
            server.enqueue(savedPage(true))
            viewModel.load("7")
            advanceUntilIdle()
            assertEquals(true, viewModel.state.value.saved)
            server.nextPath()

            server.enqueue(json("""{"success":1}"""))
            viewModel.toggle()
            viewModel.toggle()
            assertEquals(true, viewModel.state.value.saved)
            assertTrue(viewModel.state.value.busy)
            advanceUntilIdle()
            assertEquals("/eapi/user/book/7/unsave", server.nextPath())
            assertEquals(false, viewModel.state.value.saved)
            assertEquals(1L, favorites.changes.value)
            assertEquals(2, server.requestCount)

            server.enqueue(json("""{"success":0,"error":"Cannot save"}"""))
            viewModel.toggle()
            advanceUntilIdle()
            assertEquals("/eapi/user/book/7/save", server.nextPath())
            assertEquals(false, viewModel.state.value.saved)
            assertFalse(viewModel.state.value.busy)
            assertNotNull(viewModel.state.value.error)
            assertEquals(1L, favorites.changes.value)

            server.enqueue(json("""{"success":1}"""))
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(true, viewModel.state.value.saved)
            assertNull(viewModel.state.value.error)
            assertEquals(2L, favorites.changes.value)
        }
    }

    @Test
    fun `unknown membership cannot mutate cloud favorites and can be retried`() = runTest {
        withFavorite { server, _, _, viewModel ->
            server.enqueue(json("""{"success":0,"error":"Cannot read saved"}"""))
            viewModel.load("7")
            advanceUntilIdle()
            assertNull(viewModel.state.value.saved)
            viewModel.toggle()
            assertEquals(1, server.requestCount)

            server.enqueue(savedPage(false))
            viewModel.retry()
            advanceUntilIdle()
            assertEquals(false, viewModel.state.value.saved)
            assertNull(viewModel.state.value.error)
        }
    }

    @Test
    fun `new session reloads membership and logout clears the favorite`() = runTest {
        withFavorite { server, sessions, _, viewModel ->
            server.enqueue(savedPage(true))
            viewModel.load("7")
            advanceUntilIdle()
            assertEquals(true, viewModel.state.value.saved)
            server.nextPath()

            server.enqueue(savedPage(false))
            sessions.save(Session("8", "key-8", "z-lib.sk"))
            advanceUntilIdle()
            assertEquals(false, viewModel.state.value.saved)
            assertEquals("8", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("remix-userid"))

            sessions.clear()
            advanceUntilIdle()
            assertNull(viewModel.state.value.saved)
            assertNotNull(viewModel.state.value.error)
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun TestScope.withFavorite(
        block: suspend TestScope.(MockWebServer, SessionManager, FavoritesRepository, BookFavoriteViewModel) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val dispatchers = object : AppDispatchers {
                    override val io = dispatcher
                    override val default = dispatcher
                    override val main = dispatcher
                }
                val sessions = SessionManager(MemorySessionStore(), dispatchers)
                sessions.save(Session("1", "key-1", "z-lib.sk"))
                val api = EapiClient(
                    client = OkHttpClient(),
                    baseUrlProvider = { server.url("/").toString() },
                    baseUrlForHost = { server.url("/").toString() },
                    sessionProvider = sessions::current,
                    dispatchers = dispatchers,
                    maxAttempts = 1,
                )
                val favorites = FavoritesRepository(api, sessions)
                block(server, sessions, favorites, BookFavoriteViewModel(favorites, sessions))
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class MemorySessionStore : SessionStore {
        private var vault = com.qgming.zlib.core.data.session.SessionVault()
        override suspend fun readVault() = vault
        override suspend fun saveVault(vault: com.qgming.zlib.core.data.session.SessionVault) { this.vault = vault }
        override suspend fun clear() { vault = com.qgming.zlib.core.data.session.SessionVault() }
    }

    private fun MockWebServer.nextPath() = assertNotNull(takeRequest(2, TimeUnit.SECONDS)).path

    private fun savedPage(saved: Boolean) = json(
        """{"success":1,"books":[${if (saved) "{\"id\":7}" else ""}],"pagination":{"current":1,"total_pages":1}}""",
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
