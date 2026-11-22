package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModelStore
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
class AboutDocumentsViewModelTest {
    private val licenses = AboutLicenses("MIT License", "Project NOTICE", "Apache License 2.0")

    @Test
    fun `license loads once despite repeated taps and retains complete documents`() = runTest {
        val response = CompletableDeferred<AboutLicenses>()
        var reads = 0
        withModel({ reads++; response.await() }) { model ->
            model.loadLicenses()
            model.loadLicenses()
            advanceUntilIdle()
            assertEquals(1, reads)
            assertTrue(model.state.value.loading)
            assertNull(model.state.value.licenses)
            response.complete(licenses)
            advanceUntilIdle()
            assertEquals(licenses, model.state.value.licenses)
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.failed)
            model.loadLicenses()
            advanceUntilIdle()
            assertEquals(1, reads)
        }
    }

    @Test
    fun `read failures show retry and successful retry clears error`() = runTest {
        var reads = 0
        withModel({ if (++reads == 1) throw IOException("Local read failed") else licenses }) { model ->
            model.loadLicenses()
            advanceUntilIdle()
            assertTrue(model.state.value.failed)
            assertFalse(model.state.value.loading)
            assertNull(model.state.value.licenses)
            model.loadLicenses()
            advanceUntilIdle()
            assertEquals(2, reads)
            assertEquals(licenses, model.state.value.licenses)
            assertFalse(model.state.value.failed)
        }
    }

    @Test
    fun `missing document text is not presented as a successful license list`() = runTest {
        withModel({ licenses.copy(notice = " ") }) { model ->
            model.loadLicenses()
            advanceUntilIdle()
            assertTrue(model.state.value.failed)
            assertNull(model.state.value.licenses)
        }
    }

    private suspend fun TestScope.withModel(read: suspend () -> AboutLicenses, block: suspend (AboutDocumentsViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val model = AboutDocumentsViewModel(read)
            store.put("about", model)
            block(model)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
