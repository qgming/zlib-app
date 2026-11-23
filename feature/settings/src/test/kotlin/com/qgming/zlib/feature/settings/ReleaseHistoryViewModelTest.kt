package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModelStore
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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
        val response = CompletableDeferred<AppResult<List<AppRelease>>>()
        withModel({ calls++; response.await() }) { model ->
            model.load()
            model.load()
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(model.state.value.loading)
            val newest = release("v2.0.0").copy(notes = "Line one\n\nLine two")
            response.complete(AppResult.Success(listOf(release("v1.0.0"), newest)))
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
            else AppResult.Success(emptyList())
        }) { model ->
            model.load()
            advanceUntilIdle()
            assertTrue(model.state.value.failed)
            assertFalse(model.state.value.loaded)
            model.load()
            advanceUntilIdle()
            assertEquals(2, calls)
            assertTrue(model.state.value.loaded)
            assertTrue(model.state.value.releases.isEmpty())
            assertFalse(model.state.value.failed)
        }
    }

    private fun release(tag: String, date: String? = null) = AppRelease(tag, "Notes for $tag", "https://github.com/qgming/zlib-app/releases/tag/$tag", publishedAt = date)

    private suspend fun TestScope.withModel(load: suspend () -> AppResult<List<AppRelease>>, block: suspend (ReleaseHistoryViewModel) -> Unit) {
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
