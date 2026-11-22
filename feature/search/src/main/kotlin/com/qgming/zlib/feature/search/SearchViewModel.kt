package com.qgming.zlib.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.SearchQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val searched: Boolean = false,
    val books: List<Book> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val error: String? = null,
    val appendError: String? = null,
) {
    val hasMore: Boolean get() = searched && page < totalPages
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val bookRepository: BookRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var submittedQuery: SearchQuery? = null
    private var request: Job? = null
    private var initialQueryApplied = false

    /** A route may re-enter composition after details; never reset its edited query or pages. */
    fun initializeQuery(query: String) {
        if (initialQueryApplied || query.isBlank()) return
        initialQueryApplied = true
        _state.value = SearchUiState(query = query.trim())
        search()
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value, error = null) }

    fun applyFilters(filters: SearchFilters) {
        if (!filters.yearRangeValid) return
        _state.update { it.copy(filters = filters) }
        if (_state.value.query.isNotBlank()) search()
    }

    fun search() {
        val current = _state.value
        if (current.query.isBlank()) {
            _state.update { it.copy(error = "请输入书名、作者或 ISBN") }
            return
        }
        if (!current.filters.yearRangeValid) {
            _state.update { it.copy(error = "起始年份不能大于结束年份") }
            return
        }
        val query = current.filters.toQuery(current.query)
        if (current.loading && query == submittedQuery) return
        request?.cancel()
        submittedQuery = query
        _state.update {
            it.copy(
                loading = true,
                loadingMore = false,
                searched = true,
                error = null,
                appendError = null,
                page = 0,
                totalPages = 0,
                books = emptyList(),
            )
        }
        request = viewModelScope.launch {
            val result = bookRepository.search(query, page = 1)
            ensureActive()
            when (result) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        books = result.value.books.distinctBy(Book::ref),
                        page = result.value.page,
                        totalPages = result.value.totalPages,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.userMessage())
                }
            }
        }
    }

    fun loadMore() {
        val current = _state.value
        val query = submittedQuery ?: return
        if (current.loading || current.loadingMore || !current.hasMore) return
        _state.update { it.copy(loadingMore = true, appendError = null) }
        request = viewModelScope.launch {
            val result = bookRepository.search(query, page = current.page + 1)
            ensureActive()
            when (result) {
                is AppResult.Success -> _state.update {
                    val merged = (it.books + result.value.books).distinctBy(Book::ref)
                    it.copy(
                        loadingMore = false,
                        books = merged,
                        page = result.value.page,
                        totalPages = result.value.totalPages,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loadingMore = false, appendError = result.error.userMessage())
                }
            }
        }
    }
}
