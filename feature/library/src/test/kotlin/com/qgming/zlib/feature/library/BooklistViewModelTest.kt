package com.qgming.zlib.feature.library

import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BooklistRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BooklistMembership
import com.qgming.zlib.core.model.CloudBooklist
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

            repeat(4) { server.enqueue(json("""{"success":0}""", 503)) }
            viewModel.refreshBooklists()
            val failure = viewModel.booklists.first { it.error != null }
            assertEquals(LibraryRequest.Refresh, failure.failedRequest)
            assertEquals(original, failure.lists)
            repeat(4) { assertEquals("/papi/booklist/current-user/0", server.take().path) }

            server.enqueue(json("""{"success":1,"list":[]}"""))
            viewModel.retryBooklists()
            assertEquals(emptyList(), viewModel.booklists.first { it.lists?.isEmpty() == true }.lists)
            assertEquals("/papi/booklist/current-user/0", server.take().path)
        }
    }

    @Test
    fun `a transient initial booklist failure retries automatically and keeps the session`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(json("""{"success":0}""", 503).setHeader("Retry-After", "0"))
            server.enqueue(lists())
            viewModel.showBooklists()
            val loaded = viewModel.booklists.first { it.lists != null }
            assertNull(loaded.error)
            repeat(2) {
                val request = server.take()
                assertEquals("/papi/booklist/current-user/0", request.path)
                assertEquals("remix_userid=7; remix_userkey=key-7", request.getHeader("Cookie"))
            }
            assertEquals(2, server.requestCount)
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

            repeat(4) { server.enqueue(json("""{"success":0}""", 503)) }
            viewModel.loadMore()
            val failure = viewModel.books.first { it.error != null }
            repeat(4) { assertEquals("/papi/booklist/42/get-books/3", server.take().path) }
            assertEquals(listOf("123:abcd"), failure.page?.books?.map { it.ref })
            assertEquals(LibraryRequest.Append, failure.failedRequest)
            viewModel.loadMore()
            runCurrent()
            assertEquals(6, server.requestCount)

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

    @Test
    fun `confirmed details write invalidates the hidden list and refreshes open booklist books`() = runTest {
        withBooklistRepository { server, sessions, viewModel, repository ->
            server.enqueue(lists())
            viewModel.showBooklists()
            assertEquals("Reading", viewModel.booklists.first { it.lists != null }.lists?.single()?.title)
            server.take()
            viewModel.hideBooklists()

            server.enqueue(lists())
            server.enqueue(books(1, 2))
            viewModel.openList("42")
            assertEquals("123", viewModel.books.first { it.page != null }.page?.books?.single()?.id)
            server.take()
            server.take()

            server.enqueue(json("""{"success":1}"""))
            server.enqueue(lists())
            server.enqueue(json("""{"success":1,"books":[{"book":{"id":456,"hash":"abcd"}}]}"""))
            val expected = checkNotNull(sessions.current())
            val membership = BooklistMembership(CloudBooklist("42", "Reading", null), false)
            assertEquals(AppResult.Success(Unit), repository.addBook(expected, membership, "456"))
            assertEquals("456", viewModel.books.first { it.page?.books?.singleOrNull()?.id == "456" }.page?.books?.single()?.id)
            assertNull(viewModel.books.value.page?.nextPage)
            assertEquals("/papi/booklist/42/add-book/456", server.take().path)
            assertEquals("/papi/booklist/current-user/0", server.take().path)
            assertEquals("/papi/booklist/42/get-books/1", server.take().path)
            assertNull(viewModel.booklists.value.lists)

            viewModel.closeList("42")
            server.enqueue(lists("57", "New list"))
            viewModel.showBooklists()
            assertEquals("New list", viewModel.booklists.first { it.lists != null }.lists?.single()?.title)
            assertEquals("/papi/booklist/current-user/0", server.take().path)
        }
    }

    @Test
    fun `a loaded empty booklist is shareable without inventing a total`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(lists())
            server.enqueue(json("""{"success":1,"books":[]}"""))
            viewModel.openList("42")
            viewModel.books.first { it.page != null }
            var shared = false
            viewModel.shareList { list, books, partial ->
                shared = true
                assertEquals("42", list.id)
                assertNull(list.bookCount)
                assertTrue(books.isEmpty())
                assertFalse(partial)
            }
            assertTrue(shared)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `share uses six loaded books at most and reports omitted books or another page`() = runTest {
        withBooklists { server, _, viewModel ->
            for ((size, next) in listOf(8 to false, 6 to true, 6 to false)) {
                server.enqueue(lists())
                val rows = (1..size).joinToString(",") { id -> """{"book":{"id":$id,"hash":"abcd","title":"Book $id"}}""" }
                server.enqueue(json("""{"success":1,"books":[$rows],"pagination":{"current":1,"next":${if (next) "2" else "false"}}}"""))
                viewModel.openList("42")
                viewModel.books.first { it.page != null }
                var sharedBooks: List<Book>? = null
                var isPartial: Boolean? = null
                viewModel.shareList { _, books, partial ->
                    sharedBooks = books
                    isPartial = partial
                }
                assertEquals((1..6).map(Int::toString), sharedBooks?.map(Book::id))
                assertEquals(size > 6 || next, isPartial)
                viewModel.closeList("42")
            }
            assertEquals(6, server.requestCount)
        }
    }

    @Test
    fun `sharing is refused while a refresh can replace the loaded snapshot`() = runTest {
        withBooklists { server, _, viewModel ->
            server.enqueue(lists())
            server.enqueue(books(1, 2))
            viewModel.openList("42")
            viewModel.books.first { it.page != null }
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            viewModel.refreshBooks()
            assertEquals(LibraryRequest.Refresh, viewModel.books.value.request)
            viewModel.shareList { _, _, _ -> error("The old snapshot must not be shared during refresh") }
            viewModel.closeList("42")
        }
    }

    @Test
    fun `sharing checks a changed session before its collector clears the old booklist`() = runTest {
        withBooklists { server, sessions, viewModel ->
            server.enqueue(lists())
            server.enqueue(books(1, 2))
            viewModel.openList("42")
            viewModel.books.first { it.page != null }

            sessions.save(session("8"))
            // The session collector is queued on StandardTestDispatcher, so the old state still exists.
            assertEquals("42", viewModel.books.value.booklist?.id)
            assertFalse(viewModel.books.value.sessionChanged)
            viewModel.shareList { _, _, _ -> error("A previous account's snapshot must never be shared") }
            assertTrue(viewModel.books.value.sessionChanged)
            assertNull(viewModel.books.value.booklist)
            assertNull(viewModel.books.value.page)
            runCurrent()
            assertEquals(2, server.requestCount)
        }
    }

    private suspend fun TestScope.withBooklists(block: suspend TestScope.(MockWebServer, SessionManager, BooklistViewModel) -> Unit) =
        withBooklistRepository { server, sessions, viewModel, _ -> block(server, sessions, viewModel) }

    private suspend fun TestScope.withBooklistRepository(
        block: suspend TestScope.(MockWebServer, SessionManager, BooklistViewModel, BooklistRepository) -> Unit,
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
                val sessions = SessionManager(MemoryStore(), dispatchers)
                sessions.save(session("7"))
                val http = OkHttpClient.Builder().addInterceptor { chain ->
                    val request = chain.request()
                    chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath)).build())
                }.build()
                val client = PapiBooklistClient(http, isAllowedHost = { it == "z-lib.gd" })
                val repository = BooklistRepository(client, sessions)
                val viewModel = BooklistViewModel(repository)
                try {
                    block(server, sessions, viewModel, repository)
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
