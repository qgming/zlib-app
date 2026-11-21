package com.qgming.zlib.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val showFilters: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val landingLoading: Boolean = false,
    val searched: Boolean = false,
    val books: List<Book> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 0,
    val error: String? = null,
    val appendError: String? = null,
    val popular: List<Book> = emptyList(),
    val recommended: List<Book> = emptyList(),
) {
    val hasMore: Boolean get() = searched && page < totalPages
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val bookRepository: BookRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        loadLanding()
    }

    fun onQueryChange(value: String) = _state.update { it.copy(query = value, error = null) }

    fun toggleFilters() = _state.update { it.copy(showFilters = !it.showFilters) }

    fun toggleExact(enabled: Boolean) = _state.update {
        it.copy(filters = it.filters.copy(exact = enabled))
    }

    fun toggleLanguage(language: String) = _state.update {
        val languages = it.filters.languages
        it.copy(
            filters = it.filters.copy(
                languages = if (language in languages) languages - language else languages + language,
            ),
        )
    }

    fun toggleExtension(extension: String) = _state.update {
        val extensions = it.filters.extensions
        it.copy(
            filters = it.filters.copy(
                extensions = if (extension in extensions) extensions - extension else extensions + extension,
            ),
        )
    }

    fun onYearFromChange(value: String) = _state.update {
        it.copy(filters = it.filters.copy(yearFrom = value.filter(Char::isDigit).take(4)))
    }

    fun onYearToChange(value: String) = _state.update {
        it.copy(filters = it.filters.copy(yearTo = value.filter(Char::isDigit).take(4)))
    }

    fun setOrder(order: String?) = _state.update {
        it.copy(filters = it.filters.copy(order = if (it.filters.order == order) null else order))
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
        if (current.loading) return

        viewModelScope.launch {
            _state.update {
                it.copy(loading = true, searched = true, error = null, appendError = null, page = 0, totalPages = 0, books = emptyList())
            }
            when (val result = bookRepository.search(current.filters.toQuery(current.query), page = 1)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        loading = false,
                        books = result.value.books,
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
        if (current.loading || current.loadingMore || !current.hasMore) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, appendError = null) }
            when (val result = bookRepository.search(current.filters.toQuery(current.query), page = current.page + 1)) {
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

    private fun loadLanding() {
        viewModelScope.launch {
            _state.update { it.copy(landingLoading = true) }
            val popular = bookRepository.popular().let { if (it is AppResult.Success) it.value else emptyList() }
            val recommended = bookRepository.recommended().let { if (it is AppResult.Success) it.value else emptyList() }
            _state.update {
                it.copy(
                    landingLoading = false,
                    popular = popular,
                    recommended = recommended,
                )
            }
        }
    }
}
