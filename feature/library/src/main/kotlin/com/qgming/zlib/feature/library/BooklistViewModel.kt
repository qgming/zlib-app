package com.qgming.zlib.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.data.library.BooklistRepository
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BooklistPage
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.model.ShareImageContent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class BooklistsUiState(
    val lists: List<CloudBooklist>? = null,
    val request: LibraryRequest? = null,
    val error: AppError? = null,
    val failedRequest: LibraryRequest? = null,
)

internal data class BooklistBooksUiState(
    val booklist: CloudBooklist? = null,
    val page: BooklistPage? = null,
    val request: LibraryRequest? = null,
    val error: AppError? = null,
    val failedRequest: LibraryRequest? = null,
    val sessionChanged: Boolean = false,
)

@HiltViewModel
class BooklistViewModel @Inject constructor(private val repository: BooklistRepository) : ViewModel() {
    private val _booklists = MutableStateFlow(BooklistsUiState())
    internal val booklists = _booklists.asStateFlow()
    private val _books = MutableStateFlow(BooklistBooksUiState())
    internal val books = _books.asStateFlow()

    private var activeSession = repository.session.value
    private var booklistsRequest: Job? = null
    private var booksRequest: Job? = null
    private var booklistsVisible = false
    private var revision = repository.revision.value
    private var visibleListIds = emptySet<String>()
    private val previews = repository.previews(viewModelScope)
    private var openedListId: String? = null

    init {
        viewModelScope.launch {
            repository.session.collect { resetSession() }
        }
        viewModelScope.launch {
            repository.revision.collect { latest ->
                if (latest == revision) return@collect
                revision = latest
                if (resetSession()) return@collect
                booklistsRequest?.cancel()
                previews.clear()
                if (booklistsVisible) {
                    _booklists.value = _booklists.value.copy(request = null)
                    loadBooklists(LibraryRequest.Refresh)
                } else {
                    _booklists.value = BooklistsUiState()
                }
                if (openedListId != null) {
                    booksRequest?.cancel()
                    val state = _books.value
                    _books.value = state.copy(request = null, page = state.page?.copy(nextPage = null))
                    loadBooks(LibraryRequest.Refresh)
                }
            }
        }
    }

    internal fun showBooklists() {
        if (booklistsVisible) return
        booklistsVisible = true
        if (resetSession()) return
        if (booklists.value == BooklistsUiState()) {
            loadBooklists(LibraryRequest.Initial)
        }
    }

    internal fun hideBooklists() {
        booklistsVisible = false
        visibleListIds = emptySet()
        booklistsRequest?.cancel()
        previews.cancel()
        _booklists.value = _booklists.value.copy(request = null)
    }

    internal fun refreshBooklists() = loadBooklists(LibraryRequest.Refresh)

    internal fun retryBooklists() = loadBooklists(booklists.value.failedRequest ?: LibraryRequest.Refresh)

    internal fun selectList(id: String, onOpen: (String) -> Unit) {
        if (resetSession() || !booklistsVisible) return
        if (booklists.value.lists?.any { it.id == id } == true) onOpen(id)
    }

    internal fun showPreviews(visibleIds: Set<String>) {
        if (resetSession() || !booklistsVisible) return
        visibleListIds = visibleIds
        loadVisiblePreviews()
    }

    internal fun openList(id: String) {
        resetSession()
        if (openedListId == id) return
        openedListId = id
        booksRequest?.cancel()
        _books.value = BooklistBooksUiState()
        loadBooks(LibraryRequest.Initial)
    }

    internal fun closeList(id: String) {
        if (openedListId != null && openedListId != id) return
        openedListId = null
        booksRequest?.cancel()
        _books.value = BooklistBooksUiState()
    }

    internal fun refreshBooks() = loadBooks(LibraryRequest.Refresh)

    internal fun loadMore() = loadBooks(LibraryRequest.Append)

    internal fun retryBooks() = loadBooks(_books.value.failedRequest ?: LibraryRequest.Refresh, manualRetry = true)

    internal fun canOpenBook(ref: String): Boolean =
        !resetSession() && _books.value.page?.books?.any { it.ref == ref } == true

    internal fun shareList(onShare: (CloudBooklist, List<Book>, Boolean) -> Unit) {
        if (resetSession()) return
        val state = _books.value
        val booklist = state.booklist ?: return
        val page = state.page ?: return
        if (openedListId != booklist.id || state.sessionChanged || state.request == LibraryRequest.Refresh || state.error != null) return
        onShare(
            booklist,
            page.books.take(ShareImageContent.MAX_BOOKS),
            page.nextPage != null || page.books.size > ShareImageContent.MAX_BOOKS,
        )
    }

