package com.qgming.zlib.feature.detail

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.download.ExistingDownload
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.model.Book
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
class DetailViewModelTest {
    @Test
    fun `exact existing file prompts without enqueue and redownload requires explicit choice`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val dispatchers = object : AppDispatchers {
                    override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
                }
                val sessions = SessionManager(MemorySessionStore(), dispatchers)
                sessions.save(Session("7", "key", "z-lib.sk"))
                val api = EapiClient(OkHttpClient(), { server.url("/").toString() }, sessions::current,
                    baseUrlForHost = { server.url("/").toString() }, dispatchers = dispatchers, maxAttempts = 1,
                    isAllowedUrl = { it.host == server.hostName && it.port == server.port })
                val queued = mutableListOf<Book>()
                val forced = mutableListOf<Book>()
                val checked = mutableListOf<String>()
                val viewModel = DetailViewModel(BookRepository(api), sessions,
                    enqueue = { queued.add(it); AppResult.Success(Unit) },
                    findExisting = { checked.add(it.ref); ExistingDownload(it, "content://test/tree/books/document/file") },
                    forceEnqueue = { forced.add(it); AppResult.Success(Unit) },
                )
                loadDetail(server, viewModel)
                server.enqueue(json("""{"success":1,"books":[{"id":8,"hash":"pdf","extension":"pdf"}]}"""))
                viewModel.showFormats(); advanceUntilIdle()
                viewModel.selectFormat("8:pdf"); advanceUntilIdle()
                assertEquals(listOf("8:pdf"), checked)
                assertEquals("8:pdf", viewModel.state.value.existingSelection?.ref)
                assertTrue(queued.isEmpty()); assertTrue(forced.isEmpty())
                viewModel.redownload(); viewModel.redownload(); advanceUntilIdle()
                assertEquals(listOf("8:pdf"), forced.map { it.ref })
                assertTrue(queued.isEmpty())
                assertNull(viewModel.state.value.existing)
                assertEquals(2, server.requestCount)
                sessions.clear(); advanceUntilIdle()
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `opening formats never enqueues and selecting a file uses its own reference and size once`() = runTest {
        withDetail { server, _, queued, viewModel ->
            loadDetail(server, viewModel)
            server.enqueue(json("""{"success":1,"books":[
                {"id":8,"hash":"pdfhash","extension":"pdf","filesize":4096},
                {"id":9,"hash":"pdfother","extension":"pdf","filesize":8192},
                {"id":7,"hash":"abc","extension":"epub","filesize":2048}]}"""))
            viewModel.showFormats()
            viewModel.showFormats()
            advanceUntilIdle()
            assertEquals("/eapi/book/7/abc/formats", server.nextPath())
            assertEquals(listOf("7:abc", "8:pdfhash", "9:pdfother"), viewModel.state.value.formats?.options?.map { it.ref })
            assertTrue(queued.isEmpty())
            assertEquals(2, server.requestCount)

            viewModel.selectFormat("not-an-option")
            assertTrue(queued.isEmpty())
            viewModel.selectFormat("8:pdfhash")
            viewModel.selectFormat("8:pdfhash")
            assertTrue(viewModel.state.value.enqueuing)
            assertNull(viewModel.state.value.formats)
            advanceUntilIdle()
            val selected = queued.single()
            assertEquals("8:pdfhash", selected.ref)
            assertEquals("pdf", selected.extension)
            assertEquals(4096L, selected.sizeBytes)
            assertEquals("Test title", selected.title)
            assertEquals("Author", selected.authors)
            assertNull(selected.localUri)
            assertEquals("7:abc", viewModel.state.value.book?.ref)
            assertFalse(viewModel.state.value.enqueuing)
            assertEquals(2, server.requestCount) // No file endpoint is called while choosing.
        }
    }

