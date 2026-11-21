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
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
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
                val viewModel = SearchViewModel(BookRepository(client))
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
