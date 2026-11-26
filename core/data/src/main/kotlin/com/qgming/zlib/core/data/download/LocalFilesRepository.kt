package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LocalFilesRepository internal constructor(
    private val store: LocalFileStore,
    private val settings: SettingsStore,
    private val knownBooks: Flow<List<Book>>,
    private val clearFileReference: suspend (String) -> Unit,
    private val dispatchers: AppDispatchers,
    private val publications: Flow<List<PendingDownloadPublication>> = flowOf(emptyList()),
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        database: ZlibDatabase,
        settings: SettingsStore,
        dispatchers: AppDispatchers,
        journal: DownloadJournal,
    ) : this(
        SafLocalFileStore(context), settings,
        database.bookDao().observeDownloaded().map { rows -> rows.map { it.toBook() } },
        { uri -> database.bookDao().clearLocalFileByUri(uri, System.currentTimeMillis()) },
        dispatchers,
        journal.publications,
    )

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun refresh() { refreshes.tryEmit(Unit) }

    // Collected only while the Local tab is visible and its lifecycle is STARTED.
    fun observeFiles(): Flow<LocalFilesState> = settings.settings.map { it.downloadTreeUri }.distinctUntilChanged()
        .flatMapLatest { tree ->
            if (tree == null) flowOf(LocalFilesState()) else {
                val invalidations = merge(refreshes, store.changes(tree).debounce(180)).onStart { emit(Unit) }
                combine(knownBooks, publications, invalidations) { books, pending, _ -> books to pending }
                    .flatMapLatest { (books, pending) ->
                        flow {
                            val byUri = books.mapNotNull { book -> book.localUri?.let { it to book } }.toMap()
                            var interval = 3_000L
                            while (true) {
                                emit(scan(tree, byUri, pending))
                                delay(interval)
                                interval = (interval * 2).coerceAtMost(15_000L)
                            }
                        }
                    }.distinctUntilChanged().onStart { emit(LocalFilesState(treeUri = tree, loading = true)) }
            }
        }.flowOn(dispatchers.io)

    private suspend fun scan(tree: String, known: Map<String, Book>, pending: List<PendingDownloadPublication>): LocalFilesState =
        try {
            val files = store.scan(tree)
            val entries = (pending + publications.first()).distinct().filter { it.treeUri == tree }
            val visible = files.filterNot { isPending(it, entries) }
            LocalFilesState(tree, visible.map { it.copy(book = known[it.uri]) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalFilesState(treeUri = tree, error = AppError.Storage(error))
        }

    suspend fun readable(file: LocalFile): AppResult<LocalFile> = withContext(dispatchers.io) {
        try {
            val current = resolve(file) ?: run {
                refresh()
                throw IOException(appText(TextR.string.text_the_file_no_longer_exists_refresh_local_files))
            }
            if (!store.isReadable(current)) throw IOException(appText(TextR.string.text_cannot_read_the_local_file_grant_folder_access_again_in_settings))
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
            if (current != null && !store.delete(current)) throw IOException(appText(TextR.string.text_cannot_delete_the_local_file_check_folder_permissions))
            if (current != null && resolve(current) != null) {
                throw IOException(appText(TextR.string.text_the_file_still_exists_try_deleting_it_again))
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
        if (tree == null || tree != file.treeUri) throw IOException(appText(TextR.string.text_the_download_folder_changed_select_the_file_again))
        val pending = publications.first().filter { it.treeUri == tree }
        if (isPending(file, pending)) throw IOException(appText(TextR.string.text_the_file_no_longer_exists_refresh_local_files))
        val current = store.find(file)
        if (settings.read().downloadTreeUri != tree) throw IOException(appText(TextR.string.text_the_download_folder_changed_select_the_file_again))
        if (current != null && isPending(current, publications.first().filter { it.treeUri == tree })) {
            throw IOException(appText(TextR.string.text_the_file_no_longer_exists_refresh_local_files))
        }
        return current
    }

    private suspend fun isPending(file: LocalFile, pending: List<PendingDownloadPublication>): Boolean =
        pending.any { it.targetUri == file.uri } ||
            (pending.any { it.targetUri == null && it.targetName == file.name } && store.isDirectChild(file))
}
