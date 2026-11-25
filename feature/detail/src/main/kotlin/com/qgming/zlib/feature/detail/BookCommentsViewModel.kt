package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookCommentsRepository
import com.qgming.zlib.core.model.BookComment
import com.qgming.zlib.core.model.BookCommentsPage
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class BookCommentsState(
    val page: BookCommentsPage? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val dataset: Long = 0L,
    val threads: CommentThreads = CommentThreads(page?.comments.orEmpty()),
) {
    val previewComments: List<BookComment> get() = threads.mainComments.take(COMMENT_PREVIEW_COUNT)
}

private const val COMMENT_PREVIEW_COUNT = 3

@HiltViewModel
class BookCommentsViewModel internal constructor(
    private val session: StateFlow<Session?>,
    private val readPage: suspend (Session, String, Int) -> AppResult<BookCommentsPage>,
) : ViewModel() {
    @Inject constructor(repository: BookCommentsRepository) : this(repository.session, repository::comments)

    private val _state = MutableStateFlow(BookCommentsState())
    internal val state = _state.asStateFlow()
    private var bookId: String? = null
    private var destination: Destination = Destination.List
    private var observedSession = session.value
    private var request: Job? = null

    private sealed interface Destination {
        data object Preview : Destination
        data object List : Destination
        data class Thread(val commentId: String) : Destination
    }

    init {
        viewModelScope.launch {
            session.collect { resetForSession() }
        }
    }

    /** The preview, list and thread destinations share the same book dataset. */
    fun showPreview(id: String) = show(id, Destination.Preview)

    fun open(id: String, commentId: String? = null) = show(id, commentId?.let { Destination.Thread(it) } ?: Destination.List)

    private fun show(id: String, next: Destination) {
        val keepDataset = id == bookId && session.value == observedSession
        val previous = destination
        destination = next
        if (!keepDataset) {
            observedSession = session.value
            bookId = id
            resetPage()
            return
        }
        val current = state.value
        if (current.loading) return
        if (current.error != null) {
            if (previous == Destination.Preview && next != Destination.Preview) retry()
        } else if (needsMore(current.threads)) {
            current.page?.nextPage?.let(::load)
        }
    }

    private fun needsMore(threads: CommentThreads): Boolean = when (val current = destination) {
        Destination.Preview -> threads.mainComments.size < COMMENT_PREVIEW_COUNT
        Destination.List -> threads.mainComments.isEmpty()
        is Destination.Thread -> threads.mainComment(current.commentId) == null
    }

    fun close() {
        if (bookId == null) return
        bookId = null
        destination = Destination.List
        request?.cancel()
        request = null
        _state.value = BookCommentsState(dataset = _state.value.dataset + 1)
    }

    internal fun loadMore() {
        resetForSession()
        val state = _state.value
        if (state.loading || state.error != null) return
        state.page?.nextPage?.let(::load)
    }

    internal fun retry() {
        resetForSession()
        val state = _state.value
        if (state.loading || state.error == null) return
        load(state.page?.nextPage ?: FIRST_PAGE)
    }

    private fun resetForSession() {
        val current = session.value
        if (observedSession == current) return
        observedSession = current
        if (bookId != null) resetPage()
    }

    private fun resetPage() {
        request?.cancel()
        _state.value = BookCommentsState(dataset = _state.value.dataset + 1)
        load(FIRST_PAGE)
    }

    private fun load(pageNumber: Int) {
        val id = bookId ?: return
        val expected = session.value
        val dataset = state.value.dataset
        if (expected == null) {
            _state.value = state.value.copy(loading = false, error = AppError.NotLoggedIn)
            return
        }
        _state.value = state.value.copy(loading = true, error = null)
        request = viewModelScope.launch { readPages(id, expected, dataset, pageNumber) }
    }

    private suspend fun readPages(id: String, expected: Session, dataset: Long, pageNumber: Int) {
        var number = pageNumber
        while (true) {
            val result = readPage(expected, id, number)
            currentCoroutineContext().ensureActive()
            if (bookId != id || session.value != expected || state.value.dataset != dataset) return
            val before = state.value
            when (result) {
                is AppResult.Failure -> {
                    _state.value = before.copy(loading = false, error = result.error)
                    return
                }
                is AppResult.Success -> {
                    val loaded = result.value
                    val old = before.page?.comments.orEmpty()
                    val combined = (old + loaded.comments).distinctBy(BookComment::id)
                    val page = if (loaded.disabled) loaded else loaded.copy(
                        comments = combined,
                        nextPage = loaded.nextPage?.takeIf { it > number && combined.size > old.size },
                    )
                    val threads = CommentThreads(page.comments)
                    val next = page.nextPage?.takeIf { needsMore(threads) }
                    _state.value = before.copy(page = page, threads = threads, loading = next != null)
                    number = next ?: return
                }
            }
        }
    }

    private companion object {
        const val FIRST_PAGE = 0
    }
}
