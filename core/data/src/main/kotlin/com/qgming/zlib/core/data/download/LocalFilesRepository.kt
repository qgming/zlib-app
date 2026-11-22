package com.qgming.zlib.core.data.download

import android.content.Context
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.LocalFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

data class LocalFilesState(
    val treeUri: String? = null,
    val files: List<LocalFile> = emptyList(),
    val loading: Boolean = false,
    val error: AppError? = null,
)

/** The selected directory is authoritative; Room only enriches exact URI matches. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LocalFilesRepository internal constructor(
    private val store: LocalFileStore,
    private val settings: SettingsStore,
    private val knownBooks: Flow<List<Book>>,
    private val clearFileReference: suspend (String) -> Unit,
    private val dispatchers: AppDispatchers,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: ZlibDatabase,
        settings: SettingsStore,
        dispatchers: AppDispatchers,
    ) : this(
        SafLocalFileStore(context), settings,
        database.bookDao().observeDownloaded().map { rows -> rows.map { it.toBook() } },
        { uri -> database.bookDao().clearLocalFileByUri(uri, System.currentTimeMillis()) },
        dispatchers,
    )

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun refresh() { refreshes.tryEmit(Unit) }

    // Collected only while the Local tab is visible and its lifecycle is STARTED.
    fun observeFiles(): Flow<LocalFilesState> = settings.settings.map { it.downloadTreeUri }.distinctUntilChanged()
        .flatMapLatest { tree ->
            if (tree == null) flowOf(LocalFilesState()) else {
                val ticks = flow {
                    emit(Unit)
                    while (true) { delay(3_000); emit(Unit) }
                }
                combine(knownBooks, merge(ticks, refreshes, store.changes(tree))) { books, _ -> books }
                    .conflate().map { books ->
                        try {
                            val byUri = books.filter { it.localUri != null }.associateBy { it.localUri }
                            LocalFilesState(tree, store.scan(tree).map { it.copy(book = byUri[it.uri]) })
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            LocalFilesState(treeUri = tree, error = AppError.Storage(error))
                        }
                    }.onStart { emit(LocalFilesState(treeUri = tree, loading = true)) }
            }
        }.flowOn(dispatchers.io)

    suspend fun readable(file: LocalFile): AppResult<LocalFile> = withContext(dispatchers.io) {
        try {
            val current = resolve(file) ?: run {
                refresh()
                throw IOException("文件已不存在，请刷新本机列表")
            }
            if (!store.isReadable(current)) throw IOException("无法读取本机文件，请在设置中重新授权下载目录")
            AppResult.Success(current.copy(book = file.book))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            AppResult.Failure(AppError.Storage(error))
        }
    }

    suspend fun deleteLocal(file: LocalFile): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            val current = resolve(file)
            if (current != null && !store.delete(current)) throw IOException("无法删除本机文件，请检查目录权限")
            if (current != null && store.scan(file.treeUri).any { it.uri == file.uri }) {
                throw IOException("文件仍存在，请重试删除")
            }
            // Only after the actual document is gone. A missing file is already the desired state.
            clearFileReference(file.uri)
            refresh()
            AppResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            refresh()
            AppResult.Failure(AppError.Storage(error))
        }
    }

    private suspend fun resolve(file: LocalFile): LocalFile? {
        val tree = settings.read().downloadTreeUri
        if (tree == null || tree != file.treeUri) throw IOException("下载目录已改变，请重新选择文件")
        val current = store.scan(tree).firstOrNull { it.uri == file.uri }
        if (settings.read().downloadTreeUri != tree) throw IOException("下载目录已改变，请重新选择文件")
        return current
    }
}
