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
    private val licenses = AboutLicenses(
        "MIT License: qgming", "Apache License 2.0",
        "MIT License: heartleo", "MIT License: Jordon de Hoog", "Jakarta NOTICE", "SharedView publisher terms",
    )

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
            assertEquals(licenses.entries(), model.state.value.entries)
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
        listOf(
            licenses.copy(project = ""), licenses.copy(apache = ""),
            licenses.copy(heartleo = ""), licenses.copy(materialkolor = ""),
            licenses.copy(jakartaNotice = ""), licenses.copy(sharedView = ""),
        ).forEach { incomplete ->
            withModel({ incomplete }) { model ->
                model.loadLicenses()
                advanceUntilIdle()
                assertTrue(model.state.value.failed)
                assertNull(model.state.value.licenses)
                assertTrue(model.state.value.entries.isEmpty())
            }
        }
    }

    @Test
    fun `offline catalog keeps licenses paired with their owners and retains SDK boundary`() {
        val entries = licenses.entries()
        assertEquals("project", entries.first().id)
        assertFalse(entries.any { it.id == "notice" || it.title == "NOTICE" })
        assertEquals(entries.size, entries.map { it.id }.distinct().size)
        assertTrue(entries.all { it.title.isNotBlank() && it.body.isNotBlank() })
        assertEquals(licenses.project, entries.single { it.id == "project" }.body.substringAfter("\n\n"))
        assertTrue(entries.single { it.id == "heartleo" }.body.endsWith(licenses.heartleo))
        assertFalse(entries.single { it.id == "heartleo" }.body.contains(licenses.project))
        assertTrue(entries.single { it.id == "materialkolor" }.body.contains(licenses.materialkolor))
        assertTrue(entries.single { it.id == "jakarta-inject" }.body.contains(licenses.jakartaNotice))
        assertTrue(entries.filter { "Apache-2.0" in it.license }.all { it.body.endsWith(licenses.apache) })
        val sdk = entries.single { it.id == "sharedview" }
        assertTrue(sdk.body.endsWith(licenses.sharedView))
        assertTrue(sdk.license.isEmpty())
        assertFalse(sdk.body.contains(licenses.project))
        assertFalse(sdk.body.contains(licenses.apache))
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
