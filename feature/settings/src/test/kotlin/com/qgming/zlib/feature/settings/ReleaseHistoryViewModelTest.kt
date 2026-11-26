package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModelStore
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.update.ReleaseHistorySnapshot
import com.qgming.zlib.core.model.AppRelease
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalCoroutinesApi::class)
class ReleaseHistoryViewModelTest {
    @Test
    fun `newest semantic version leads history regardless of API order or old release edits`() {
        val releases = listOf(
            release("v1.9.0", "2026-11-23T08:00:00Z"),
            release("v1.10.0", "2026-11-22T08:00:00Z"),
            release("v1.10.0-rc.1", "2026-11-23T09:00:00Z"),
            release("v1.0.0", "2026-11-23T10:00:00Z"),
        )
        assertEquals(listOf("v1.10.0", "v1.10.0-rc.1", "v1.9.0", "v1.0.0"), orderedReleaseHistory(releases).map { it.tag })
    }

    @Test
    fun `unknown tags sort by real timestamps after versions and duplicate tags keep first response`() {
        val first = release("v1.2.0").copy(notes = "Original notes")
        val releases = listOf(
            release("invalid-time", "garbage"), release("old", "2025-01-01T08:00:00Z"),
            release("recent", "2026-01-01T10:00:00+02:00"), first,
            first.copy(notes = "Duplicate notes"), release("no-time"),
        )
        val ordered = orderedReleaseHistory(releases)
        assertEquals(listOf("v1.2.0", "recent", "old", "invalid-time", "no-time"), ordered.map { it.tag })
        assertEquals("Original notes", ordered.first().notes)
        // Mixed dated tags must not create a non-transitive comparator.
        assertEquals(ordered, orderedReleaseHistory(ordered))
    }

    @Test
    fun `repeated page loads share one request and retain sorted complete notes`() = runTest {
        var calls = 0
        val response = CompletableDeferred<AppResult<ReleaseHistorySnapshot>>()
        withModel({ calls++; response.await() }) { model ->
            model.load()
            model.load()
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(model.state.value.loading)
            val newest = release("v2.0.0").copy(notes = "Line one\n\nLine two")
            response.complete(AppResult.Success(ReleaseHistorySnapshot(listOf(release("v1.0.0"), newest))))
            advanceUntilIdle()
            assertEquals(newest, model.state.value.releases.first())
            assertTrue(model.state.value.loaded)
            assertFalse(model.state.value.loading)
            model.load()
            advanceUntilIdle()
            assertEquals(1, calls)
        }
    }

    @Test
    fun `failure is retryable and an empty history is loaded successfully`() = runTest {
        var calls = 0
        withModel({
            if (++calls == 1) AppResult.Failure(AppError.Server(503, "Unavailable"))
            else AppResult.Success(ReleaseHistorySnapshot(emptyList()))
        }) { model ->
            model.load()
            advanceUntilIdle()
            assertEquals(UpdateFailure.Response, model.state.value.failure)
            assertFalse(model.state.value.loaded)
            assertNull(withTimeoutOrNull(1) { model.networkFailures.first() })
            model.load()
            advanceUntilIdle()
            assertEquals(2, calls)
            assertTrue(model.state.value.loaded)
            assertTrue(model.state.value.releases.isEmpty())
            assertFalse(model.state.value.failed)
        }
    }

    @Test
    fun `connection failure emits one network toast for a shared load and allows retry`() = runTest {
        var calls = 0
        val response = CompletableDeferred<AppResult<ReleaseHistorySnapshot>>()
        withModel({
            if (++calls == 1) response.await()
            else AppResult.Success(ReleaseHistorySnapshot(emptyList()))
        }) { model ->
            model.load()
            model.load()
            advanceUntilIdle()
            response.complete(AppResult.Failure(AppError.Network(IOException())))
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals(ReleaseHistoryState(failure = UpdateFailure.NetworkEnvironment), model.state.value)
            assertNetworkToast(model)
            model.load()
            advanceUntilIdle()
            assertTrue(model.state.value.loaded)
            assertNull(withTimeoutOrNull(1) { model.networkFailures.first() })
        }
    }

    @Test
    fun `confirmed rate limit responses emit one network toast without loading empty history`() = runTest {
        val failures = listOf(AppError.Server(403, "Rate limited", 1_790_000_000_000L), AppError.Server(429, "Rate limited"))
        for (failure in failures) {
            withModel({ AppResult.Failure(failure) }) { model ->
                model.load()
                advanceUntilIdle()
                assertEquals(ReleaseHistoryState(failure = UpdateFailure.NetworkEnvironment), model.state.value)
                assertNetworkToast(model)
            }
        }
    }

    @Test
    fun `cached response failure keeps notes and later network failure retains them`() = runTest {
        var calls = 0
        val cached = release("v1.0.0")
        withModel({
            if (++calls == 1) AppResult.Success(ReleaseHistorySnapshot(listOf(cached), AppError.Parse("Invalid history")))
            else AppResult.Failure(AppError.Network(IOException()))
        }) { model ->
            model.load()
            advanceUntilIdle()
            assertEquals(UpdateFailure.Response, model.state.value.failure)
            assertTrue(model.state.value.cached)
            assertFalse(model.state.value.loaded)
            assertNull(withTimeoutOrNull(1) { model.networkFailures.first() })
            model.load()
            advanceUntilIdle()
            assertEquals(listOf(cached), model.state.value.releases)
            assertTrue(model.state.value.cached)
            assertFalse(model.state.value.loaded)
            assertEquals(UpdateFailure.NetworkEnvironment, model.state.value.failure)
            assertNetworkToast(model)
        }
    }

    @Test
    fun `cached notes retain failure and can be refreshed into live notes`() = runTest {
        var calls = 0
        val cached = release("v1.0.0")
        val live = release("v1.1.0")
        withModel({
            if (++calls == 1) AppResult.Success(ReleaseHistorySnapshot(listOf(cached), AppError.Server(429, "Rate limited", 1_790_000_000_000L)))
            else AppResult.Success(ReleaseHistorySnapshot(listOf(live)))
        }) { model ->
            model.load()
            advanceUntilIdle()
            assertEquals(listOf(cached), model.state.value.releases)
            assertTrue(model.state.value.cached)
            assertEquals(UpdateFailure.NetworkEnvironment, model.state.value.failure)
            assertNetworkToast(model)
            model.load()
            advanceUntilIdle()
            assertEquals(listOf(live), model.state.value.releases)
            assertFalse(model.state.value.cached)
            assertFalse(model.state.value.failed)
            assertNull(withTimeoutOrNull(1) { model.networkFailures.first() })
        }
    }

    private suspend fun assertNetworkToast(model: ReleaseHistoryViewModel) {
        assertEquals(Unit, withTimeoutOrNull(1) { model.networkFailures.first() })
        assertNull(withTimeoutOrNull(1) { model.networkFailures.first() })
    }

    private fun release(tag: String, date: String? = null) = AppRelease(tag, "Notes for $tag", "https://github.com/qgming/zlib-app/releases/tag/$tag", publishedAt = date)

    private suspend fun TestScope.withModel(load: suspend () -> AppResult<ReleaseHistorySnapshot>, block: suspend (ReleaseHistoryViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = ReleaseHistoryViewModel(load)
            store.put("history", model)
            block(model)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
