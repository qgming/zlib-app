package com.qgming.zlib.feature.library

import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.data.library.BooklistRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.PapiBooklistClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

@OptIn(ExperimentalCoroutinesApi::class)
class BooklistViewModelTest {
    @Test
    fun `booklists load only when shown and refresh failure retains the account list`() = runTest {
        withBooklists { server, _, viewModel ->
            runCurrent()
            assertEquals(0, server.requestCount)
            server.enqueue(lists())
            viewModel.showBooklists()
            val original = viewModel.booklists.first { it.lists != null }.lists
            assertEquals("Reading", original?.single()?.title)
            assertEquals("/papi/booklist/current-user/0", server.take().path)

            server.enqueue(json("""{"success":0}""", 503))
            viewModel.refreshBooklists()
            val failure = viewModel.booklists.first { it.error != null }
            assertEquals(LibraryRequest.Refresh, failure.failedRequest)
            assertEquals(original, failure.lists)
            assertEquals("/papi/booklist/current-user/0", server.take().path)

            server.enqueue(json("""{"success":1,"list":[]}"""))
            viewModel.retryBooklists()
            assertEquals(emptyList(), viewModel.booklists.first { it.lists?.isEmpty() == true }.lists)
            assertEquals("/papi/booklist/current-user/0", server.take().path)
        }
    }

    @Test
    fun `leaving booklists cancels the initial request and reopening starts a new one`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            viewModel.showBooklists()
            runCurrent()
            assertEquals("/papi/booklist/current-user/0", server.take().path)
            viewModel.hideBooklists()
            runCurrent()
            assertEquals(BooklistsUiState(), viewModel.booklists.value)

            server.enqueue(lists("99", "New list"))
            viewModel.showBooklists()
            assertEquals("99", viewModel.booklists.first { it.lists != null }.lists?.single()?.id)
            assertEquals("/papi/booklist/current-user/0", server.take().path)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `append retries the exact server page and repeated results stop pagination`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(lists())
            server.enqueue(books(1, 3))
            viewModel.openList("42")
            viewModel.books.first { it.page != null }
            assertEquals("/papi/booklist/current-user/0", server.take().path)
            assertEquals("/papi/booklist/42/get-books/1", server.take().path)

            server.enqueue(json("""{"success":0}""", 503))
            viewModel.loadMore()
            val failure = viewModel.books.first { it.error != null }
            assertEquals("/papi/booklist/42/get-books/3", server.take().path)
            assertEquals(listOf("123:abcd"), failure.page?.books?.map { it.ref })
            assertEquals(LibraryRequest.Append, failure.failedRequest)
            viewModel.loadMore()
            runCurrent()
            assertEquals(3, server.requestCount)