    @Test
    fun `empty and failed format responses keep the current file but do not auto download`() = runTest {
        withDetail { server, _, queued, viewModel ->
            loadDetail(server, viewModel)
            server.enqueue(json("""{"success":1,"books":[]}"""))
            viewModel.showFormats()
            advanceUntilIdle()
            assertEquals(listOf("7:abc"), viewModel.state.value.formats?.options?.map { it.ref })
            assertNull(viewModel.state.value.formats?.error)
            assertTrue(queued.isEmpty())
            viewModel.dismissFormats()
            assertNull(viewModel.state.value.formats)
            viewModel.selectFormat("7:abc")
            assertTrue(queued.isEmpty())

            server.enqueue(json("""{"success":0,"error":"Cannot read formats"}"""))
            viewModel.showFormats()
            advanceUntilIdle()
            assertNotNull(viewModel.state.value.formats?.error)
            assertEquals(listOf("7:abc"), viewModel.state.value.formats?.options?.map { it.ref })
            assertTrue(queued.isEmpty())

            server.enqueue(json("""{"success":1,"books":[{"id":8,"hash":"pdf","extension":"pdf"}]}"""))
            viewModel.showFormats()
            advanceUntilIdle()
            assertNull(viewModel.state.value.formats?.error)
            assertEquals(listOf("7:abc", "8:pdf"), viewModel.state.value.formats?.options?.map { it.ref })
        }
    }

    @Test
    fun `reopening formats fetches again and keeps other extensions beyond the detail file`() = runTest {
        withDetail { server, _, queued, viewModel ->
            loadDetail(server, viewModel)
            server.enqueue(json("""{"success":1,"books":[]}"""))
            viewModel.showFormats()
            advanceUntilIdle()
            assertEquals("/eapi/book/7/abc/formats", server.nextPath())
            assertEquals(listOf("epub"), viewModel.state.value.formats?.options?.map { it.extension })
            viewModel.dismissFormats()

            server.enqueue(json("""{"success":1,"books":[
                {"id":8,"hash":"pdfhash","extension":"pdf","filesize":4096},
                {"id":9,"hash":"mobihash","extension":"mobi","filesize":8192}]}"""))
            viewModel.showFormats()
            advanceUntilIdle()
            assertEquals("/eapi/book/7/abc/formats", server.nextPath())
            assertEquals(listOf("epub", "pdf", "mobi"), viewModel.state.value.formats?.options?.map { it.extension })
            assertEquals(listOf("7:abc", "8:pdfhash", "9:mobihash"), viewModel.state.value.formats?.options?.map { it.ref })
            assertEquals("epub", viewModel.state.value.book?.extension)
            assertTrue(queued.isEmpty())
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `closing the sheet or changing account before a request runs drops its selection`() = runTest {
        withDetail { server, sessions, queued, viewModel ->
            loadDetail(server, viewModel)
            viewModel.showFormats()
            viewModel.dismissFormats()
            advanceUntilIdle()
            assertEquals(1, server.requestCount)
            assertNull(viewModel.state.value.formats)

            server.enqueue(json("""{"success":1,"books":[{"id":8,"hash":"pdf","extension":"pdf"}]}"""))
            viewModel.showFormats()
            advanceUntilIdle()
            sessions.clear()
            // The click can arrive before the session collector; it must still reject the old menu.
            viewModel.selectFormat("8:pdf")
            advanceUntilIdle()
            assertNull(viewModel.state.value.book)
            assertNull(viewModel.state.value.formats)
            assertTrue(queued.isEmpty())
            assertEquals(2, server.requestCount)
        }
    }

    private fun TestScope.loadDetail(server: MockWebServer, viewModel: DetailViewModel) {
        server.enqueue(json("""{"success":1,"book":{"id":7,"hash":"abc","title":"Test title","author":"Author","extension":"epub","filesize":2048}}"""))
        viewModel.load("7", "abc")
        advanceUntilIdle()
        assertEquals("/eapi/book/7/abc", server.nextPath())
    }

    private suspend fun TestScope.withDetail(
        block: suspend TestScope.(MockWebServer, SessionManager, MutableList<Book>, DetailViewModel) -> Unit,
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
                sessions.save(Session("7", "key", "z-lib.sk"))
                val api = EapiClient(
                    client = OkHttpClient(), baseUrlProvider = { server.url("/").toString() },
                    baseUrlForHost = { server.url("/").toString() }, sessionProvider = sessions::current,
                    dispatchers = dispatchers, maxAttempts = 1,
                    isAllowedUrl = { it.host == server.hostName && it.port == server.port },
                )
                val queued = mutableListOf<Book>()
                val viewModel = DetailViewModel(BookRepository(api), sessions, enqueue = { book ->
                    queued.add(book)
                    AppResult.Success(Unit)
                })
                block(server, sessions, queued, viewModel)
                sessions.clear()
                advanceUntilIdle()
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
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
