package com.qgming.zlib.core.data.download

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Selecting a writer and controlling a record must not race. */
internal class DownloadQueueControl {
    private val selection = Mutex()
    @Volatile private var activeId: String? = null

    suspend fun <T> selectNext(idOf: (T) -> String, next: suspend () -> T?): T? = selection.withLock {
        currentCoroutineContext().ensureActive()
        next()?.also { activeId = idOf(it) }
    }

    // Called by the writer's finally; never await selection while a control joins the writer.
    fun finished() { activeId = null }

    suspend fun control(id: String, stopActive: suspend () -> Unit, apply: suspend () -> Unit) = selection.withLock {
        if (activeId == id) stopActive()
        apply()
    }

    suspend fun <T> accountChange(block: suspend (String?) -> T): T = selection.withLock { block(activeId) }

    /**
     * Runs a directory change while no new writer can be selected. The caller stops the current
     * writer before saving the new tree; holding the same mutex makes "switch directory" and
     * "start next item" mutually exclusive.
     */
    suspend fun <T> exclusive(block: suspend (activeId: String?) -> T): T = selection.withLock {
        block(activeId)
    }
}

/** A record may be removed only after the old writer has completely stopped. */
internal suspend fun stopDownloadWorker(worker: Job?, cancelNetwork: () -> Unit, afterStopped: suspend () -> Unit) {
    worker?.cancel()
    cancelNetwork()
    worker?.join()
    afterStopped()
}
