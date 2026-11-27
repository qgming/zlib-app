package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.LocalFile
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** Resolves a completed task by its exact persisted association, rechecking after SAF access. */
internal suspend fun resolveCompletedDownload(
    id: String,
    taskLookup: suspend (String) -> DownloadEntity?,
    bookLookup: suspend (String) -> Book?,
    currentTree: suspend () -> String?,
    readable: suspend (Book, String) -> AppResult<LocalFile>,
): AppResult<LocalFile> = try {
    val task = taskLookup(id)
    when {
        task == null || task.id != id || task.state != DownloadStates.COMPLETED ||
            task.bookId.isBlank() || task.hash.isBlank() || id != Book.refOf(task.bookId, task.hash) -> missingCompletedFile()
        else -> {
            val book = bookLookup(task.bookId)
            val uri = book?.localUri?.takeIf(String::isNotBlank)
            if (book == null || book.id != task.bookId || book.hash != task.hash || uri == null) {
                missingCompletedFile()
            } else {
                val tree = currentTree()
                if (tree == null) {
                    changedCompletedDirectory()
                } else when (val result = readable(book, uri)) {
                    is AppResult.Failure -> result
                    is AppResult.Success -> {
                        val latestTask = taskLookup(id)
                        val latestBook = bookLookup(task.bookId)
                        when {
                            currentTree() != tree || result.value.treeUri != tree -> changedCompletedDirectory()
                            latestTask == null || latestTask.state != DownloadStates.COMPLETED ||
                                latestTask.id != task.id || latestTask.bookId != task.bookId ||
                                latestTask.hash != task.hash || latestTask.createdAt != task.createdAt ||
                                latestBook == null || latestBook.ref != book.ref ||
                                latestBook.localUri != uri || result.value.uri != uri -> missingCompletedFile()
                            else -> result
                        }
                    }
                }
            }
        }
    }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    AppResult.Failure(AppError.Storage(error))
}

private fun missingCompletedFile(): AppResult.Failure = AppResult.Failure(
    AppError.Storage(IOException(appText(TextR.string.text_local_file_not_found_may_have_been_deleted))),
)

private fun changedCompletedDirectory(): AppResult.Failure = AppResult.Failure(
    AppError.Storage(IOException(appText(TextR.string.text_the_download_folder_changed_select_the_file_again))),
)