    private fun loadBooklists(request: LibraryRequest) {
        if (resetSession() || !booklistsVisible) return
        val previous = booklists.value
        if (previous.request != null) return
        val session = activeSession
        if (session == null) {
            _booklists.value = previous.copy(error = AppError.NotLoggedIn, failedRequest = request)
            return
        }
        if (request == LibraryRequest.Refresh) previews.clear()
        _booklists.value = previous.copy(request = request, error = null, failedRequest = null)
        booklistsRequest = viewModelScope.launch {
            val result = repository.myBooklists(session)
            currentCoroutineContext().ensureActive()
            if (repository.session.value != session || !booklistsVisible) return@launch
            when (result) {
                is AppResult.Success -> {
                    _booklists.value = BooklistsUiState(lists = result.value.map(previews::withCachedCovers))
                    loadVisiblePreviews()
                }
                is AppResult.Failure -> _booklists.value = previous.copy(request = null, error = result.error, failedRequest = request)
            }
        }
    }

    private fun loadVisiblePreviews() {
        val session = activeSession ?: return
        if (booklists.value.request != null) return
        previews.loadVisible(session, booklists.value.lists.orEmpty(), visibleListIds) { id, covers ->
            val state = booklists.value
            _booklists.value = state.copy(lists = state.lists?.map { if (it.id == id) it.withCoverPreview(covers) else it })
        }
    }

    private fun loadBooks(request: LibraryRequest, manualRetry: Boolean = false) {
        resetSession()
        val id = openedListId ?: return
        val previous = _books.value
        if (previous.sessionChanged || previous.request != null) return
        val page = if (request == LibraryRequest.Append) {
            if (previous.error != null && !manualRetry) return
            previous.page?.nextPage ?: return
        } else {
            1
        }
        val session = activeSession
        if (session == null) {
            _books.value = previous.copy(request = null, error = AppError.NotLoggedIn, failedRequest = request)
            return
        }
        _books.value = previous.copy(request = request, error = null, failedRequest = null)
        booksRequest = viewModelScope.launch {
            val booklist = when {
                request == LibraryRequest.Append && previous.booklist != null -> previous.booklist
                else -> when (val result = repository.myBooklists(session)) {
                    is AppResult.Success -> result.value.firstOrNull { it.id == id } ?: run {
                        failBooks(
                            session,
                            request,
                            AppError.Unsupported(appText(TextR.string.text_booklist_no_longer_available)),
                            clear = true,
                        )
                        return@launch
                    }
                    is AppResult.Failure -> {
                        failBooks(session, request, result.error)
                        return@launch
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (repository.session.value != session || openedListId != id) return@launch
            _books.value = _books.value.copy(booklist = booklist)
            val result = repository.books(session, id, page)
            currentCoroutineContext().ensureActive()
            if (repository.session.value != session || openedListId != id) return@launch
            val current = _books.value
            _books.value = when (result) {
                is AppResult.Success -> {
                    val oldBooks = if (request == LibraryRequest.Append) previous.page?.books.orEmpty() else emptyList()
                    val books = (oldBooks + result.value.books).distinctBy(Book::ref)
                    current.copy(
                        page = BooklistPage(books, result.value.nextPage.takeIf { books.size > oldBooks.size }),
                        request = null,
                        error = null,
                        failedRequest = null,
                    )
                }
                is AppResult.Failure -> current.copy(
                    request = null,
                    error = result.error,
                    failedRequest = request,
                )
            }
        }
    }

    private suspend fun failBooks(session: Session, request: LibraryRequest, error: AppError, clear: Boolean = false) {
        currentCoroutineContext().ensureActive()
        if (repository.session.value == session) {
            _books.value = _books.value.copy(
                booklist = if (clear) null else _books.value.booklist,
                page = if (clear) null else _books.value.page,
                request = null,
                error = error,
                failedRequest = request,
            )
        }
    }

    private fun resetSession(): Boolean {
        val session = repository.session.value
        if (session == activeSession) return false
        activeSession = session
        booklistsRequest?.cancel()
        booksRequest?.cancel()
        previews.clear()
        val hadOpenedList = openedListId != null || _books.value.sessionChanged
        openedListId = null
        _booklists.value = BooklistsUiState()
        _books.value = BooklistBooksUiState(sessionChanged = hadOpenedList)
        if (booklistsVisible) loadBooklists(LibraryRequest.Initial)
        return true
    }
}
