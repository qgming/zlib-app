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

    @Test fun `failure supports retry without claiming success`() = runTest {
        var calls = 0
        withModel({ if (++calls == 1) AppResult.Failure(AppError.Network(IOException())) else AppResult.Success(UpdateCheckResult.NoRelease) }) { model ->
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            assertEquals(UpdateFailure.Network, model.update.value.failure)
            assertNull(model.update.value.result)
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            assertNull(model.update.value.failure)
            assertEquals(UpdateCheckResult.NoRelease, model.update.value.result)
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

    @Test fun `GitHub rate limiting has its own actionable state`() = runTest {
        withModel({ AppResult.Failure(AppError.Server(403, "Rate limited")) }) { model ->
            model.checkUpdate("0.1.0")
            advanceUntilIdle()
            assertEquals(UpdateFailure.RateLimit, model.update.value.failure)
            assertNull(model.update.value.result)
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

    @Test fun `manual check reveals the ongoing startup check without duplicating the request`() = runTest {
        val result = CompletableDeferred<AppResult<UpdateCheckResult>>()
        var calls = 0
        withModel({ calls++; result.await() }) { model ->
            model.checkOnStart("0.1.0")
            advanceUntilIdle()
            model.checkUpdate("0.1.0")
            assertTrue(model.update.value.visible)
            result.complete(AppResult.Success(UpdateCheckResult.NoRelease))
            advanceUntilIdle()
            assertTrue(model.update.value.visible)
            assertEquals(1, calls)
        }
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
