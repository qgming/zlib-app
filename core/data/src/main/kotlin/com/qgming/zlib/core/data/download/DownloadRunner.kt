package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.database.ZlibDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Singleton
class DownloadRunner @Inject constructor(
    private val database: ZlibDatabase,
    private val engine: DownloadEngine,
    private val notifications: DownloadNotifications,
    private val dispatchers: AppDispatchers,
) {
    suspend fun runQueue() = withContext(dispatchers.io) {
        val dao = database.downloadDao()
        val books = database.bookDao()
        dao.resetInterrupted()

        while (true) {
            val item = dao.nextActive() ?: break
            dao.update(item.id, DownloadStates.RESOLVING, item.bytesWritten, item.totalBytes, null, now())
            notifications.update(dao.byId(item.id) ?: item)

            var attempt = 1
            var lastBytes = item.bytesWritten
            var lastTotal = item.totalBytes
            while (true) {
                val current = dao.byId(item.id) ?: break
                val result = try {
                    engine.download(current) { written, total ->
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
                        notifications.completed(current, lastBytes, lastTotal)
                        break
                    }

                    is AppResult.Failure -> {
                        val error = result.error
                        if (isRetryable(error) && attempt < 3) {
                            dao.updateState(item.id, DownloadStates.QUEUED, null, now())
                            delay(1_000L shl (attempt - 1))
                            attempt++
                            continue
                        }
                        dao.update(item.id, DownloadStates.FAILED, lastBytes, lastTotal, errorCode(error), now())
                        notifications.failed(current, errorCode(error))
                        break
                    }
                }
            }
        }
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
