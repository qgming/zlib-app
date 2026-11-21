package com.qgming.zlib.core.data.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.toBook
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.model.Book
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: ZlibDatabase,
    private val dispatchers: AppDispatchers,
) {
    private val books = database.bookDao()

    val downloaded: Flow<List<Book>> = books.observeDownloaded()
        .map { entities -> entities.map { it.toBook() } }

    suspend fun deleteLocal(book: Book): AppResult<Unit> = withContext(dispatchers.io) {
        val uri = book.localUri?.let(Uri::parse)
            ?: return@withContext AppResult.Failure(AppError.Parse("没有本地文件"))
        try {
            DocumentFile.fromSingleUri(context, uri)?.delete()
            books.clearLocalFile(book.id, System.currentTimeMillis())
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage(t))
        }
    }
}
