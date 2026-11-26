package com.qgming.zlib.feature.search

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiClient
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

@OptIn(ExperimentalCoroutinesApi::class)
class PopularViewModelTest {
    @Test
    fun `sources have independent results and failures and retry only the requested source`() = runTest {
        withHome {
            failures += ReadingSource.Recommended
            val viewModel = viewModel()
            advanceUntilIdle()

            val popular = assertNotNull(viewModel.state.value[ReadingSource.Popular])
            assertEquals(listOf("1:abc"), popular.books.map { it.ref })
            assertFalse(popular.loading)
            assertNull(popular.error)
            val recommended = assertNotNull(viewModel.state.value[ReadingSource.Recommended])
            assertNotNull(recommended.error)
            assertTrue(recommended.books.isEmpty())
            assertFalse(recommended.loading)
            assertEquals(1, count(ReadingSource.Popular))

            failures.clear()
            viewModel.refresh(ReadingSource.Recommended)
            advanceUntilIdle()
            assertEquals(listOf("2:abc"), viewModel.state.value[ReadingSource.Recommended]?.books?.map { it.ref })
            assertNull(viewModel.state.value[ReadingSource.Recommended]?.error)
            assertEquals(popular, viewModel.state.value[ReadingSource.Popular])
            assertEquals(1, count(ReadingSource.Popular))
            assertEquals(2, count(ReadingSource.Recommended))
        }
    }

    @Test
    fun `refresh retains visible books on failure and merges duplicate refresh taps`() = runTest {
        withHome {
            val viewModel = viewModel()
            advanceUntilIdle()
            val recommended = viewModel.state.value[ReadingSource.Recommended]
            failures += ReadingSource.Popular

            viewModel.refresh(ReadingSource.Popular)
            viewModel.refresh(ReadingSource.Popular)
            assertTrue(viewModel.state.value[ReadingSource.Popular]?.loading == true)
            assertEquals(listOf("1"), viewModel.state.value[ReadingSource.Popular]?.books?.map { it.id })
            advanceUntilIdle()

            assertEquals(listOf("1"), viewModel.state.value[ReadingSource.Popular]?.books?.map { it.id })
            assertNotNull(viewModel.state.value[ReadingSource.Popular]?.error)
            assertFalse(viewModel.state.value[ReadingSource.Popular]?.loading == true)
            assertEquals(recommended, viewModel.state.value[ReadingSource.Recommended])
            assertEquals(2, count(ReadingSource.Popular))
            assertEquals(1, count(ReadingSource.Recommended))
        }
    }

    @Test
    fun `empty recommendations finish loading and can be refreshed independently`() = runTest {
        withHome {
            empty += ReadingSource.Recommended
            val viewModel = viewModel()
            advanceUntilIdle()
            val feed = assertNotNull(viewModel.state.value[ReadingSource.Recommended])
            assertTrue(feed.books.isEmpty())
            assertFalse(feed.loading)
            assertNull(feed.error)

            empty.clear()
            viewModel.refresh(ReadingSource.Recommended)
            advanceUntilIdle()
            assertEquals(listOf("2"), viewModel.state.value[ReadingSource.Recommended]?.books?.map { it.id })
            assertEquals(1, count(ReadingSource.Popular))
        }
    }

    @Test
    fun `domain change reloads failed feeds and preserves successfully loaded content`() = runTest {
        withHome {
            failures += ReadingSource.Recommended
            val viewModel = viewModel()
            advanceUntilIdle()
            val popular = viewModel.state.value[ReadingSource.Popular]

            failures.clear()
            sessions.updateDomain(assertNotNull(sessions.current()), "z101.by")
            advanceUntilIdle()
            assertEquals(popular, viewModel.state.value[ReadingSource.Popular])
            assertEquals(listOf("2"), viewModel.state.value[ReadingSource.Recommended]?.books?.map { it.id })
            assertNull(viewModel.state.value[ReadingSource.Recommended]?.error)
            assertEquals(1, count(ReadingSource.Popular))
            assertEquals(2, count(ReadingSource.Recommended))
        }
    }

    @Test
    fun `account change replaces both feeds and logout clears all old results`() = runTest {
        withHome {
            val viewModel = viewModel()
            advanceUntilIdle()

            sessions.add(Session("8", "new-key", "z-lib.by"))
            advanceUntilIdle()
            assertEquals(listOf("3"), viewModel.state.value[ReadingSource.Popular]?.books?.map { it.id })
            assertEquals(listOf("4"), viewModel.state.value[ReadingSource.Recommended]?.books?.map { it.id })
            assertEquals(2, count(ReadingSource.Popular))
            assertEquals(2, count(ReadingSource.Recommended))

            sessions.clear()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.isEmpty())
            assertEquals(4, server.requestCount)
        }
    }

    private suspend fun TestScope.withHome(block: suspend Fixture.() -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val dispatchers = object : AppDispatchers {
                override val main = dispatcher
                override val io = dispatcher
                override val default = dispatcher
            }
            val fixture = Fixture(dispatchers)
            fixture.server.use {
                fixture.sessions.add(Session("7", "key", "z-lib.by"))
                fixture.block()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private class Fixture(dispatchers: AppDispatchers) {
        val failures = ConcurrentHashMap.newKeySet<ReadingSource>()
        val empty = ConcurrentHashMap.newKeySet<ReadingSource>()
        private val counts = ConcurrentHashMap<ReadingSource, Int>()
        val sessions = SessionManager(object : SessionStore {
            private var vault = SessionVault()
            override suspend fun readVault() = vault
            override suspend fun saveVault(vault: SessionVault) { this.vault = vault }
            override suspend fun clear() { vault = SessionVault() }
        }, dispatchers)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val source = when (request.path) {
                        "/eapi/book/most-popular" -> ReadingSource.Popular
                        "/eapi/user/book/recommended" -> ReadingSource.Recommended
                        else -> return MockResponse().setResponseCode(404)
                    }
                    counts.merge(source, 1, Int::plus)
                    if (source in failures) return json("""{"success":0,"error":"Unavailable"}""", 503)
                    val id = (if (request.getHeader("remix-userid") == "8") 3 else 1) + source.ordinal
                    val book = """{"id":$id,"hash":"abc","title":"Book $id"}"""
                    return json("""{"success":1,"books":[${if (source in empty) "" else "$book,$book"}]}""")
                }
            }
            start()
        }
        private val api = EapiClient(
            client = OkHttpClient(),
            baseUrlProvider = { server.url("/").toString() },
            baseUrlForHost = { server.url("/").toString() },
            sessionProvider = sessions::current,
            dispatchers = dispatchers,
            maxAttempts = 1,
            isAllowedUrl = { it.host == server.hostName && it.port == server.port },
        )

        fun viewModel() = PopularViewModel(BookRepository(api, sessions))
        fun count(source: ReadingSource) = counts[source] ?: 0

        private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
            .setHeader("Content-Type", "application/json").setBody(body)
    }
}
