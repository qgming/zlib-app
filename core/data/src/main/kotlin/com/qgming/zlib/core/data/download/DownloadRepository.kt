package com.qgming.zlib.core.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.FileNames
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.DownloadItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object DownloadServiceActions {
    const val START = "com.qgming.zlib.download.START"
    const val PAUSE = "com.qgming.zlib.download.PAUSE"
    const val CANCEL = "com.qgming.zlib.download.CANCEL"
    const val EXTRA_ID = "download_id"
}

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: ZlibDatabase,
    private val directoryStore: DownloadDirectoryStore,
    private val dispatchers: AppDispatchers,
) {
    private val downloads = database.downloadDao()
    private val books = database.bookDao()

    val queue: Flow<List<DownloadItem>> = downloads.observeAll()
        .map { items -> items.map { it.toItem() } }

    suspend fun enqueue(book: Book): AppResult<Unit> = withContext(dispatchers.io) {
        directoryStore.ensureLoaded()
        if (directoryStore.currentUri() == null) {
            return@withContext AppResult.Failure(AppError.Parse("请先在设置中选择下载目录"))
        }
        val existing = downloads.byId(book.ref)
        if (existing != null && existing.state in DownloadStates.active) {
            return@withContext AppResult.Failure(AppError.Parse("这本书已在下载队列中"))
        }

        val now = System.currentTimeMillis()
        val fileName = FileNames.withExtension(
            FileNames.sanitize(book.title, fallback = "book-${book.id}"),
            book.extension,
        )
        downloads.upsert(
            DownloadEntity(
                id = book.ref,
                bookId = book.id,
                hash = book.hash,
                title = book.title,
                authors = book.authors,
                extension = book.extension,
                coverUrl = book.coverUrl,
                fileName = fileName,
                state = DownloadStates.QUEUED,
                bytesWritten = 0,
                totalBytes = book.sizeBytes,
                error = null,
                createdAt = now,
                updatedAt = now,
            ),
        )
        val stored = books.byId(book.id)
        books.upsert(book.toEntity(now = stored?.createdAt ?: now, localUri = stored?.localUri))
        startService(DownloadServiceActions.START)
        AppResult.Success(Unit)
    }

    suspend fun pause(id: String) = withContext(dispatchers.io) {
        downloads.updateState(id, DownloadStates.PAUSED, null, System.currentTimeMillis())
        sendAction(DownloadServiceActions.PAUSE, id)
    }

    suspend fun resume(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        directoryStore.ensureLoaded()
        if (directoryStore.currentUri() == null) {
            return@withContext AppResult.Failure(AppError.Parse("请先在设置中选择下载目录"))
        }
        downloads.updateState(id, DownloadStates.QUEUED, null, System.currentTimeMillis())
        startService(DownloadServiceActions.START)
        AppResult.Success(Unit)
    }

    suspend fun cancel(id: String) = withContext(dispatchers.io) {
        downloads.updateState(id, DownloadStates.CANCELLED, null, System.currentTimeMillis())
        sendAction(DownloadServiceActions.CANCEL, id)
    }

    suspend fun retry(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        directoryStore.ensureLoaded()
        if (directoryStore.currentUri() == null) {
            return@withContext AppResult.Failure(AppError.Parse("请先在设置中选择下载目录"))
        }
        downloads.resetProgress(id)
        downloads.updateState(id, DownloadStates.QUEUED, null, System.currentTimeMillis())
        startService(DownloadServiceActions.START)
        AppResult.Success(Unit)
    }

    suspend fun remove(id: String) = withContext(dispatchers.io) {
        downloads.byId(id)?.let { directoryStore.deletePart(it.fileName) }
        downloads.delete(id)
    }

    suspend fun setDownloadDirectory(uri: android.net.Uri): AppResult<Unit> =
        directoryStore.setTree(uri)

    suspend fun downloadDirectoryName(): String? = withContext(dispatchers.io) {
        if (directoryStore.ensureLoaded() == null) return@withContext null
        runCatching { directoryStore.tree()?.name }.getOrNull()?.takeIf(String::isNotBlank) ?: "已选择文件夹"
    }

    suspend fun resumePendingIfAny() = withContext(dispatchers.io) {
        if (downloads.nextActive() != null) startService(DownloadServiceActions.START)
    }

    private fun startService(action: String) {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun sendAction(action: String, id: String) {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(action)
            .putExtra(DownloadServiceActions.EXTRA_ID, id)
        context.startService(intent)
    }

    companion object {
        val IO = Dispatchers.IO
    }
}
