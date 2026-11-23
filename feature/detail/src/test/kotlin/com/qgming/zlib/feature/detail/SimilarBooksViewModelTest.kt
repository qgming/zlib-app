package com.qgming.zlib.feature.detail

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SimilarBooksViewModelTest {
    @Test fun `finite similar list loads once retries independently and clears on logout`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val dispatchers = object : AppDispatchers {
                    override val main = dispatcher; override val io = dispatcher; override val default = dispatcher
                }
                val sessions = SessionManager(object : SessionStore {
                    var value = SessionVault()
                    override suspend fun readVault() = value
                    override suspend fun saveVault(vault: SessionVault) { value = vault }
                    override suspend fun clear() { value = SessionVault() }
                }, dispatchers)
                sessions.save(Session("1", "key", "z-lib.sk"))
                val api = EapiClient(OkHttpClient(), { server.url("/").toString() }, sessions::current,
                    dispatchers = dispatchers, baseUrlForHost = { server.url("/").toString() }, maxAttempts = 1)
                val viewModel = SimilarBooksViewModel(BookRepository(api, sessions), sessions)
                server.enqueue(MockResponse().setBody("""{"success":0,"error":"Unavailable"}"""))
                viewModel.load("7", "abc"); viewModel.load("7", "abc"); advanceUntilIdle()
                assertNotNull(viewModel.state.value.error)
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setBody("""{"success":1,"books":[{"id":8,"hash":"def"}]}"""))
                viewModel.retry(); advanceUntilIdle()
                assertEquals(listOf("8:def"), viewModel.state.value.books.map { it.ref })
                assertNull(viewModel.state.value.error)
                viewModel.load("7", "abc"); advanceUntilIdle()
                assertEquals(2, server.requestCount)
                sessions.clear(); advanceUntilIdle()
                assertEquals(emptyList(), viewModel.state.value.books)
                assertEquals(false, viewModel.state.value.loading)
                assertEquals(2, server.requestCount)
            }
        } finally { Dispatchers.resetMain() }
    }
}
