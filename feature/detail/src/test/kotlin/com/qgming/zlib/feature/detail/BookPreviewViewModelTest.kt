package com.qgming.zlib.feature.detail

import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.model.OnlineBookPreview
import com.qgming.zlib.core.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BookPreviewViewModelTest {
    @Test
    fun `opening resolves once and refresh asks again with the current mirror`() = withSessions { sessions ->
        val requested = mutableListOf<String>()
        val resolve: suspend (String, String, Session?) -> AppResult<OnlineBookPreview?> = { id, hash, session ->
            assertEquals("7:abc", "$id:$hash")
            val domain = assertNotNull(session).domain
            requested += domain
            AppResult.Success(preview(domain, requested.size))
        }
        val viewModel = BookPreviewViewModel(sessions, resolve)
        try {
            viewModel.open("7", "abc")
            viewModel.open("7", "abc")
            advanceUntilIdle()
            assertEquals(preview("zlib.bz", 1).readerUrl, viewModel.currentPreview()?.readerUrl)
            assertIs<BookPreviewState.Ready>(viewModel.state.value)

            viewModel.refresh("7", "abc")
            viewModel.refresh("7", "abc")
            assertEquals(BookPreviewState.Loading, viewModel.state.value)
            assertNull(viewModel.currentPreview())
            advanceUntilIdle()
            assertEquals(preview("zlib.bz", 2).readerUrl, viewModel.currentPreview()?.readerUrl)

            sessions.updateDomain(assertNotNull(sessions.current()), "zh.z-lib.by")
            assertEquals(preview("zlib.bz", 2).readerUrl, viewModel.currentPreview()?.readerUrl)
            viewModel.refresh("7", "abc")
            advanceUntilIdle()
            assertEquals(preview("zh.z-lib.by", 3).readerUrl, viewModel.currentPreview()?.readerUrl)

            val reopened = BookPreviewViewModel(sessions, resolve)
            try {
                reopened.open("7", "abc")
                advanceUntilIdle()
                assertEquals(preview("zh.z-lib.by", 4).readerUrl, reopened.currentPreview()?.readerUrl)
                assertEquals(listOf("zlib.bz", "zlib.bz", "zh.z-lib.by", "zh.z-lib.by"), requested)
            } finally {
                reopened.viewModelScope.cancel()
            }
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `an anonymous session can still open a public preview`() = withSessions { sessions ->
        sessions.clear()
        var receivedSession = false
        val viewModel = BookPreviewViewModel(sessions) { _, _, session ->
            receivedSession = session == null
            AppResult.Success(preview("zlib.bz", 1))
        }
        try {
            viewModel.open("7", "abc")
            advanceUntilIdle()
            assertEquals(true, receivedSession)
            assertEquals(preview("zlib.bz", 1).readerUrl, viewModel.currentPreview()?.readerUrl)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun `failures and unavailable links map to explicit retryable states`() = withSessions { sessions ->
        val results = ArrayDeque(
            listOf(
                AppResult.Failure(AppError.Network(RuntimeException("synthetic"))),
                AppResult.Success<OnlineBookPreview?>(null),
                AppResult.Success<OnlineBookPreview?>(preview("zlib.bz", 1)),
            ),
        )
        val viewModel = BookPreviewViewModel(sessions) { _, _, _ -> results.removeFirst() }
        try {
            viewModel.open("7", "abc")
            advanceUntilIdle()
            assertEquals(BookPreviewState.Failed, viewModel.state.value)

            viewModel.refresh("7", "abc")
            advanceUntilIdle()
            assertEquals(BookPreviewState.Unavailable, viewModel.state.value)

            viewModel.refresh("7", "abc")
            advanceUntilIdle()
            assertEquals(preview("zlib.bz", 1).readerUrl, viewModel.currentPreview()?.readerUrl)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun withSessions(block: suspend TestScope.(SessionManager) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val dispatchers = object : AppDispatchers {
                override val main = dispatcher
                override val io = dispatcher
                override val default = dispatcher
            }
            val sessions = SessionManager(object : SessionStore {
                private var vault = SessionVault()
                override suspend fun readVault() = vault
                override suspend fun saveVault(vault: SessionVault) { this.vault = vault }
                override suspend fun clear() { vault = SessionVault() }
            }, dispatchers)
            sessions.save(Session("1", "unit-test-key", "zlib.bz"))
            block(sessions)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun preview(host: String, version: Int): OnlineBookPreview = checkNotNull(OnlineBookPreview.fromDetail(
        readerUrl = "https://$host/read/example?signature=synthetic-$version",
        bookUrl = "/book/example",
        detailHost = host,
    ))
}
