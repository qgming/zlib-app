package com.qgming.zlib.core.data.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadWorkerControlTest {
    @Test
    fun `record removal waits for writer cleanup and cancels network first`() = runTest {
        val events = mutableListOf<String>()
        var writing = true
        val worker = launch {
            try { awaitCancellation() } finally {
                withContext(NonCancellable) { delay(100); writing = false; events += "writer stopped" }
            }
        }
        runCurrent()
        stopDownloadWorker(worker, cancelNetwork = { events += "network cancelled" }) {
            assertFalse(writing)
            events += "record removed"
        }
        assertTrue(worker.isCompleted)
        assertEquals(listOf("network cancelled", "writer stopped", "record removed"), events)
    }

    @Test
    fun `completed records can be removed without a running writer`() = runTest {
        var removed = 0
        stopDownloadWorker(null, cancelNetwork = {}) { removed++ }
        assertEquals(1, removed)
    }

    @Test
    fun `removing another record leaves the current writer running`() = runTest {
        val control = DownloadQueueControl()
        control.selectNext({ it }) { "active" }
        var stopped = false
        var removed = false
        control.control("completed", stopActive = { stopped = true }) { removed = true }
        assertFalse(stopped)
        assertTrue(removed)
        control.control("active", stopActive = { stopped = true }) { }
        assertTrue(stopped)
    }

    @Test
    fun `record cannot start while it is being removed`() = runTest {
        val control = DownloadQueueControl()
        val events = mutableListOf<String>()
        var record: String? = "queued"
        val removal = launch {
            control.control("queued", stopActive = { error("no writer") }) {
                delay(100)
                record = null
                events += "removed"
            }
        }
        runCurrent()
        val selection = launch {
            val next = control.selectNext({ it }) { record }
            assertEquals(null, next)
            events += "selection finished"
        }
        removal.join()
        selection.join()
        assertEquals(listOf("removed", "selection finished"), events)
    }

    @Test
    fun `writer completion cannot deadlock a control waiting for it`() = runTest {
        val control = DownloadQueueControl()
        val events = mutableListOf<String>()
        val worker = launch {
            control.selectNext({ it }) { "active" }
            try { awaitCancellation() } finally { control.finished(); events += "stopped" }
        }
        runCurrent()
        control.control("active", stopActive = { stopDownloadWorker(worker, {}) {} }) { events += "removed" }
        assertEquals(listOf("stopped", "removed"), events)
    }

    @Test
    fun `directory change excludes the next writer selection`() = runTest {
        val control = DownloadQueueControl()
        val events = mutableListOf<String>()
        var record: String? = "queued"
        val changing = launch {
            control.exclusive { active ->
                assertNull(active)
                delay(100)
                record = null
                events += "directory saved"
            }
        }
        runCurrent()
        val selection = launch {
            val next = control.selectNext({ it }) { record }
            assertNull(next)
            events += "selection finished"
        }
        changing.join()
        selection.join()
        assertEquals(listOf("directory saved", "selection finished"), events)
    }
}
