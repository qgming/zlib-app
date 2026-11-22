package com.qgming.zlib.feature.library

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class LibrarySection(val title: String) {
    Saved("收藏"),
    Booklists("书单"),
}

internal enum class CloudLibrarySource(val title: String, val emptyMessage: String) {
    Saved("收藏", "这里显示 Z-Library 账号中的收藏。"),
    DownloadHistory("云端下载记录", "这里显示 Z-Library 账号的下载记录，包括其他设备的下载。"),
}

internal enum class LibraryRequest { Initial, Refresh, Append }

internal data class RemoteLibraryState(
    val page: BookPage? = null,
    val request: LibraryRequest? = null,
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

    private var activeSession = sessions.current()
    private var generation = 0L
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

    internal fun refresh(source: CloudLibrarySource) = load(source, LibraryRequest.Refresh)

    internal fun loadMore(source: CloudLibrarySource) = load(source, LibraryRequest.Append)

    private fun resetSession(): Boolean {
        val session = sessions.current()
        if (session == activeSession) return false
        activeSession = session
        generation++
        requests.values.forEach { it.cancel() }
        coverRequests.values.forEach { it.cancel() }
        requests.clear()
        coverRequests.clear()
        attemptedCovers.clear()
        covers.clear()
        _remote.value = emptyMap()
        return true
    }

    private fun load(source: CloudLibrarySource, request: LibraryRequest) {
        resetSession()
        val current = _remote.value[source] ?: RemoteLibraryState()
        if (current.request != null) return
        val requestedPage = if (request == LibraryRequest.Append) {
            current.page?.takeIf { it.hasNext }?.let { it.page + 1 } ?: return
        } else {
            1
        }

        val requestGeneration = generation
        val requestSession = activeSession
        updateRemote(source, current.copy(request = request, error = null))
        requests[source] = viewModelScope.launch {
            val result = when (source) {
                CloudLibrarySource.Saved -> repository.saved(requestedPage)
                CloudLibrarySource.DownloadHistory -> repository.history(requestedPage)
            }
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            val updated = when (result) {
                is AppResult.Success -> {
                    // Keep any covers that arrived while the next page was loading.
                    val previousBooks = if (request == LibraryRequest.Append) _remote.value[source]?.page?.books.orEmpty() else emptyList()
                    val page = result.value
                    RemoteLibraryState(
                        page = page.copy(books = (previousBooks + page.books).distinctBy(Book::ref).map(::withCachedCover)),
                    )
                }

                is AppResult.Failure -> (_remote.value[source] ?: current).copy(request = null, error = result.error.userMessage())
            }
            updateRemote(source, updated)
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