            server.enqueue(books(3, 4))
            viewModel.retryBooks()
            val end = viewModel.books.first { it.request == null && it.error == null }
            assertEquals("/papi/booklist/42/get-books/3", server.take().path)
            assertEquals(1, end.page?.books?.size)
            assertNull(end.page?.nextPage)
        }
    }

    @Test
    fun `changing accounts clears old lists and detail and rejects a stale selection`() = runTest {
        withBooklists { server, sessions, viewModel ->
            server.enqueue(lists())
            viewModel.showBooklists()
            val mine = viewModel.booklists
            mine.first { it.lists != null }
            assertEquals("remix_userid=7; remix_userkey=key-7", server.take().getHeader("Cookie"))
            var selection: String? = null
            viewModel.selectList("42") { selection = it }
            assertEquals("42", selection)

            server.enqueue(lists())
            server.enqueue(books(1, 2))
            viewModel.openList("42")
            viewModel.books.first { it.page != null }
            server.take()
            server.take()
            assertTrue(viewModel.canOpenBook("123:abcd"))

            server.enqueue(lists("99", "Current account"))
            sessions.save(session("8"))
            val newLists = mine.first { it.lists?.singleOrNull()?.id == "99" }
            assertEquals("Current account", newLists.lists?.single()?.title)
            assertEquals("remix_userid=8; remix_userkey=key-8", server.take().getHeader("Cookie"))
            assertTrue(viewModel.books.value.sessionChanged)
            assertNull(viewModel.books.value.booklist)
            assertNull(viewModel.books.value.page)
            assertFalse(viewModel.canOpenBook("123:abcd"))
            viewModel.selectList("42") { error("A previous account's booklist must not open") }
            viewModel.loadMore()
            runCurrent()
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `dereferencing a stale booklist id only checks ownership and never loads its books`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(lists("99"))
            viewModel.openList("42")
            assertIs<AppError.Unsupported>(viewModel.books.first { it.error != null }.error)
            assertEquals("/papi/booklist/current-user/0", server.take().path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `PAPI login refusal does not invalidate the current EAPI session`() = runTest {
        withBooklists { server, sessions, viewModel ->
            server.enqueue(json("""{"success":0,"error":"Please login"}""", 401))
            viewModel.showBooklists()
            assertIs<AppError.Unsupported>(viewModel.booklists.first { it.error != null }.error)
            assertEquals(session("7"), sessions.current())
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `changing the domain cancels pending detail and does not replay it`() = runTest {
        withBooklists { server, sessions, viewModel ->
            server.enqueue(lists())
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            viewModel.openList("42")
            runCurrent()
            assertEquals("/papi/booklist/current-user/0", server.take().path)
            viewModel.books.first { it.booklist != null }
            assertEquals("/papi/booklist/42/get-books/1", server.take().path)

            sessions.save(session("7").withDomain("zlib.bz"))
            val changed = viewModel.books.first { it.sessionChanged }
            assertNull(changed.page)
            assertNull(changed.booklist)
            assertFalse(viewModel.canOpenBook("123:abcd"))
            viewModel.loadMore()
            runCurrent()
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `cover previews read only visible nonempty lists and keep at most four covers`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(json("""{"success":1,"list":[{"id":42,"books_count":7},{"id":43,"books_count":1},{"id":44,"books_count":0}]}"""))
            viewModel.showBooklists()
            viewModel.booklists.first { it.lists?.size == 3 }
            server.take()

            val books = (1..7).joinToString(",") { id ->
                """{"book":{"id":$id,"hash":"abcd","cover":"https://images.example.test/$id.jpg"}}"""
            }
            server.enqueue(json("""{"success":1,"books":[$books],"pagination":{"next":2}}"""))
            viewModel.showPreviews(setOf("42", "44"))
            val loaded = viewModel.booklists.first { it.lists?.first()?.covers?.size == 4 }.lists.orEmpty()
            assertEquals(listOf("1", "2", "3", "4"), loaded.first().covers.map { it.bookId })
            assertTrue(loaded.drop(1).all { it.covers.isEmpty() })
            val request = server.take()
            assertEquals("/papi/booklist/42/get-books/1", request.path)
            assertEquals("remix_userid=7; remix_userkey=key-7", request.getHeader("Cookie"))
            viewModel.showPreviews(setOf("42"))
            runCurrent()
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun TestScope.withBooklists(block: suspend TestScope.(MockWebServer, SessionManager, BooklistViewModel) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val dispatchers = object : AppDispatchers {
                    override val io = dispatcher
                    override val default = dispatcher
                    override val main = dispatcher
                }
                val sessions = SessionManager(MemoryStore(), dispatchers)
                sessions.save(session("7"))
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }.build()
                val client = PapiBooklistClient(http) { it == "z-lib.gd" }
                val viewModel = BooklistViewModel(BooklistRepository(client, sessions))
                try {
                    block(server, sessions, viewModel)
                } finally {
                    viewModel.viewModelScope.cancel()
                    runCurrent()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun MockWebServer.take(): RecordedRequest =
        takeRequest(2, TimeUnit.SECONDS) ?: error("no recorded request, count=$requestCount")

    private fun lists(id: String = "42", title: String = "Reading") = json("""{"success":1,"list":[{"id":"$id","title":"$title"}]}""")

    private fun books(page: Int, next: Int) = json(
        """{"success":1,"books":[{"book":{"id":123,"hash":"abcd"}}],"pagination":{"current":$page,"next":$next}}""",
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)

    private fun session(id: String) = Session(id, "key-$id", "z-lib.gd")

    private class MemoryStore : SessionStore {
        private var vault = SessionVault()
        override suspend fun readVault() = vault
        override suspend fun saveVault(vault: SessionVault) { this.vault = vault }
        override suspend fun clear() { vault = SessionVault() }
    }
}
