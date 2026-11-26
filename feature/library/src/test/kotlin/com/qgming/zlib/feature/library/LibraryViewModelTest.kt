package com.qgming.zlib.feature.library

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.library.LibraryRepository
import com.qgming.zlib.core.data.library.FavoritesRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.EapiClient
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @Test
    fun `failed append retries the same page and repeated results stop pagination`() = runTest {
        withLibrary { server, _, _, viewModel ->
            val source = CloudLibrarySource.Saved
            server.enqueue(page("""{"id":1,"hash":"abc"}""", 1, 3))
            viewModel.onSourceShown(source); advanceUntilIdle(); server.nextPath()
            server.enqueue(json("""{"success":0,"error":"unavailable"}""", 503))
            viewModel.loadMore(source); advanceUntilIdle()
            assertEquals("/eapi/user/book/saved?page=2&limit=50", server.nextPath())
            assertEquals(listOf("1"), viewModel.remote.value[source]?.page?.books?.map { it.id })
            assertEquals(LibraryRequest.Append, viewModel.remote.value[source]?.failedRequest)
            server.enqueue(page("""{"id":1,"hash":"abc"}""", 2, 3))
            viewModel.retry(source); advanceUntilIdle()
            assertEquals("/eapi/user/book/saved?page=2&limit=50", server.nextPath())
            assertEquals(false, viewModel.remote.value[source]?.page?.hasNext)
            viewModel.loadMore(source); advanceUntilIdle()
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `saved refresh recovers after failure and pagination retains cloud results`() = runTest {
        withLibrary { server, _, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}""", 1, 2))
            viewModel.onSourceShown(CloudLibrarySource.Saved)
            advanceUntilIdle()
            assertEquals("/eapi/user/book/saved?page=1&limit=50", server.nextPath())

            server.enqueue(json("""{"success":0,"error":"temporarily unavailable"}"""))
            viewModel.refresh(CloudLibrarySource.Saved)
            advanceUntilIdle()
            assertNotNull(viewModel.remote.value[CloudLibrarySource.Saved]?.error)
            assertNull(viewModel.remote.value[CloudLibrarySource.Saved]?.request)

            server.enqueue(page("""{"id":2,"hash":"def"}""", 1, 2))
            viewModel.refresh(CloudLibrarySource.Saved)
            advanceUntilIdle()
            assertEquals(listOf("2"), viewModel.remote.value[CloudLibrarySource.Saved]?.page?.books?.map { it.id })

            server.enqueue(page("""{"id":2,"hash":"def"},{"id":3,"hash":"ghi"}""", 2, 2))
            viewModel.loadMore(CloudLibrarySource.Saved)
            advanceUntilIdle()
            assertEquals(listOf("2", "3"), viewModel.remote.value[CloudLibrarySource.Saved]?.page?.books?.map { it.id })
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `history repairs a missing collection cover using details without overwriting its date`() = runTest {
        withLibrary { server, _, _, viewModel ->
            server.enqueue(page(
                """{"id":1,"hash":"abc","date_download":"2026-11-20","cover":"https://covers.example/collections//abc.jpg"}""",
            ))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            assertEquals("/eapi/user/book/downloaded?page=1&limit=50", server.nextPath())
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single())
            assertEquals("", book.coverUrl)
            assertEquals(1, server.requestCount) // Offscreen cards do not trigger detail requests.

            val cover = "https://covers.example/collections/userbooks/abc.jpg"
            server.enqueue(json("""{"success":1,"book":{"id":1,"hash":"abc","cover":"$cover"}}"""))
            viewModel.loadCover(book)
            viewModel.loadCover(book)
            advanceUntilIdle()
            assertEquals("/eapi/book/1/abc", server.nextPath())
            val repaired = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single())
            assertEquals(cover, repaired.coverUrl)
            assertEquals("2026-11-20", repaired.historyDate)
            assertNull(repaired.localUri)
            assertEquals(2, server.requestCount)

            server.enqueue(page("""{"id":1,"hash":"abc","date_download":"2026-11-20"}"""))
            viewModel.refresh(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            assertEquals(cover, viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single()?.coverUrl)
        }
    }

    @Test
    fun `failed cover lookup keeps the list and is not repeatedly requested`() = runTest {
        withLibrary { server, _, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single())
            server.enqueue(json("""{"success":0,"error":"Book not found"}""", 404))
            viewModel.loadCover(book)
            advanceUntilIdle()
            viewModel.loadCover(book)
            advanceUntilIdle()
            assertEquals(listOf(book), viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books)
            assertNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.error)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `switching accounts discards old lists and cover cache`() = runTest {
        withLibrary { server, sessions, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            viewModel.onSourceShown(CloudLibrarySource.Saved)
            advanceUntilIdle()
            server.nextPath()
            val oldBook = assertNotNull(viewModel.remote.value[CloudLibrarySource.Saved]?.page?.books?.single())
            server.enqueue(json("""{"success":1,"book":{"id":1,"hash":"abc","cover":"https://covers.example/old.jpg"}}"""))
            viewModel.loadCover(oldBook)
            advanceUntilIdle()
            server.nextPath()

            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            sessions.save(session("8"))
            advanceUntilIdle()
            val newBook = assertNotNull(viewModel.remote.value[CloudLibrarySource.Saved]?.page?.books?.single())
            assertEquals("", newBook.coverUrl)
            assertEquals("8", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).getHeader("remix-userid"))

            sessions.clear()
            advanceUntilIdle()
            assertTrue(viewModel.remote.value.values.all { it.page == null })
        }
    }

    @Test
    fun `successful favorite mutation refreshes an already loaded saved list`() = runTest {
        withLibrary { server, _, favorites, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            viewModel.onSourceShown(CloudLibrarySource.Saved)
            advanceUntilIdle()
            server.nextPath()
            server.enqueue(json("""{"success":1}"""))
            server.enqueue(page(""))
            favorites.setSaved("1", false)
            advanceUntilIdle()
            assertEquals("/eapi/user/book/1/unsave", server.nextPath())
            assertEquals("/eapi/user/book/saved?page=1&limit=50", server.nextPath())
            assertEquals(emptyList(), viewModel.remote.value[CloudLibrarySource.Saved]?.page?.books)
            assertNull(viewModel.remote.value[CloudLibrarySource.Saved]?.request)
        }
    }

    @Test
    fun `history deletion requires confirmation sends once and reloads pagination from page one`() = runTest {
        withLibrary { server, _, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"},{"id":2,"hash":"def"}""", 1, 3))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            server.nextPath()
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.first())
            viewModel.confirmHistoryDeletion()
            advanceUntilIdle()
            assertEquals(1, server.requestCount)
            viewModel.requestHistoryDeletion(book)
            assertTrue(viewModel.historyAction.value.confirming)
            server.enqueue(json("""{"success":1}"""))
            server.enqueue(page("""{"id":2,"hash":"def"},{"id":3,"hash":"ghi"}""", 1, 2))
            viewModel.confirmHistoryDeletion()
            viewModel.confirmHistoryDeletion()
            viewModel.loadMore(CloudLibrarySource.DownloadHistory)
            assertTrue(viewModel.historyAction.value.busy)
            assertEquals(listOf("1", "2"), viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.map { it.id })
            advanceUntilIdle()
            assertEquals("/eapi/user/book/1/delete", server.nextPath())
            assertEquals("/eapi/user/book/downloaded?page=1&limit=50", server.nextPath())
            assertEquals(listOf("2", "3"), viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.map { it.id })
            assertEquals(1, viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.page)
            assertNull(viewModel.historyAction.value.book)
            assertEquals("已删除云端下载记录", withTimeoutOrNull(100) { viewModel.events.first() })
            assertNull(withTimeoutOrNull(100) { viewModel.events.first() })
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `refused history deletion retains the record and user can cancel or retry`() = runTest {
        withLibrary { server, _, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            server.nextPath()
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single())
            viewModel.requestHistoryDeletion(book)
            server.enqueue(json("""{"success":0,"error":"Cannot delete"}""", 400))
            viewModel.confirmHistoryDeletion()
            advanceUntilIdle()
            server.nextPath()
            assertEquals(listOf(book), viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books)
            assertNotNull(viewModel.historyAction.value.error)
            assertEquals(false, viewModel.historyAction.value.busy)
            assertNull(withTimeoutOrNull(100) { viewModel.events.first() })
            assertEquals(2, server.requestCount)
            viewModel.dismissHistoryActions()
            assertNull(viewModel.historyAction.value.book)
        }
    }

    @Test
    fun `a failed refresh after deletion cannot append using stale page offsets`() = runTest {
        withLibrary { server, _, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"},{"id":2,"hash":"def"}""", 1, 3))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            server.nextPath()
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.first())
            viewModel.requestHistoryDeletion(book)
            server.enqueue(json("""{"success":1}"""))
            server.enqueue(json("""{"success":0,"error":"Cannot refresh"}"""))
            viewModel.confirmHistoryDeletion()
            advanceUntilIdle()
            assertEquals("/eapi/user/book/1/delete", server.nextPath())
            assertEquals("/eapi/user/book/downloaded?page=1&limit=50", server.nextPath())
            assertEquals(listOf("2"), viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.map { it.id })
            assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.error)

            server.enqueue(page("""{"id":2,"hash":"def"},{"id":3,"hash":"ghi"}""", 1, 2))
            viewModel.loadMore(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            assertEquals("/eapi/user/book/downloaded?page=1&limit=50", server.nextPath())
            assertEquals(listOf("2", "3"), viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.map { it.id })
        }
    }

    @Test
    fun `logout before confirming a history deletion drops the old account action`() = runTest {
        withLibrary { server, sessions, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            server.nextPath()
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single())
            viewModel.requestHistoryDeletion(book)
            sessions.clear()
            viewModel.confirmHistoryDeletion()
            advanceUntilIdle()
            assertNull(viewModel.historyAction.value.book)
            assertTrue(viewModel.remote.value.values.all { it.page == null })
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `logout clears pending success feedback and changes the toast session key`() = runTest {
        withLibrary { server, sessions, _, viewModel ->
            server.enqueue(page("""{"id":1,"hash":"abc"}"""))
            viewModel.onSourceShown(CloudLibrarySource.DownloadHistory)
            advanceUntilIdle()
            val book = assertNotNull(viewModel.remote.value[CloudLibrarySource.DownloadHistory]?.page?.books?.single())
            val previousGeneration = viewModel.sessionGeneration.value
            viewModel.requestHistoryDeletion(book)
            server.enqueue(json("""{"success":1}"""))
            server.enqueue(page(""))
            viewModel.confirmHistoryDeletion()
            advanceUntilIdle()

            sessions.clear()
            advanceUntilIdle()
            assertEquals(previousGeneration + 1, viewModel.sessionGeneration.value)
            assertNull(withTimeoutOrNull(100) { viewModel.events.first() })
            assertNull(viewModel.historyAction.value.book)
            assertTrue(viewModel.remote.value.values.all { it.page == null })
            assertEquals(3, server.requestCount)
        }
    }

    private suspend fun TestScope.withLibrary(
        block: suspend TestScope.(MockWebServer, SessionManager, FavoritesRepository, LibraryViewModel) -> Unit,
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
                sessions.save(session("7"))
                val api = EapiClient(
                    client = OkHttpClient(),
                    baseUrlProvider = { server.url("/").toString() },
                    baseUrlForHost = { server.url("/").toString() },
                    sessionProvider = sessions::current,
                    dispatchers = dispatchers,
                    maxAttempts = 1,
                    isAllowedUrl = { it.host == server.hostName && it.port == server.port },
                )
                val favorites = FavoritesRepository(api, sessions)
                block(server, sessions, favorites, LibraryViewModel(LibraryRepository(api), sessions, favorites))
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun session(id: String) = Session(id, "key-$id", "z-lib.sk")

    private class MemorySessionStore : SessionStore {
        private var vault = com.qgming.zlib.core.data.session.SessionVault()
        override suspend fun readVault() = vault
        override suspend fun saveVault(vault: com.qgming.zlib.core.data.session.SessionVault) { this.vault = vault }
        override suspend fun clear() { vault = com.qgming.zlib.core.data.session.SessionVault() }
    }

    private fun MockWebServer.nextPath(): String? = assertNotNull(takeRequest(2, TimeUnit.SECONDS)).path

    private fun page(books: String, current: Int = 1, total: Int = 1): MockResponse = json(
        """{"success":1,"books":[$books],"pagination":{"current":$current,"total_pages":$total}}""",
    )

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
