package com.qgming.zlib.core.data.download

import android.net.Uri
import androidx.room.withTransaction
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.data.account.AccountQuotaSwitcher
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.database.ZlibDatabase
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class DownloadRunner @Inject constructor(
    private val database: ZlibDatabase,
    private val engine: DownloadEngine,
    private val notifications: DownloadNotifications,
    private val directoryStore: DownloadDirectoryStore,
    private val dispatchers: AppDispatchers,
    private val sessions: SessionManager,
    private val quotaSwitcher: AccountQuotaSwitcher,
    private val journal: DownloadJournal,
) {
    private val controls = DownloadQueueControl()
    private val execution = Mutex()
    private val recovery = CoroutineScope(SupervisorJob() + dispatchers.io)
    @Volatile private var worker: Job? = null
    @Volatile private var acceptingQueueWork = false

    fun cancelActiveTransfer() = engine.cancelActiveTransfer()

    internal suspend fun control(id: String, stopActive: suspend () -> Unit, apply: suspend () -> Unit) =
        controls.control(id, stopActive, apply)

    internal suspend fun controlWithoutService(id: String, apply: suspend () -> Unit) =
        control(id, stopActive = { stopDownloadWorker(worker, ::cancelActiveTransfer) {} }, apply = apply)

    internal fun stopForForegroundLimit(reason: String) {
        cancelActiveTransfer()
        recovery.launch {
            try {
                execution.withLock { parkQueue(reason) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SafeLog.w("Download", "foreground queue state unavailable")
            }
        }
    }

    internal suspend fun pauseAfterLaunchRejection(): Boolean {
        if (acceptingQueueWork) return false
        return execution.withLock {
            if (acceptingQueueWork) return@withLock false
            parkQueue(DownloadCodes.FOREGROUND_UNAVAILABLE)
            true
        }
    }

    internal suspend fun discardPublication(item: DownloadEntity, tree: String): Boolean {
        val pending = journal.pending(tree, item.id, item.createdAt) ?: return true
        return engine.abandonPublication(pending)
    }

    private suspend fun parkQueue(reason: String) {
        database.downloadDao().pauseAllActive(DownloadStates.PAUSED, reason, now())
    }

    suspend fun <T> withAccountChange(stopIf: () -> Boolean = { true }, change: suspend () -> T): T = controls.accountChange { activeId ->
        val stopping = stopIf()
        if (stopping) stopDownloadWorker(worker, ::cancelActiveTransfer) { }
        if (stopping && activeId != null) withContext(dispatchers.io) {
            val row = database.downloadDao().byId(activeId)
            if (row != null && row.state != DownloadStates.COMPLETED) {
                database.downloadDao().updateState(activeId, DownloadStates.PAUSED, null, now())
            }
        }
        change()
    }

    /**
     * Directory changes are mutually exclusive with selecting the next writer. All unfinished
     * active records are paused first, the old writer is cancelled and joined, and only then is
     * the new tree validated and saved. A failed validation keeps the previous directory.
     */
    suspend fun withDirectoryChange(uri: Uri): AppResult<DownloadDirectoryChange> {
        directoryStore.ensureLoaded()
        // Re-selecting the same tree (including a first-time selection that is already saved)
        // must not pause an otherwise healthy queue just to re-save an identical URI.
        if (directoryStore.currentUri() == uri) {
            return when (val saved = directoryStore.setTree(uri)) {
                is AppResult.Failure -> saved
                is AppResult.Success -> AppResult.Success(
                    DownloadDirectoryChange(
                        withContext(dispatchers.io) { directoryStore.currentName() },
                        pausedTasks = 0,
                    ),
                )
            }
        }
        return controls.exclusive { active ->
            try {
                val dao = database.downloadDao()
                val paused = withContext(dispatchers.io) { dao.countActive() }
                SafeLog.d("Download", "directory change active=$active paused=$paused")
                withContext(dispatchers.io) {
                    dao.pauseAllActive(DownloadStates.PAUSED, null, now())
                }
                // Cancel the network call and wait until the old writer has fully stopped.
                stopDownloadWorker(worker, ::cancelActiveTransfer) { }
                // Progress writes may land while cancellation unwinds; pause again after the join.
                withContext(dispatchers.io) {
                    dao.pauseAllActive(DownloadStates.PAUSED, null, now())
                }
                when (val saved = directoryStore.setTree(uri)) {
                    is AppResult.Failure -> saved
                    is AppResult.Success -> AppResult.Success(
                        DownloadDirectoryChange(
                            withContext(dispatchers.io) { directoryStore.currentName() },
                            paused,
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                // The queue is already paused, so the safe state is preserved.
                AppResult.Failure(AppError.Storage(t))
            }
        }
    }

    suspend fun runQueue() = withContext(dispatchers.io) {
        execution.withLock {
            worker = currentCoroutineContext().job
            acceptingQueueWork = true
            try {
                val dao = database.downloadDao()
                dao.resetInterrupted()
                while (true) {
                    val item = controls.selectNext(DownloadEntity::id) { dao.nextActive() }
                    if (item == null) {
                        acceptingQueueWork = false
                        if (dao.nextActive() != null) {
                            acceptingQueueWork = true
                            continue
                        }
                        break
                    }
                    try {
                        if (!runItem(item)) break
                    } finally {
                        controls.finished()
                    }
                }
            } finally {
                acceptingQueueWork = false
                worker = null
            }
        }
    }

    internal suspend fun reconcilePublishedFiles() = withContext(dispatchers.io) {
        execution.withLock {
            controls.exclusive {
                val treeUri = directoryStore.ensureLoaded()?.toString() ?: return@exclusive
                val dao = database.downloadDao()
                journal.publications.first().filter { it.treeUri == treeUri }.forEach { pending ->
                    try {
                        val item = dao.byId(pending.taskId)
                        if (item == null || item.createdAt != pending.taskCreatedAt || item.state == DownloadStates.CANCELLED) {
                            engine.abandonPublication(pending)
                        } else if (item.state == DownloadStates.COMPLETED) {
                            if (pending.step == PublicationStep.Complete) engine.finishPublication(pending)
                            else engine.abandonPublication(pending)
                        } else {
                            val recovered = engine.recover(pending) ?: return@forEach
                            finishCompletion(item, recovered)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SafeLog.w("Download", "pending file reconciliation unavailable")
                    }
                }
            }
        }
    }

    private suspend fun runItem(item: DownloadEntity): Boolean {
        val dao = database.downloadDao()
        dao.update(item.id, DownloadStates.RESOLVING, item.bytesWritten, item.totalBytes, null, now())
        notifications.update(dao.byId(item.id) ?: item)

        var attempt = 1
        var lastBytes = item.bytesWritten
        var lastTotal = item.totalBytes
        var retriedSession = false
        val attemptedAccounts = mutableSetOf<String>()
        while (true) {
            val current = dao.byId(item.id) ?: break
            val requestSession = sessions.current()
            val outcome = try {
                engine.download(current, requestSession) { written, total ->
                    lastBytes = written
                    lastTotal = total
                    dao.update(item.id, DownloadStates.DOWNLOADING, written, total, null, now())
                    notifications.update(
                        current.copy(
                            state = DownloadStates.DOWNLOADING,
                            bytesWritten = written,
                            totalBytes = total,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                DownloadOutcome.Failed(
                    AppError.Storage(t),
                    if (t is SecurityException) DownloadCodes.STORAGE_PERMISSION else DownloadCodes.STORAGE,
                )
            }

            when (outcome) {
                is DownloadOutcome.Completed -> {
                    try {
                        finishCompletion(item, outcome)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        dao.update(item.id, DownloadStates.FAILED, outcome.totalBytes, outcome.totalBytes, DownloadCodes.STORAGE, now())
                        notifications.failed(current, DownloadCodes.STORAGE)
                        break
                    }
                    lastBytes = outcome.totalBytes
                    lastTotal = outcome.totalBytes
                    SafeLog.d("Download", "completed bytes=${outcome.totalBytes}")
                    notifications.completed(current, outcome.totalBytes, outcome.totalBytes)
                    break
                }

                is DownloadOutcome.Failed -> {
                    val error = outcome.error
                    if (error is AppError.Quota && requestSession != null && quotaSwitcher.switchAfterQuota(requestSession, attemptedAccounts)) {
                        attempt = 1
                        retriedSession = false
                        continue
                    }
                    if (error is AppError.SessionExpired && requestSession != null && sessions.current() == requestSession && !retriedSession) {
                        retriedSession = true
                        continue
                    }
                    if (isRetryable(error) && attempt < 4) {
                        dao.updateState(item.id, DownloadStates.QUEUED, null, now())
                        delay(1_000L shl (attempt - 1))
                        attempt++
                        continue
                    }
                    dao.update(item.id, DownloadStates.FAILED, lastBytes, lastTotal, outcome.code, now())
                    SafeLog.w("Download", "failed ${item.id} code=${outcome.code}")
                    notifications.failed(current, outcome.code)
                    if (error is AppError.SessionExpired && sessions.current() == requestSession && requestSession != null) {
                        sessions.expire(requestSession)
                    }
                    if (error is AppError.Quota || error is AppError.SessionExpired || error is AppError.NotLoggedIn) {
                        parkQueue(if (error is AppError.Quota) DownloadCodes.QUOTA else DownloadCodes.SESSION)
                        return false
                    }
                    break
                }
            }
        }
        return true
    }

    private suspend fun finishCompletion(item: DownloadEntity, outcome: DownloadOutcome.Completed) = withContext(NonCancellable) {
        val now = now()
        database.withTransaction {
            val current = database.downloadDao().byId(item.id)
                ?.takeIf { it.createdAt == outcome.publication.taskCreatedAt }
                ?: throw IOException("The download task changed")
            if (current.state == DownloadStates.COMPLETED) return@withTransaction
            database.downloadDao().update(
                item.id,
                DownloadStates.COMPLETED,
                outcome.totalBytes,
                outcome.totalBytes,
                null,
                now,
            )
            // Another format of the same book may have been enqueued meanwhile. Only associate
            // the file when the book row still describes this exact id:hash.
            val book = database.bookDao().byId(item.bookId)
            if (book != null && book.hash == item.hash) {
                database.bookDao().setLocalFile(item.bookId, outcome.uri, now, now)
            }
        }
        try {
            engine.finishPublication(outcome.publication)
        } catch (_: Exception) {
            SafeLog.w("Download", "completed file journal cleanup unavailable")
        }
    }

    private fun isRetryable(error: AppError): Boolean = when (error) {
        is AppError.Network -> true
        is AppError.Server -> error.code == 502 || error.code == 503 || error.code == 504
        else -> false
    }

    private fun now() = System.currentTimeMillis()
}
