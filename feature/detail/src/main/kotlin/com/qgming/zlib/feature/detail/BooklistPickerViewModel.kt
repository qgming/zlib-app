package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.data.library.BooklistPreviews
import com.qgming.zlib.core.data.library.BooklistRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.BooklistMembership
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class PickerRequest { Load, Add, Remove, Create, Verify }

internal data class BooklistPickerState(
    val open: Boolean = false,
    val lists: List<BooklistMembership> = emptyList(),
    val request: PickerRequest? = null,
    val addingList: String? = null,
    val removingList: String? = null,
    val loadError: AppError? = null,
    val formOpen: Boolean = false,
    val title: String = "",
    val createError: AppError? = null,
    val needsVerification: Boolean = false,
    val toastKey: Long = 0L,
)

@HiltViewModel
class BooklistPickerViewModel internal constructor(
    private val session: StateFlow<Session?>,
    private val readMemberships: suspend (Session, String) -> AppResult<List<BooklistMembership>>,
    private val addBook: suspend (Session, BooklistMembership, String) -> AppResult<Unit>,
    private val removeBook: suspend (Session, BooklistMembership, String) -> AppResult<Unit>,
    private val createBooklist: suspend (Session, String, String) -> AppResult<String?>,
    private val invalidateBooklists: (Session) -> Unit,
    readCovers: suspend (Session, String) -> List<BooklistCover>?,
) : ViewModel() {
    @Inject constructor(repository: BooklistRepository) : this(
        repository.session, repository::memberships, repository::addBook, repository::removeBook,
        repository::createBooklist, repository::invalidate, repository::previewCovers,
    )

    private val _state = MutableStateFlow(BooklistPickerState())
    internal val state = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    internal val events = _events.asSharedFlow()

    private data class Target(val session: Session, val bookId: String)
    private data class PendingCreation(val title: String, val oldIds: Set<String>, val returnedId: String?)

    private var target: Target? = null
    private var pending: PendingCreation? = null
    private var job: Job? = null
    private var observedSession = session.value
    private val previews = BooklistPreviews(viewModelScope, { session.value == it }, readCovers)
    private var visibleIds = emptySet<String>()

    init {
        viewModelScope.launch {
            session.collect { resetForSession() }
        }
    }

    internal fun open(bookId: String) {
        resetForSession()
        if (_state.value.open) return
        _state.value = BooklistPickerState(open = true, toastKey = _state.value.toastKey + 1)
        val current = session.value
        if (current == null) {
            _state.value = _state.value.copy(loadError = AppError.NotLoggedIn)
            return
        }
        val opened = Target(current, bookId)
        target = opened
        load(opened)
    }

    internal fun dismiss() {
        when (_state.value.request) {
            PickerRequest.Add, PickerRequest.Remove, PickerRequest.Create, PickerRequest.Verify -> return
            else -> Unit
        }
        close()
    }

    internal fun leaveDetail() = close(clearToast = true)

    internal fun retryList() {
        val opened = currentTarget() ?: return
        if (_state.value.request == null && _state.value.loadError != null) load(opened)
    }

    internal fun showPreviews(ids: Set<String>) {
        val opened = currentTarget() ?: return
        visibleIds = ids
        loadVisiblePreviews(opened)
    }

    private fun loadVisiblePreviews(opened: Target) {
        val state = _state.value
        if (state.formOpen || state.loadError != null || state.request == PickerRequest.Load) return
        previews.loadVisible(opened.session, state.lists.map(BooklistMembership::booklist), visibleIds) { id, covers ->
            if (isCurrent(opened)) {
                val current = _state.value
                _state.value = current.copy(lists = current.lists.map { item ->
                    if (item.booklistId == id) item.copy(booklist = item.booklist.withCoverPreview(covers)) else item
                })
            }
        }
    }

    private fun load(opened: Target) {
        previews.clear()
        _state.value = _state.value.copy(request = PickerRequest.Load, loadError = null)
        job = viewModelScope.launch {
            val result = readMemberships(opened.session, opened.bookId)
            ensureActive()
            if (!isCurrent(opened)) return@launch
            _state.value = when (result) {
                is AppResult.Success -> _state.value.copy(lists = result.value, request = null)
                is AppResult.Failure -> _state.value.copy(loadError = result.error, request = null)
            }
            if (result is AppResult.Success) loadVisiblePreviews(opened)
        }
    }

    internal fun toggle(listId: String) {
        val opened = currentTarget() ?: return
        val state = _state.value
        if (state.request != null || state.formOpen || state.loadError != null) return
        val list = state.lists.firstOrNull { it.booklistId == listId } ?: return
        if (list.isMember) remove(opened, list) else add(opened, list)
    }

    internal fun add(listId: String) {
        val opened = currentTarget() ?: return
        val state = _state.value
        if (state.request != null || state.formOpen || state.loadError != null) return
        val list = state.lists.firstOrNull { it.booklistId == listId && !it.isMember } ?: return
        add(opened, list)
    }

    internal fun remove(listId: String) {
        val opened = currentTarget() ?: return
        val state = _state.value
        if (state.request != null || state.formOpen || state.loadError != null) return
        val list = state.lists.firstOrNull { it.booklistId == listId && it.isMember } ?: return
        remove(opened, list)
    }

    private fun add(opened: Target, list: BooklistMembership) {
        val listId = list.booklistId
        _state.value = _state.value.copy(request = PickerRequest.Add, addingList = listId, removingList = null)
        job = viewModelScope.launch {
            val result = addBook(opened.session, list, opened.bookId)
            ensureActive()
            if (!isCurrent(opened)) return@launch
            val current = _state.value
            val lists = if (result is AppResult.Success) {
                current.lists.map { item ->
                    if (item.booklistId != listId) item else item.copy(
                        isMember = true,
                        booklist = item.booklist.copy(bookCount = item.booklist.bookCount?.takeIf { it < Int.MAX_VALUE }?.plus(1)),
                    )
                }
            } else {
                current.lists
            }
            val loadError = (result as? AppResult.Failure)?.error?.takeIf { it.mayHaveCommitted() }
            if (loadError != null) previews.clear()
            if (result is AppResult.Success) previews.invalidate(listId)
            _state.value = current.copy(lists = lists, request = null, addingList = null, removingList = null, loadError = loadError)
            if (result is AppResult.Success) loadVisiblePreviews(opened)
            _events.tryEmit(when (result) {
                is AppResult.Success -> appText(TextR.string.text_book_added_to_booklist, list.title)
                is AppResult.Failure -> result.error.userMessage()
            })
        }
    }

    private fun remove(opened: Target, list: BooklistMembership) {
        val listId = list.booklistId
        _state.value = _state.value.copy(request = PickerRequest.Remove, addingList = null, removingList = listId)
        job = viewModelScope.launch {
            val result = removeBook(opened.session, list, opened.bookId)
            ensureActive()
            if (!isCurrent(opened)) return@launch
            val current = _state.value
            val lists = if (result is AppResult.Success) {
                current.lists.map { item ->
                    if (item.booklistId != listId) item else item.copy(
                        isMember = false,
                        readlistBookId = null,
                        booklist = item.booklist.copy(bookCount = item.booklist.bookCount?.takeIf { it > 0 }?.minus(1)),
                    )
                }
            } else {
                current.lists
            }
            val loadError = (result as? AppResult.Failure)?.error?.takeIf { it.mayHaveCommitted() }
            if (loadError != null) previews.clear()
            if (result is AppResult.Success) previews.invalidate(listId)
            _state.value = current.copy(lists = lists, request = null, addingList = null, removingList = null, loadError = loadError)
            if (result is AppResult.Success) loadVisiblePreviews(opened)
            _events.tryEmit(when (result) {
                is AppResult.Success -> appText(TextR.string.text_book_removed_from_booklist, list.title)
                is AppResult.Failure -> result.error.userMessage()
            })
        }
    }

    internal fun showForm() {
        currentTarget() ?: return
        val state = _state.value
        if (state.request == null && state.loadError == null && !state.formOpen) {
            visibleIds = emptySet()
            previews.cancel()
            _state.value = state.copy(formOpen = true)
        }
    }

    internal fun backToLists() {
        currentTarget() ?: return
        if (_state.value.request != null) return
        pending = null
        _state.value = _state.value.copy(formOpen = false, title = "", createError = null, needsVerification = false)
    }

    internal fun editTitle(value: String) {
        currentTarget() ?: return
        val state = _state.value
        if (state.formOpen && state.request == null && !state.needsVerification) {
            _state.value = state.copy(title = value.filterNot(Char::isISOControl).take(CloudBooklist.MAX_TITLE_LENGTH), createError = null)
        }
    }

    internal fun create() {
        val opened = currentTarget() ?: return
        val state = _state.value
        val title = state.title.trim()
        if (!state.formOpen || state.request != null || state.needsVerification || title.isEmpty()) return
        val oldIds = state.lists.mapTo(mutableSetOf(), BooklistMembership::booklistId)
        _state.value = state.copy(request = PickerRequest.Create, createError = null)
        job = viewModelScope.launch {
            val result = createBooklist(opened.session, title, opened.bookId)
            ensureActive()
            if (!isCurrent(opened)) return@launch
            when (result) {
                is AppResult.Success -> {
                    val record = PendingCreation(title, oldIds, result.value)
                    pending = record
                    verify(opened, record)
                }
                is AppResult.Failure -> {
                    val error = result.error
                    // A lost or malformed response may have followed a successful write.
                    val uncertain = error.mayHaveCommitted()
                    if (uncertain) pending = PendingCreation(title, oldIds, returnedId = null)
                    _state.value = _state.value.copy(request = null, createError = error, needsVerification = uncertain)
                }
            }
        }
    }

    internal fun recheck() {
        val opened = currentTarget() ?: return
        val record = pending ?: return
        if (_state.value.request != null) return
        _state.value = _state.value.copy(request = PickerRequest.Verify, createError = null)
        job = viewModelScope.launch { verify(opened, record) }
    }

    private suspend fun verify(opened: Target, record: PendingCreation) {
        _state.value = _state.value.copy(request = PickerRequest.Verify)
        val result = readMemberships(opened.session, opened.bookId)
        currentCoroutineContext().ensureActive()
        if (!isCurrent(opened)) return
        val state = _state.value
        when (result) {
            is AppResult.Success -> {
                val lists = result.value.map { it.copy(booklist = previews.withCachedCovers(it.booklist)) }
                val confirmed = lists.singleOrNull { list ->
                    list.isMember && list.booklistId !in record.oldIds &&
                        (record.returnedId?.let { list.booklistId == it } ?: (list.title == record.title))
                }
                if (confirmed != null) {
                    pending = null
                    invalidateBooklists(opened.session)
                    _state.value = state.copy(lists = lists, request = null, formOpen = false, title = "", needsVerification = false)
                    _events.tryEmit(appText(TextR.string.text_booklist_created_with_book, confirmed.title))
                } else {
                    _state.value = state.copy(lists = lists, request = null, needsVerification = true)
                }
            }
            is AppResult.Failure -> _state.value = state.copy(request = null, createError = result.error, needsVerification = true)
        }
    }

    private fun currentTarget(): Target? {
        resetForSession()
        return target
    }

    private fun isCurrent(opened: Target) = target === opened && session.value == opened.session

    private fun AppError.mayHaveCommitted() = this is AppError.Network || this is AppError.Parse ||
        this is AppError.Server && (code == 408 || code >= 500)

    private fun resetForSession() {
        val current = session.value
        if (current == observedSession) return
        observedSession = current
        close(clearToast = true)
    }

    private fun close(clearToast: Boolean = false) {
        target = null
        pending = null
        job?.cancel()
        job = null
        visibleIds = emptySet()
        previews.clear()
        _state.value = BooklistPickerState(toastKey = _state.value.toastKey + if (clearToast) 1 else 0)
    }
}
