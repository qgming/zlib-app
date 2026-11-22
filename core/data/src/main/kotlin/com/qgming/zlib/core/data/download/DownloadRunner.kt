package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.data.account.AccountQuotaSwitcher
import com.qgming.zlib.core.data.session.SessionManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Singleton
class DownloadRunner @Inject constructor(
    private val database: ZlibDatabase,
    private val engine: DownloadEngine,
    private val notifications: DownloadNotifications,
    private val dispatchers: AppDispatchers,
    private val sessions: SessionManager,
    private val quotaSwitcher: AccountQuotaSwitcher,
) {
    private val controls = DownloadQueueControl()
    @Volatile private var worker: Job? = null

    fun cancelActiveTransfer() = engine.cancelActiveTransfer()

    internal suspend fun control(id: String, stopActive: suspend () -> Unit, apply: suspend () -> Unit) =
        controls.control(id, stopActive, apply)

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

    suspend fun runQueue() = withContext(dispatchers.io) {
        worker = currentCoroutineContext().job
        try {
            val dao = database.downloadDao()
            dao.resetInterrupted()
            dao.invalidateTinyCompleted()
            while (true) {
                val item = controls.selectNext(DownloadEntity::id) { dao.nextActive() } ?: break
                try {
                    if (!runItem(item)) break
                } finally {
                    controls.finished()
                }
            }
        } finally { worker = null }
    }

    private suspend fun runItem(item: DownloadEntity): Boolean {
        val dao = database.downloadDao()
        val books = database.bookDao()
        dao.update(item.id, DownloadStates.RESOLVING, item.bytesWritten, item.totalBytes, null, now())
        notifications.update(dao.byId(item.id) ?: item)

        var attempt = 1
        var lastBytes = item.bytesWritten
        var lastTotal = item.totalBytes
        val attemptedAccounts = mutableSetOf<String>()
        while (true) {
            val current = dao.byId(item.id) ?: break
            val requestSession = sessions.current()
            val result = try {
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
                AppResult.Failure(AppError.Storage(t))
            }

            when (result) {
                is AppResult.Success -> {
                    dao.update(item.id, DownloadStates.COMPLETED, lastBytes, lastTotal, null, now())
                    books.setLocalFile(item.bookId, result.value, now(), now())
                    SafeLog.d("Download", "completed ${item.id} bytes=$lastBytes uri=${result.value}")
                    notifications.completed(current, lastBytes, lastTotal)
                    break
                }

                is AppResult.Failure -> {
                    val error = result.error
                    if (error is AppError.Quota && requestSession != null && quotaSwitcher.switchAfterQuota(requestSession, attemptedAccounts)) {
                        attempt = 1
                        continue
                    }
                    if (isRetryable(error) && attempt < 4) {
                        dao.updateState(item.id, DownloadStates.QUEUED, null, now())
                        delay(1_000L shl (attempt - 1))
                        attempt++
                        continue
                    }
                    dao.update(item.id, DownloadStates.FAILED, lastBytes, lastTotal, errorCode(error), now())
                    SafeLog.w("Download", "failed ${item.id} code=${errorCode(error)}")
                    notifications.failed(current, errorCode(error))
                    if (error is AppError.Quota) {
                        // Park the remaining queue; another START must not repeatedly hit the same limit.
                        while (true) {
                            val pending = dao.nextActive() ?: break
                            dao.updateState(pending.id, DownloadStates.PAUSED, "quota", now())
                        }
                        return false
                    }
                    break
                }
            }
        }
        return true
    }

    private fun isRetryable(error: AppError): Boolean = when (error) {
        is AppError.Network -> true
        is AppError.Server -> error.code == 502 || error.code == 503 || error.code == 504
        else -> false
    }

    private fun errorCode(error: AppError): String = when (error) {
        is AppError.Quota -> "quota"
        AppError.SessionExpired, AppError.NotLoggedIn -> "session"
        is AppError.DomainBlocked -> "domain"
        is AppError.Network -> "network"
        is AppError.Server -> "http_${error.code}"
        is AppError.Storage -> "storage"
        is AppError.Parse -> "storage"
        else -> "unknown"
    }

    private fun now() = System.currentTimeMillis()
}
