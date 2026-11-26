package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModelStore
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.UpdateCheckResult
import com.qgming.zlib.core.model.AppRelease
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    @Test fun `checking is manual and repeated taps share an in flight check`() = runTest {
        var calls = 0
        val result = CompletableDeferred<AppResult<UpdateCheckResult>>()
        withModel({ calls++; result.await() }) { model ->
            advanceUntilIdle()
            assertEquals(0, calls)
            model.checkUpdate("0.1.0")
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(model.update.value.checking)
            result.complete(AppResult.Success(UpdateCheckResult.NoRelease))
            advanceUntilIdle()
            assertEquals(UpdateCheckResult.NoRelease, model.update.value.result)
            assertFalse(model.update.value.checking)
        }
    }

    @Test fun `connection failure emits one toast and a successful retry shows its result`() = runTest {
        var calls = 0
        withModel({ if (++calls == 1) AppResult.Failure(AppError.Network(IOException())) else AppResult.Success(UpdateCheckResult.NoRelease) }) { model ->
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            assertEquals(AboutUpdateState(), model.update.value)
            assertNetworkToast(model)
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            assertNull(model.update.value.failure)
            assertEquals(UpdateCheckResult.NoRelease, model.update.value.result)
            assertTrue(model.update.value.visible)
            assertNull(withTimeoutOrNull(1) { model.events.first() })
        }
    }

    @Test fun `dismissal cancels check and cannot reopen the sheet with a stale result`() = runTest {
        val result = CompletableDeferred<AppResult<UpdateCheckResult>>()
        withModel({ result.await() }) { model ->
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            model.dismissUpdate()
            result.complete(AppResult.Success(UpdateCheckResult.NoRelease))
            advanceUntilIdle()
            assertEquals(AboutUpdateState(), model.update.value)
        }
    }

    @Test fun `confirmed GitHub rate limiting emits one network toast without a sheet`() = runTest {
        val failures = listOf(AppError.Server(403, "Rate limited", 1_790_000_000_000L), AppError.Server(429, "Rate limited"))
        for (failure in failures) {
            withModel({ AppResult.Failure(failure) }) { model ->
                model.checkUpdate("0.1.0")
                advanceUntilIdle()
                assertEquals(AboutUpdateState(), model.update.value)
                assertNetworkToast(model)
            }
        }
    }

    @Test fun `cached network failures stay silent on startup and only toast on manual check`() = runTest {
        val failures = listOf(AppError.Server(429, "Rate limited", 1_790_000_000_000L), AppError.Network(IOException()))
        for (failure in failures) {
            val cached = UpdateCheckResult.Cached(AppRelease("v1.2.0", "", ""), failure)
            withModel({ AppResult.Success(cached) }) { model ->
                model.checkOnStart("1.1.0")
                advanceUntilIdle()
                assertEquals(AboutUpdateState(), model.update.value)
                assertNull(withTimeoutOrNull(1) { model.events.first() })
                model.checkUpdate("1.1.0")
                advanceUntilIdle()
                assertEquals(AboutUpdateState(), model.update.value)
                assertNetworkToast(model)
            }
        }
    }

    @Test fun `other forbidden responses keep the retry sheet without a network toast`() = runTest {
        withModel({ AppResult.Failure(AppError.Server(403, "Forbidden")) }) { model ->
            model.checkUpdate("1.1.0")
            advanceUntilIdle()
            assertEquals(AboutUpdateState(visible = true, failure = UpdateFailure.Response), model.update.value)
            assertNull(withTimeoutOrNull(1) { model.events.first() })
        }
    }

    @Test fun `startup network failures do not emit feedback or open a sheet`() = runTest {
        val failures = listOf(AppError.Network(IOException()), AppError.Server(429, "Rate limited"))
        for (failure in failures) {
            withModel({ AppResult.Failure(failure) }) { model ->
                model.checkOnStart("1.1.0")
                advanceUntilIdle()
                assertEquals(AboutUpdateState(), model.update.value)
                assertNull(withTimeoutOrNull(1) { model.events.first() })
            }
        }
    }

    @Test fun `cached notes from a parse failure remain marked cached in the retry sheet`() = runTest {
        val cached = UpdateCheckResult.Cached(AppRelease("v1.1.0", "", ""), AppError.Parse("Invalid release"))
        withModel({ AppResult.Success(cached) }) { model ->
            model.checkUpdate("1.1.0")
            advanceUntilIdle()
            assertEquals(AboutUpdateState(visible = true, result = cached, failure = UpdateFailure.Response), model.update.value)
            assertNull(withTimeoutOrNull(1) { model.events.first() })
        }
    }

    @Test fun `startup checks once and only opens the sheet for a newer release`() = runTest {
        var calls = 0
        withModel({ calls++; AppResult.Success(UpdateCheckResult.NoRelease) }) { model ->
            model.checkOnStart("0.1.0")
            model.checkOnStart("0.1.0")
            advanceUntilIdle()
            assertEquals(1, calls)
            assertFalse(model.update.value.visible)
        }
        withModel({ AppResult.Success(UpdateCheckResult.Available(AppRelease("v0.2.0", "Actual notes", ""))) }) { model ->
            model.checkOnStart("0.1.0")
            advanceUntilIdle()
            assertTrue(model.update.value.visible)
            assertTrue(model.update.value.result is UpdateCheckResult.Available)
        }
    }

    @Test fun `manual check shares ongoing startup request without opening a loading sheet`() = runTest {
        val result = CompletableDeferred<AppResult<UpdateCheckResult>>()
        var calls = 0
        withModel({ calls++; result.await() }) { model ->
            model.checkOnStart("0.1.0")
            advanceUntilIdle()
            model.checkUpdate("0.1.0")
            assertFalse(model.update.value.visible)
            result.complete(AppResult.Success(UpdateCheckResult.NoRelease))
            advanceUntilIdle()
            assertTrue(model.update.value.visible)
            assertEquals(1, calls)
        }
    }

    @Test fun `manual current version emits one toast event with no sheet`() = runTest {
        withModel({ AppResult.Success(UpdateCheckResult.Current(AppRelease("v1.1.0", "", ""))) }) { model ->
            model.checkUpdate("1.1.0")
            assertFalse(model.update.value.visible)
            advanceUntilIdle()
            assertFalse(model.update.value.visible)
            assertFalse(model.update.value.checking)
            assertEquals(AboutUpdateEvent.AlreadyCurrent, model.events.first())
            assertNull(withTimeoutOrNull(1) { model.events.first() })
        }
    }

    @Test fun `automatic current check stays silent even on repeat`() = runTest {
        withModel({ AppResult.Success(UpdateCheckResult.Current(AppRelease("v1.0.0", "", ""))) }) { model ->
            model.checkOnStart("1.1.0")
            model.checkOnStart("1.1.0")
            advanceUntilIdle()
            assertFalse(model.update.value.visible)
            assertNull(withTimeoutOrNull(1) { model.events.first() })
        }
    }

    @Test fun `manual tap during startup current check shares request and receives one toast`() = runTest {
        val result = CompletableDeferred<AppResult<UpdateCheckResult>>()
        var calls = 0
        withModel({ calls++; result.await() }) { model ->
            model.checkOnStart("1.1.0")
            advanceUntilIdle()
            model.checkUpdate("1.1.0")
            model.checkUpdate("1.1.0")
            assertFalse(model.update.value.visible)
            result.complete(AppResult.Success(UpdateCheckResult.Current(AppRelease("v1.0.0", "", ""))))
            advanceUntilIdle()
            assertEquals(1, calls)
            assertFalse(model.update.value.visible)
            assertEquals(AboutUpdateEvent.AlreadyCurrent, model.events.first())
            assertNull(withTimeoutOrNull(1) { model.events.first() })
        }
    }

    @Test fun `manual taps during startup rate limit check share request and receive one toast`() = runTest {
        val result = CompletableDeferred<AppResult<UpdateCheckResult>>()
        var calls = 0
        withModel({ calls++; result.await() }) { model ->
            model.checkOnStart("1.1.0")
            advanceUntilIdle()
            model.checkUpdate("1.1.0")
            model.checkUpdate("1.1.0")
            result.complete(AppResult.Failure(AppError.Server(429, "Rate limited")))
            advanceUntilIdle()
            assertEquals(1, calls)
            assertEquals(AboutUpdateState(), model.update.value)
            assertNetworkToast(model)
        }
    }

    private suspend fun assertNetworkToast(model: AboutViewModel) {
        assertEquals(AboutUpdateEvent.NetworkEnvironment, withTimeoutOrNull(1) { model.events.first() })
        assertNull(withTimeoutOrNull(1) { model.events.first() })
    }

    private suspend fun TestScope.withModel(check: suspend (String) -> AppResult<UpdateCheckResult>, block: suspend (AboutViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = AboutViewModel(check)
            store.put("about", model)
            block(model)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
