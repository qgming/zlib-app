package com.qgming.zlib.core.data.download

import android.content.Context
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.model.Book
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Device files are independent of the account's cloud library. */
@Singleton
class LocalFilesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    database: ZlibDatabase,
    private val dispatchers: AppDispatchers,
) {
    private val books = database.bookDao()

    val downloaded: Flow<List<Book>> = books.observeDownloaded()
        .map { entities -> entities.map { it.toBook() } }

    suspend fun shareUri(book: Book): AppResult<String> = withContext(dispatchers.io) {
        try {
            val uri = book.localUri ?: throw IOException("没有本机文件")
            val file = DocumentFile.fromSingleUri(context, uri.toUri())
            if (file == null || !file.isFile || !file.canRead()) throw IOException("无法读取本机文件，请检查目录权限")
            AppResult.Success(uri)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppResult.Failure(AppError.Storage(error))
        }
    }

    suspend fun deleteLocal(book: Book): AppResult<Unit> = withContext(dispatchers.io) {
        val uri = book.localUri?.toUri()
            ?: return@withContext AppResult.Failure(AppError.Parse("没有本机文件"))
        try {
            val file = DocumentFile.fromSingleUri(context, uri)
                ?: throw IOException("无法访问本机文件")
            if (!file.delete()) throw IOException("无法删除本机文件，请检查目录权限")
            books.clearLocalFile(book.id, System.currentTimeMillis())
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppResult.Failure(AppError.Storage(error))
        }
    }
}
