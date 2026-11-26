package com.qgming.zlib.core.data.update

import com.qgming.zlib.core.model.AppRelease
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateDownloadAttemptsTest {
    private val release = AppRelease("v1.5.0", "Notes", "https://github.com/qgming/zlib-app/releases")

    @Test
    fun `timeout stops the active worker and a retry rejects old callbacks`() {
        val attempts = UpdateDownloadAttempts()
        val first = assertNotNull(attempts.begin(release))
        attempts.change(first) { it.copy(phase = UpdateDownloadPhase.Queued) }
        val worker = Job()
        assertEquals(release, attempts.claim(first, worker))
        assertNull(attempts.claim(first, Job()))
        attempts.interrupt(first)
        assertTrue(worker.isCancelled)
        assertEquals(UpdateDownloadFailure.Interrupted, attempts.state.value.failure)
        assertEquals(release, attempts.state.value.release)

        val second = assertNotNull(attempts.begin(release))
        attempts.change(second) { it.copy(phase = UpdateDownloadPhase.Queued) }
        val retry = Job()
        assertEquals(release, attempts.claim(second, retry))
        assertFalse(attempts.change(first) { it.copy(phase = UpdateDownloadPhase.Ready) })
        attempts.interrupt(first)
        assertFalse(retry.isCancelled)
        assertEquals(UpdateDownloadPhase.Downloading, attempts.state.value.phase)
        attempts.cancel()
        assertTrue(retry.isCancelled)
        assertEquals(UpdateDownloadPhase.Idle, attempts.state.value.phase)
    }

    @Test
    fun `timeout leaves an already verified installer available`() {
        val attempts = UpdateDownloadAttempts()
        val id = assertNotNull(attempts.begin(release))
        attempts.change(id) { it.copy(phase = UpdateDownloadPhase.Queued) }
        val worker = Job()
        assertEquals(release, attempts.claim(id, worker))
        attempts.change(id) { it.copy(phase = UpdateDownloadPhase.Ready, savedUri = "content://provider/file") }
        attempts.interrupt(id)
        assertEquals(UpdateDownloadPhase.Ready, attempts.state.value.phase)
        assertEquals("content://provider/file", attempts.state.value.savedUri)
        assertFalse(worker.isCancelled)
        worker.complete()
    }
}
