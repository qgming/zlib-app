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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

data class LocalFilesState(
    val treeUri: String? = null,
    val files: List<LocalFile> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
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

    private val refreshes = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    fun refresh(userInitiated: Boolean = false) { refreshes.tryEmit(userInitiated) }

    // Collected only while the Local tab is visible and its lifecycle is STARTED.
    fun observeFiles(): Flow<LocalFilesState> = settings.settings.map { it.downloadTreeUri }.distinctUntilChanged()
        .flatMapLatest { tree ->
            if (tree == null) flowOf(LocalFilesState()) else observeTree(tree)
        }.flowOn(dispatchers.io)

    private fun observeTree(tree: String): Flow<LocalFilesState> = channelFlow {
        send(LocalFilesState(treeUri = tree, loading = true))
        val triggers = Channel<Boolean>(Channel.CONFLATED)
        var latestBooks = knownBooks.first()
        var latestPending = publications.first()
        coroutineScope {
            val booksJob = launch {
                knownBooks.drop(1).collect { books ->
                    latestBooks = books
                    triggers.trySend(false)
                }
            }
            val pendingJob = launch {
                publications.drop(1).collect { pending ->
                    latestPending = pending
                    triggers.trySend(false)
                }
            }
            val changeJob = launch {
                merge(
                    refreshes,
                    store.changes(tree).debounce(180).map { false },
                ).collect { userInitiated ->
                    triggers.trySend(userInitiated)
                }
            }
            try {
                // Initial snapshots are primed before starting the loop so in-flight SAF scans
                // finish cleanly instead of being cancelled by subsequent bursts.
                var interval = 3_000L
                var lastState = LocalFilesState(treeUri = tree, loading = true)
                var showRefreshing = false
                while (true) {
                    if (showRefreshing && !lastState.loading) {
                        send(lastState.copy(refreshing = true))
                    }
                    val byUri = latestBooks.mapNotNull { book -> book.localUri?.let { it to book } }.toMap()
                    val scanned = scan(tree, byUri, latestPending)
                    if (scanned != lastState || showRefreshing) {
                        lastState = scanned
                        send(scanned)
                    }
                    showRefreshing = false
                    // Drain coalesced trigger or wait up to the polling interval.
                    val triggered = select {
                        triggers.onReceive { userInitiated ->
                            showRefreshing = userInitiated
                            true
                        }
                        onTimeout(interval) { false }
                    }
                    interval = if (triggered) 3_000L else (interval * 2).coerceAtMost(15_000L)
                }
            } finally {
                booksJob.cancel()
                pendingJob.cancel()
                changeJob.cancel()
            }
        }
    }.distinctUntilChanged()

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
