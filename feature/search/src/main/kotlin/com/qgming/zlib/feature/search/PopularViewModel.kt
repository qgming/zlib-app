package com.qgming.zlib.feature.search

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReadingSource(private val titleRes: Int) {
    Popular(TextR.string.text_popular),
    Recommended(TextR.string.text_recommended);

    val title: String get() = appText(titleRes)
}

data class ReadingFeedState(
    val loading: Boolean = false,
    val books: List<Book> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PopularViewModel @Inject constructor(
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<Map<ReadingSource, ReadingFeedState>>(emptyMap())
    val state = _state.asStateFlow()
    private val requests = mutableMapOf<ReadingSource, Job>()
    private var activeSession = bookRepository.currentSession()

    init {
        ReadingSource.entries.forEach(::refresh)
        viewModelScope.launch {
            bookRepository.sessionChanges.collect { session ->
                val previous = activeSession
                activeSession = session
                if (session?.remixUserId != previous?.remixUserId || session?.remixUserKey != previous?.remixUserKey) {
                    requests.values.forEach { it.cancel() }
                    requests.clear()
                    _state.value = emptyMap()
                    if (session != null) ReadingSource.entries.forEach(::refresh)
                } else if (session?.domain != previous?.domain && session != null) {
                    ReadingSource.entries.forEach { source ->
                        val feed = _state.value[source]
                        if (feed == null || feed.loading || feed.error != null || feed.books.isEmpty()) {
                            requests.remove(source)?.cancel()
                            refresh(source)
                        }
                    }
                }
            }
        }
    }

    fun refresh(source: ReadingSource) {
        if (requests[source]?.isActive == true) return
        val expectedSession = bookRepository.currentSession()
        updateFeed(source, (_state.value[source] ?: ReadingFeedState()).copy(loading = true, error = null))
        requests[source] = viewModelScope.launch {
            val result = when (source) {
                ReadingSource.Popular -> bookRepository.popular()
                ReadingSource.Recommended -> bookRepository.recommended()
            }
            ensureActive()
            if (!sameSession(expectedSession)) return@launch
            when (result) {
                is AppResult.Success -> updateFeed(source, ReadingFeedState(books = result.value.distinctBy(Book::ref)))
                is AppResult.Failure -> updateFeed(source, (_state.value[source] ?: ReadingFeedState()).copy(
                    loading = false, error = result.error.userMessage(),
                ))
            }
        }
    }

    private fun sameSession(expected: Session?): Boolean {
        val current = bookRepository.currentSession()
        return current?.remixUserId == expected?.remixUserId &&
            current?.remixUserKey == expected?.remixUserKey && current?.domain == expected?.domain
    }

    private fun updateFeed(source: ReadingSource, feed: ReadingFeedState) {
        _state.update { it + (source to feed) }
    }
}
