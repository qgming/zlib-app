package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.Context
import androidx.core.net.toUri
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.database.ZlibDatabase
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.LocalFile
import com.qgming.zlib.core.model.EbookFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
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
    private val metadataReader: LocalFileMetadataReader = NoLocalFileMetadataReader,
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
        CachedEpubMetadataReader(File(context.cacheDir, "local-epub-covers"), { file, cancellation ->
            context.contentResolver.openAssetFileDescriptor(file.uri.toUri(), "r", cancellation)?.createInputStream()
        }),
    )

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val manualRefreshGeneration = AtomicLong()

    fun refresh(userInitiated: Boolean = false) {
        if (userInitiated) {
            manualRefreshGeneration.incrementAndGet()
        }
        refreshes.tryEmit(Unit)
    }

    // Collected only while the Local tab is visible and its lifecycle is STARTED.
    fun observeFiles(): Flow<LocalFilesState> = settings.settings.map { it.downloadTreeUri }.distinctUntilChanged()
        .flatMapLatest { tree ->
            if (tree == null) flowOf(LocalFilesState()) else observeTree(tree)
        }.flowOn(dispatchers.io)

    private fun observeTree(tree: String): Flow<LocalFilesState> = channelFlow {
        send(LocalFilesState(treeUri = tree, loading = true))
        val triggers = Channel<Unit>(Channel.CONFLATED)
        var latestBooks = knownBooks.first()
        var latestPending = publications.first()
        coroutineScope {
            val booksJob = launch {
                knownBooks.drop(1).collect { books ->
                    latestBooks = books
                    triggers.trySend(Unit)
                }
            }
            val pendingJob = launch {
                publications.drop(1).collect { pending ->
                    latestPending = pending
                    triggers.trySend(Unit)
                }
            }
            val changeJob = launch {
                merge(
                    refreshes,
                    store.changes(tree).debounce(180),
                ).collect {
                    triggers.trySend(Unit)
                }
            }
            try {
                // Initial snapshots are primed before starting the loop so in-flight SAF scans
                // finish cleanly instead of being cancelled by subsequent bursts.
                var interval = 3_000L
                var lastState = LocalFilesState(treeUri = tree, loading = true)
                var handledManualRefresh = manualRefreshGeneration.get()
                var enrichment: kotlinx.coroutines.Job? = null
                while (true) {
                    val requestedGeneration = manualRefreshGeneration.get()
                    val showRefreshing = requestedGeneration != handledManualRefresh
                    if (showRefreshing) {
                        enrichment?.cancelAndJoin()
                        if (!lastState.loading) send(lastState.copy(refreshing = true))
                        metadataReader.invalidate(tree)
                        handledManualRefresh = requestedGeneration
                    }
                    val byUri = latestBooks.mapNotNull { book -> book.localUri?.let { it to book } }.toMap()
                    val previous = if (showRefreshing) emptyMap() else lastState.files.associateBy(::LocalFileVersion)
                    val scanned = scan(tree, byUri, latestPending, previous)
                    val changed = scanned != lastState || showRefreshing
                    if (changed) {
                        enrichment?.cancelAndJoin()
                        lastState = scanned
                        send(scanned)
                    }
                    // Existence is displayed immediately; parsing never holds back the first list.
                    // A quiet polling pass must not repeatedly cancel a slow provider's read.
                    if (scanned.error == null && (changed || enrichment?.isActive != true)) {
                        enrichment = launch {
                            enrich(scanned) { enriched ->
                                lastState = enriched
                                send(enriched)
                            }
                        }
                    }
                    // Drain coalesced trigger or wait up to the polling interval.
                    val triggered = select {
                        triggers.onReceive { true }
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

    private suspend fun scan(
        tree: String,
        known: Map<String, Book>,
        pending: List<PendingDownloadPublication>,
        previous: Map<LocalFileVersion, LocalFile>,
    ): LocalFilesState =
        try {
            val files = store.scan(tree)
            val entries = (pending + publications.first()).distinct().filter { it.treeUri == tree }
            val visible = files.filterNot { isPending(it, entries) }
            metadataReader.retain(tree, visible)
            LocalFilesState(tree, visible.map {
                it.copy(book = known[it.uri], metadata = metadataReader.cached(it) ?: previous[LocalFileVersion(it)]?.metadata)
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalFilesState(treeUri = tree, error = AppError.Storage(error))
        }

    private suspend fun enrich(state: LocalFilesState, emit: suspend (LocalFilesState) -> Unit) {
        if (metadataReader === NoLocalFileMetadataReader) return
        val files = state.files.toMutableList()
        var changed = false
        var lastEmission = 0L
        for ((index, file) in state.files.withIndex()) {
            if (!file.extension.equals("epub", ignoreCase = true) || file.metadata != null || metadataReader.cached(file) != null) continue
            val metadata = try {
                metadataReader.read(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            // Metadata is optional; an unreadable or changing document must never hide other files.
            val current = try {
                store.find(file)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (settings.read().downloadTreeUri != file.treeUri) return
            if (current == null || LocalFileVersion(current) != LocalFileVersion(file) ||
                isPending(current, publications.first().filter { it.treeUri == file.treeUri })
            ) {
                refresh()
                continue
            }
            if (file.metadata != metadata) {
                files[index] = file.copy(metadata = metadata)
                changed = true
            }
            val now = System.nanoTime()
            if (changed && now - lastEmission >= 100_000_000L) {
                emit(state.copy(files = files.toList()))
                changed = false
                lastEmission = now
            }
        }
        if (changed) emit(state.copy(files = files.toList()))
    }

    /** Completed tasks use the exact persisted URI, then the same SAF checks as the Local tab. */
    suspend fun readableDownloadedFile(book: Book, uri: String): AppResult<LocalFile> = withContext(dispatchers.io) {
        try {
            val tree = settings.read().downloadTreeUri
                ?: return@withContext AppResult.Failure(AppError.Storage(IOException(appText(TextR.string.text_the_download_folder_changed_select_the_file_again))))
            val name = book.title.ifBlank { "book-${book.id}" } + "." + book.extension
            readable(LocalFile(tree, uri, name, EbookFiles.extension(name) ?: book.extension, book = book))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppResult.Failure(AppError.Storage(error))
        }
    }

    suspend fun readable(file: LocalFile): AppResult<LocalFile> = withContext(dispatchers.io) {
        try {
            val current = resolve(file) ?: run {
                refresh()
                throw FileNotFoundException(appText(TextR.string.text_local_file_not_found_may_have_been_deleted))
            }
            if (!store.isReadable(current)) throw IOException(appText(TextR.string.text_cannot_read_the_local_file_grant_folder_access_again_in_settings))
            if (settings.read().downloadTreeUri != current.treeUri) throw IOException(appText(TextR.string.text_the_download_folder_changed_select_the_file_again))
            if (isPending(current, publications.first().filter { it.treeUri == current.treeUri })) {
                throw FileNotFoundException(appText(TextR.string.text_local_file_not_found_may_have_been_deleted))
            }
            AppResult.Success(current.copy(book = file.book, metadata = metadataReader.cached(current) ?: file.metadata))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: FileNotFoundException) {
            refresh()
            AppResult.Failure(AppError.Storage(FileNotFoundException(appText(TextR.string.text_local_file_not_found_may_have_been_deleted))))
        } catch (_: SecurityException) {
            refresh()
            AppResult.Failure(AppError.Storage(IOException(appText(TextR.string.text_cannot_read_the_local_file_grant_folder_access_again_in_settings))))
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
        // The completed-task caller knows the exact URI, but its display name may be synthetic.
        // Name-based publication checks must use the actual provider name returned below.
        if (pending.any { it.targetUri == file.uri }) throw IOException(appText(TextR.string.text_the_file_no_longer_exists_refresh_local_files))
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
