package com.qgming.zlib.feature.library

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.LibraryRepository
import com.qgming.zlib.core.data.library.FavoritesRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class LibrarySection(private val titleRes: Int) {
    Local(TextR.string.text_local),
    Saved(TextR.string.text_favorites);

    val title: String get() = appText(titleRes)
}

internal enum class CloudLibrarySource(private val titleRes: Int, private val emptyMessageRes: Int) {
    Saved(TextR.string.text_favorites, TextR.string.text_your_z_library_favorites_appear_here),
    DownloadHistory(TextR.string.text_cloud_download_history, TextR.string.text_your_z_library_download_history_appears_here_including_downloads_on_othe);

    val title: String get() = appText(titleRes)
    val emptyMessage: String get() = appText(emptyMessageRes)
}

internal enum class LibraryRequest { Initial, Refresh, Append }

internal data class RemoteLibraryState(
    val page: BookPage? = null,
    val request: LibraryRequest? = null,
    val error: String? = null,
    val failedRequest: LibraryRequest? = null,
)

internal data class HistoryActionState(
    val book: Book? = null,
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
    private val sessions: SessionManager,
    private val favorites: FavoritesRepository,
) : ViewModel() {
    private val _remote = MutableStateFlow<Map<CloudLibrarySource, RemoteLibraryState>>(emptyMap())
    internal val remote = _remote.asStateFlow()
    private val _historyAction = MutableStateFlow(HistoryActionState())
    internal val historyAction = _historyAction.asStateFlow()
    private val _events = Channel<String>(Channel.BUFFERED)
    internal val events = _events.receiveAsFlow()
    private var deleteRequest: Job? = null
    private var historyNeedsRefresh = false

    private var activeSession = sessions.current()
    private val _sessionGeneration = MutableStateFlow(0L)
    internal val sessionGeneration = _sessionGeneration.asStateFlow()
    private val generation get() = _sessionGeneration.value
    private var shownSource: CloudLibrarySource? = null
    private val requests = mutableMapOf<CloudLibrarySource, Job>()
    private val coverRequests = mutableMapOf<String, Job>()
    private val attemptedCovers = mutableSetOf<String>()
    private val covers = mutableMapOf<String, String>()
    private val coverPermits = Semaphore(3)
    private var favoriteRevision = favorites.changes.value

    init {
        viewModelScope.launch {
            sessions.session.collect {
                if (resetSession()) shownSource?.let { source -> load(source, LibraryRequest.Initial) }
            }
        }
        viewModelScope.launch {
            favorites.changes.collect { revision ->
                if (favoriteRevision != revision) {
                    favoriteRevision = revision
                    val state = _remote.value[CloudLibrarySource.Saved]
                    if (state != null) {
                        requests[CloudLibrarySource.Saved]?.cancel()
                        updateRemote(CloudLibrarySource.Saved, state.copy(request = null))
                        load(CloudLibrarySource.Saved, LibraryRequest.Refresh)
                    }
                }
            }
        }
    }

    internal fun onSourceShown(source: CloudLibrarySource) {
        shownSource = source
        resetSession()
        if (source !in _remote.value) load(source, LibraryRequest.Initial)
    }

    internal fun refresh(source: CloudLibrarySource) {
        if (source == CloudLibrarySource.Saved) favorites.invalidate()
        load(source, LibraryRequest.Refresh)
    }

    internal fun loadMore(source: CloudLibrarySource) = load(source, LibraryRequest.Append)

    internal fun retry(source: CloudLibrarySource) = load(source, _remote.value[source]?.failedRequest ?: LibraryRequest.Refresh)

    private fun resetSession(): Boolean {
        val session = sessions.current()
        if (session == activeSession) return false
        activeSession = session
        _sessionGeneration.value++
        requests.values.forEach { it.cancel() }
        coverRequests.values.forEach { it.cancel() }
        deleteRequest?.cancel()
        _historyAction.value = HistoryActionState()
        historyNeedsRefresh = false
        while (_events.tryReceive().isSuccess) { /* Discard previous-account feedback. */ }
        requests.clear()
        coverRequests.clear()
        attemptedCovers.clear()
        covers.clear()
        _remote.value = emptyMap()
        return true
    }

    private fun load(source: CloudLibrarySource, request: LibraryRequest) {
        resetSession()
        if (source == CloudLibrarySource.DownloadHistory && _historyAction.value.busy) return
        if (source == CloudLibrarySource.DownloadHistory && request == LibraryRequest.Append && historyNeedsRefresh) {
            load(source, LibraryRequest.Refresh)
            return
        }
        val current = _remote.value[source] ?: RemoteLibraryState()
        if (current.request != null) return
        val requestedPage = if (request == LibraryRequest.Append) {
            current.page?.takeIf { it.hasNext }?.let { it.page + 1 } ?: return
        } else {
            1
        }

        val requestGeneration = generation
        val requestSession = activeSession
        updateRemote(source, current.copy(request = request, error = null, failedRequest = null))
        requests[source] = viewModelScope.launch {
            val result = when (source) {
                CloudLibrarySource.Saved -> repository.saved(requestedPage)
                CloudLibrarySource.DownloadHistory -> repository.history(requestedPage)
            }
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            val updated = when (result) {
                is AppResult.Success -> {
                    if (source == CloudLibrarySource.DownloadHistory) historyNeedsRefresh = false
                    // Keep any covers that arrived while the next page was loading.
                    val previousBooks = if (request == LibraryRequest.Append) _remote.value[source]?.page?.books.orEmpty() else emptyList()
                    val page = result.value
                    if (source == CloudLibrarySource.Saved) favorites.rememberSaved(page.books)
                    val merged = (previousBooks + page.books).distinctBy(Book::ref).map(::withCachedCover)
                    val progressed = request != LibraryRequest.Append || (page.page == requestedPage && merged.size > previousBooks.size)
                    RemoteLibraryState(
                        page = page.copy(books = merged, totalPages = if (progressed) page.totalPages else page.page),
                    )
                }

                is AppResult.Failure -> (_remote.value[source] ?: current).copy(request = null, error = result.error.userMessage(), failedRequest = request)
            }
            updateRemote(source, updated)
        }
    }

    internal fun requestHistoryDeletion(book: Book) {
        if (resetSession() || _historyAction.value.busy) return
        val loaded = _remote.value[CloudLibrarySource.DownloadHistory]?.page?.books
            ?.firstOrNull { it.ref == book.ref } ?: return
        _historyAction.value = HistoryActionState(book = loaded, confirming = true, error = null)
    }

    internal fun dismissHistoryActions() {
        if (!_historyAction.value.busy) _historyAction.value = HistoryActionState()
    }

    internal fun confirmHistoryDeletion() {
        if (resetSession()) return
        val current = _historyAction.value
        val book = current.book ?: return
        if (!current.confirming || current.busy) return
        val requestSession = activeSession
        val requestGeneration = generation
        val source = CloudLibrarySource.DownloadHistory
        requests.remove(source)?.cancel()
        _remote.value[source]?.let { updateRemote(source, it.copy(request = null)) }
        _historyAction.value = current.copy(busy = true, error = null)
        deleteRequest = viewModelScope.launch {
            if (sessions.current() != requestSession) return@launch
            val result = repository.deleteHistory(book.id)
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            when (result) {
                is AppResult.Success -> {
                    _historyAction.value = HistoryActionState()
                    historyNeedsRefresh = true
                    _remote.value[source]?.let { previous ->
                        updateRemote(source, previous.copy(
                            page = previous.page?.let { it.copy(books = it.books.filterNot { it.id == book.id }) },
                        ))
                    }
                    _events.send(appText(TextR.string.text_cloud_download_record_deleted))
                    // A deletion shifts server offsets. Reload page one before allowing another append.
                    load(source, LibraryRequest.Refresh)
                }
                is AppResult.Failure -> _historyAction.value = current.copy(
                    busy = false, error = result.error.userMessage(),
                )
            }
        }
    }

    /** Called as cards enter the grid, rather than fetching details for the whole page. */
    internal fun loadCover(book: Book) {
        resetSession()
        if (_remote.value.values.none { state -> state.page?.books?.any { it.ref == book.ref } == true }) return
        if (book.coverUrl.isNotBlank() || !attemptedCovers.add(book.ref)) return
        val requestGeneration = generation
        val requestSession = activeSession
        coverRequests[book.ref] = viewModelScope.launch {
            val result = coverPermits.withPermit { repository.detail(book) }
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            coverRequests.remove(book.ref)
            if (result is AppResult.Success && result.value.ref == book.ref && result.value.coverUrl.isNotBlank()) {
                covers[book.ref] = result.value.coverUrl
                _remote.update { states ->
                    states.mapValues { (_, state) ->
                        state.copy(page = state.page?.let { it.copy(books = it.books.map(::withCachedCover)) })
                    }
                }
            }
        }
    }

    private fun withCachedCover(book: Book): Book =
        if (book.coverUrl.isBlank()) covers[book.ref]?.let { book.copy(coverUrl = it) } ?: book else book

    private fun updateRemote(source: CloudLibrarySource, state: RemoteLibraryState) {
        _remote.update { it + (source to state) }
    }
}
