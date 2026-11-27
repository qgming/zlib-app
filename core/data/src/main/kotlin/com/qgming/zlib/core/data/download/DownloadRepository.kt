package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.room.withTransaction
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.FileNames
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.LocalFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object DownloadServiceActions {
    const val START = "com.qgming.zlib.download.START"
    const val PAUSE = "com.qgming.zlib.download.PAUSE"
    const val CANCEL = "com.qgming.zlib.download.CANCEL"
    const val REMOVE = "com.qgming.zlib.download.REMOVE"
    const val EXTRA_ID = "download_id"
}

/** A downloaded file for the exact `id:hash` that is still readable under the current tree. */
data class ExistingDownload(val book: Book, val uri: String)

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ZlibDatabase,
    private val directoryStore: DownloadDirectoryStore,
    private val runner: DownloadRunner,
    private val dispatchers: AppDispatchers,
    private val journal: DownloadJournal,
    private val localFiles: LocalFilesRepository,
    private val settings: SettingsStore,
) {
    private val downloads = database.downloadDao()
    private val books = database.bookDao()

    val queue: Flow<List<DownloadItem>> = downloads.observeAll()
        .map { items -> items.map { it.toItem() } }

    /** Default enqueue never silently duplicates a readable file already in the download tree. */
    suspend fun enqueue(book: Book): AppResult<Unit> = enqueue(book, forceRedownload = false)

    /**
     * @param forceRedownload explicit user choice to download again. The task's own `.part` is
     * discarded, but an existing readable file is never overwritten or deleted.
     */
    suspend fun enqueue(book: Book, forceRedownload: Boolean): AppResult<Unit> = withContext(dispatchers.io) {
        val tree = directoryStore.ensureLoaded()?.toString()
        if (tree == null) {
            return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_choose_a_download_folder_in_settings_first)))
        }
        val existingRecord = downloads.byId(book.ref)
        if (existingRecord != null && existingRecord.state in DownloadStates.active) {
            return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_this_book_is_already_in_the_download_queue)))
        }
        if (!forceRedownload && findExistingDownloadLocked(book) != null) {
            return@withContext AppResult.Failure(
                AppError.Parse(appText(TextR.string.text_this_file_already_exists_open_it_or_confirm_another_download)),
            )
        }
        if (existingRecord != null && journal.pending(tree, existingRecord.id, existingRecord.createdAt) != null) {
            return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_this_book_is_already_in_the_download_queue)))
        }
        if (forceRedownload) {
            journal.setContinuation(tree, book.ref, null)
            directoryStore.deletePart(book.ref)
        }

        val now = System.currentTimeMillis()
        val fileName = FileNames.withExtension(
            FileNames.sanitize(book.title, fallback = "book-${book.id}"),
            book.extension,
        )
        database.withTransaction {
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
            // A different `id:hash` is a different file. Keeping the old format's URI on the new
            // hash would make the local-file association (and existence check) lie.
            val sameReference = stored != null && stored.hash == book.hash
            val entity = book.toEntity(
                now = now,
                localUri = if (sameReference) stored.localUri else null,
            )
            books.upsert(
                if (stored == null) {
                    entity
                } else {
                    entity.copy(
                        favorite = if (sameReference) stored.favorite else false,
                        downloadedAt = if (sameReference) stored.downloadedAt else null,
                        createdAt = stored.createdAt,
                        updatedAt = now,
                    )
                },
            )
        }
        startQueueService()
    }

    /**
     * Returns the already-downloaded file only for the exact reference (`id:hash`), and only while
     * its URI is inside the current tree and readable. Titles or numeric ids alone are never enough.
     */
    suspend fun findExistingDownload(book: Book): ExistingDownload? = withContext(dispatchers.io) {
        directoryStore.ensureLoaded()
        findExistingDownloadLocked(book)
    }

    /** Opens only the current completed task's exact local file, using the Local tab's SAF checks. */
    suspend fun completedFile(id: String): AppResult<LocalFile> = withContext(dispatchers.io) {
        resolveCompletedDownload(
            id = id,
            taskLookup = downloads::byId,
            bookLookup = { bookId -> books.byId(bookId)?.toBook() },
            currentTree = { settings.read().downloadTreeUri },
            readable = localFiles::readableDownloadedFile,
        )
    }

    private suspend fun findExistingDownloadLocked(book: Book): ExistingDownload? {
        val treeUri = directoryStore.currentUri() ?: return null
        val stored = books.byId(book.id) ?: return null
        if (stored.hash != book.hash) return null
        val raw = stored.localUri?.takeIf { it.isNotBlank() } ?: return null
        val uri = runCatching { raw.toUri() }.getOrNull() ?: return null
        if (!isInsideTree(uri, treeUri)) return null
        val readable = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
        }.getOrDefault(false)
        return if (readable && directoryStore.currentUri() == treeUri) ExistingDownload(stored.toBook(), raw) else null
    }

    private fun isInsideTree(uri: Uri, tree: Uri): Boolean {
        if (uri.scheme != "content" || uri.authority != tree.authority) return false
        val prefix = tree.toString().trimEnd('/')
        return uri.toString().startsWith("$prefix/")
    }

    suspend fun pause(id: String) = withContext(dispatchers.io) {
        sendAction(DownloadServiceActions.PAUSE, id)
    }

    suspend fun resume(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        directoryStore.ensureLoaded()
        if (directoryStore.currentUri() == null) {
            return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_choose_a_download_folder_in_settings_first)))
        }
        val item = downloads.byId(id)
            ?: return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_download_record_not_found)))
        if (item.state == DownloadStates.COMPLETED) return@withContext AppResult.Success(Unit)
        if (item.state !in DownloadStates.active) {
            downloads.updateState(id, DownloadStates.QUEUED, null, System.currentTimeMillis())
        }
        startQueueService()
    }

    suspend fun cancel(id: String) = withContext(dispatchers.io) {
        sendAction(DownloadServiceActions.CANCEL, id)
    }

    suspend fun retry(id: String): AppResult<Unit> = withContext(dispatchers.io) {
        directoryStore.ensureLoaded()
        if (directoryStore.currentUri() == null) {
            return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_choose_a_download_folder_in_settings_first)))
        }
        val item = downloads.byId(id)
            ?: return@withContext AppResult.Failure(AppError.Parse(appText(TextR.string.text_download_record_not_found)))
        if (item.state == DownloadStates.COMPLETED) return@withContext AppResult.Success(Unit)
        if (item.state in DownloadStates.active) return@withContext startQueueService()
        if (item.error == DownloadCodes.RANGE || item.error == DownloadCodes.INVALID) {
            directoryStore.currentUri()?.toString()?.let { journal.setContinuation(it, item.id, null) }
            directoryStore.deletePart(item.id)
            downloads.resetProgress(id)
        }
        downloads.updateState(id, DownloadStates.QUEUED, null, System.currentTimeMillis())
        startQueueService()
    }

    suspend fun remove(id: String) = withContext(dispatchers.io) {
        // The Service joins its writer before deleting the record or .part file.
        sendAction(DownloadServiceActions.REMOVE, id)
    }

    internal suspend fun finishControl(action: String, id: String) = withContext(dispatchers.io) {
        val item = downloads.byId(id) ?: return@withContext
        when (action) {
            DownloadServiceActions.PAUSE -> if (item.state != DownloadStates.COMPLETED) {
                downloads.updateState(id, DownloadStates.PAUSED, null, System.currentTimeMillis())
            }
            DownloadServiceActions.CANCEL -> if (item.state != DownloadStates.COMPLETED) {
                downloads.updateState(id, DownloadStates.CANCELLED, null, System.currentTimeMillis())
            }
            DownloadServiceActions.REMOVE -> {
                if (item.state != DownloadStates.COMPLETED) {
                    val tree = directoryStore.ensureLoaded()?.toString()
                    if (tree != null) try {
                        if (runner.discardPublication(item, tree)) directoryStore.deletePart(item.id)
                        journal.setContinuation(tree, item.id, null)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SafeLog.w("Download", "temporary file cleanup unavailable")
                    }
                }
                downloads.delete(id)
            }
        }
    }

    /**
     * Pausing and stopping the old writer is coordinated by [DownloadRunner]; validation lives in
     * [DownloadDirectoryStore]. On failure the previous directory and tasks stay paused.
     */
    suspend fun setDownloadDirectory(uri: Uri): AppResult<DownloadDirectoryChange> =
        withContext(dispatchers.io) { runner.withDirectoryChange(uri) }

    suspend fun hasDownloadDirectory(): Boolean = withContext(dispatchers.io) {
        directoryStore.ensureLoaded() != null
    }

    /** True only while the saved tree still has a persisted read/write grant and usable flags. */
    suspend fun downloadDirectoryAccessible(): Boolean = withContext(dispatchers.io) {
        directoryStore.currentAccessible()
    }

    suspend fun downloadDirectoryName(): String? = withContext(dispatchers.io) {
        if (directoryStore.ensureLoaded() == null) return@withContext null
        directoryStore.currentName()?.takeIf(String::isNotBlank) ?: appText(TextR.string.text_folder_selected)
    }

    suspend fun resumePendingIfAny() = withContext(dispatchers.io) {
        if (downloads.nextActive() != null) startQueueService()
        Unit
    }

    suspend fun reconcilePublishedIfAny() = runner.reconcilePublishedFiles()

    private suspend fun startQueueService(): AppResult<Unit> {
        val intent = Intent(context, DownloadService::class.java).setAction(DownloadServiceActions.START)
        try {
            ContextCompat.startForegroundService(context, intent)
            return AppResult.Success(Unit)
        } catch (_: IllegalStateException) {
            // A running worker can still accept this item; otherwise keep it paused for retry.
        } catch (_: SecurityException) {
        }
        if (!runner.pauseAfterLaunchRejection()) return AppResult.Success(Unit)
        return AppResult.Failure(AppError.Parse(appText(TextR.string.text_download_foreground_unavailable)))
    }

    private suspend fun sendAction(action: String, id: String) {
        val intent = Intent(context, DownloadService::class.java)
            .setAction(action)
            .putExtra(DownloadServiceActions.EXTRA_ID, id)
        try {
            context.startService(intent)
            return
        } catch (_: IllegalStateException) {
        } catch (_: SecurityException) {
        }
        runner.controlWithoutService(id) { finishControl(action, id) }
    }

    companion object {
        val IO = Dispatchers.IO
    }
}
