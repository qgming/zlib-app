package com.qgming.zlib.feature.search

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.network.EapiClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import androidx.lifecycle.SavedStateHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun `restored language outside fallback is verified against current options before search`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val client = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null },
                    dispatchers = object : AppDispatchers {
                        override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
                    }, maxAttempts = 1)
                server.enqueue(MockResponse().setBody("""{"success":1,"languages":{"french":"法语","english":"英语"}}"""))
                server.enqueue(MockResponse().setBody("""{"success":1,"extensions":["epub","pdf"]}"""))
                server.enqueue(bookPage(1, 1))
                val viewModel = SearchViewModel(BookRepository(client), SavedStateHandle(mapOf(
                    "submitted" to true, "submittedQuery" to "dune", "query" to "edited draft", "languages" to arrayListOf("french"),
                )))
                advanceUntilIdle()
                assertEquals("/eapi/info/languages", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
                assertEquals("/eapi/info/extensions", assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).path)
                val form = ("https://example.test/?" + server.nextForm()).toHttpUrl()
                assertEquals("dune", form.queryParameter("message"))
                assertEquals("french", form.queryParameter("languages[]"))
                assertEquals("edited draft", viewModel.state.value.query)
                assertEquals(setOf("french"), viewModel.state.value.filters.languages)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `failed append retains loaded books and retries the same submitted page`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val client = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null },
                    dispatchers = object : AppDispatchers {
                        override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
                    }, maxAttempts = 1)
                val viewModel = SearchViewModel(BookRepository(client), SavedStateHandle())
                server.enqueue(bookPage(1, 1))
                viewModel.onQueryChange("dune"); viewModel.search(); advanceUntilIdle(); server.nextForm()
                server.enqueue(MockResponse().setResponseCode(503).setBody("""{"success":0,"error":"Unavailable"}"""))
                viewModel.loadMore(); advanceUntilIdle(); server.nextForm()
                assertNotNull(viewModel.state.value.appendError)
                assertEquals(listOf("1"), viewModel.state.value.books.map { it.id })
                viewModel.onQueryChange("edited")
                server.enqueue(bookPage(2, 2))
                viewModel.loadMore(); advanceUntilIdle()
                val form = ("https://example.test/?" + server.nextForm()).toHttpUrl()
                assertEquals("dune", form.queryParameter("message"))
                assertEquals("2", form.queryParameter("page"))
                assertEquals(false, viewModel.state.value.hasMore)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `process restoration searches submitted text once while preserving edited draft`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val client = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null },
                    dispatchers = object : AppDispatchers {
                        override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
                    }, maxAttempts = 1)
                server.enqueue(bookPage(1, 1))
                val saved = SavedStateHandle(mapOf("query" to "unfinished draft", "submittedQuery" to "dune", "submitted" to true,
                    "languages" to arrayListOf("english"), "exact" to true))
                val viewModel = SearchViewModel(BookRepository(client), saved)
                viewModel.initializeQuery("original author")
                advanceUntilIdle()
                val form = ("https://example.test/?" + server.nextForm()).toHttpUrl()
                assertEquals("dune", form.queryParameter("message"))
                assertEquals("english", form.queryParameter("languages[]"))
                assertEquals("1", form.queryParameter("e"))
                assertEquals("unfinished draft", viewModel.state.value.query)
                assertEquals(1, server.requestCount)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `duplicate next page ends pagination and does not repeatedly request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val client = EapiClient(OkHttpClient(), { server.url("/").toString() }, { null },
                    dispatchers = object : AppDispatchers {
                        override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
                    }, maxAttempts = 1)
                val viewModel = SearchViewModel(BookRepository(client), SavedStateHandle())
                server.enqueue(bookPage(1, 1)); viewModel.onQueryChange("book"); viewModel.search(); advanceUntilIdle()
                server.enqueue(bookPage(1, 2)); viewModel.loadMore(); advanceUntilIdle()
                assertEquals(false, viewModel.state.value.hasMore)
                assertEquals(1, viewModel.state.value.books.size)
                viewModel.loadMore(); advanceUntilIdle()
                assertEquals(2, server.requestCount)
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun `contributor query auto searches once without old filters and survives reentry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val client = EapiClient(
                    client = OkHttpClient(),
                    baseUrlProvider = { server.url("/").toString() },
                    sessionProvider = { null },
                    dispatchers = object : AppDispatchers {
                        override val io = dispatcher
                        override val default = dispatcher
                        override val main = dispatcher
                    },
                    maxAttempts = 1,
                )
                val viewModel = SearchViewModel(BookRepository(client), SavedStateHandle())
                viewModel.applyFilters(SearchFilters(languages = setOf("english"), exact = true))
                viewModel.initializeQuery(" ")
                assertEquals(0, server.requestCount)
                server.enqueue(bookPage(id = 1, page = 1))
                viewModel.initializeQuery(" 丹尼尔·卡尼曼 ")
                viewModel.initializeQuery("丹尼尔·卡尼曼")
                advanceUntilIdle()
                val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
                assertEquals("/eapi/book/search", request.path)
                val form = ("https://example.test/?" + request.body.readUtf8()).toHttpUrl()
                assertEquals(setOf("message", "page", "limit"), form.queryParameterNames)
                assertEquals("丹尼尔·卡尼曼", form.queryParameter("message"))
                assertEquals("1", form.queryParameter("page"))
                assertEquals(SearchFilters(), viewModel.state.value.filters)

                server.enqueue(bookPage(id = 2, page = 2))
                viewModel.loadMore()
                advanceUntilIdle()
                server.nextForm()
                viewModel.onQueryChange("edited query")
                viewModel.initializeQuery("丹尼尔·卡尼曼")
                advanceUntilIdle()
                assertEquals("edited query", viewModel.state.value.query)
                assertEquals(2, viewModel.state.value.page)
                assertEquals(listOf("1", "2"), viewModel.state.value.books.map { it.id })
                assertEquals(2, server.requestCount)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `pagination keeps the submitted query and applying filters starts at page one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            MockWebServer().use { server ->
                val client = EapiClient(
                    client = OkHttpClient(),
                    baseUrlProvider = { server.url("/").toString() },
                    sessionProvider = { null },
                    dispatchers = object : AppDispatchers {
                        override val io = dispatcher
                        override val default = dispatcher
                        override val main = dispatcher
                    },
                    maxAttempts = 1,
                )
                val viewModel = SearchViewModel(BookRepository(client), SavedStateHandle())
                server.enqueue(bookPage(id = 1, page = 1))
                viewModel.onQueryChange("dune")
                viewModel.search()
                advanceUntilIdle()
                assertEquals("message=dune&page=1&limit=50", server.nextForm())

                viewModel.onQueryChange("foundation")
                server.enqueue(bookPage(id = 2, page = 2))
                viewModel.loadMore()
                advanceUntilIdle()
                assertEquals("message=dune&page=2&limit=50", server.nextForm())
                assertEquals(listOf("1", "2"), viewModel.state.value.books.map { it.id })

                server.enqueue(bookPage(id = 3, page = 1))
                viewModel.applyFilters(SearchFilters(exact = true))
                advanceUntilIdle()
                assertEquals("message=foundation&page=1&limit=50&e=1", server.nextForm())
                assertEquals(listOf("3"), viewModel.state.value.books.map { it.id })
                assertTrue(viewModel.state.value.filters.exact)
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun MockWebServer.nextForm(): String = assertNotNull(takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()

    private fun bookPage(id: Int, page: Int): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"success":1,"books":[{"id":$id,"hash":"abc"}],"pagination":{"current":$page,"total_pages":2}}""",
        )
}
