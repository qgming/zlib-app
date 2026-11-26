package com.qgming.zlib.core.data.update

import com.qgming.zlib.core.model.AppRelease
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class UpdateDownloadAttempts {
    private val lock = Any()
    private var generation = 0L
    private var worker: Job? = null
    private val current = MutableStateFlow(UpdateDownloadState())
    val state = current.asStateFlow()

    fun begin(release: AppRelease): Long? = synchronized(lock) {
        if (current.value.busy || current.value.phase == UpdateDownloadPhase.Ready) return null
        generation += 1
        worker = null
        current.value = UpdateDownloadState(phase = UpdateDownloadPhase.Starting, release = release)
        generation
    }

    fun restore(release: AppRelease, savedUri: String) = synchronized(lock) {
        if (current.value.phase == UpdateDownloadPhase.Idle) {
            current.value = UpdateDownloadState(phase = UpdateDownloadPhase.Ready, release = release, savedUri = savedUri)
        }
    }

    fun isPhase(id: Long, phase: UpdateDownloadPhase): Boolean = synchronized(lock) {
        id != 0L && generation == id && current.value.phase == phase
    }

    fun isBusy(id: Long): Boolean = synchronized(lock) { id != 0L && generation == id && current.value.busy }

    fun claim(id: Long, job: Job): AppRelease? = synchronized(lock) {
        val state = current.value
        if (id == 0L || generation != id || state.phase != UpdateDownloadPhase.Queued) return null
        val release = state.release ?: return null
        worker = job
        current.value = state.copy(phase = UpdateDownloadPhase.Downloading)
        release
    }

    fun release(id: Long) = synchronized(lock) {
        if (generation == id) worker = null
    }

    fun change(id: Long, transform: (UpdateDownloadState) -> UpdateDownloadState): Boolean = synchronized(lock) {
        if (id == 0L || generation != id || !current.value.busy) return false
        current.value = transform(current.value)
        true
    }

    fun fail(id: Long, reason: UpdateDownloadFailure) {
        change(id) { it.copy(phase = UpdateDownloadPhase.Failed, failure = reason) }
    }

    fun interrupt(id: Long) = stop(id, UpdateDownloadFailure.Interrupted)

    fun cancel() = stop(null, null)

    private fun stop(id: Long?, reason: UpdateDownloadFailure?) {
        val activeWorker = synchronized(lock) {
            val state = current.value
            val canCancelReady = reason == null && id == null && state.phase == UpdateDownloadPhase.Ready
            if (!state.busy && !canCancelReady) return
            if (id != null && (id == 0L || generation != id)) return
            val active = worker
            worker = null
            if (reason == null) generation += 1
            current.value = if (reason == null) UpdateDownloadState() else state.copy(phase = UpdateDownloadPhase.Failed, failure = reason)
            active
        }
        activeWorker?.cancel()
    }
}
