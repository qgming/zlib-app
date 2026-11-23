package com.qgming.zlib.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.SearchFilterOptions
import com.qgming.zlib.core.data.settings.SearchHistory
import com.qgming.zlib.core.data.settings.SearchHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

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
    val filterOptions: SearchFilterOptions = SearchFilterOptions.Fallback,
    val loadingOptions: Boolean = false,
    val optionsError: String? = null,
) {
    val hasMore: Boolean get() = searched && page < totalPages
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val savedState: SavedStateHandle,
    private val historyRepository: SearchHistoryRepository? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState(query = savedState["query"] ?: "", filters = restoreFilters()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()
    private var submittedQuery: SearchQuery? = null
    private var request: Job? = null
    private var initialQueryApplied = savedState.get<Boolean>("submitted") == true
    private var optionsLoaded = false
    val history = (historyRepository?.history ?: flowOf(SearchHistory())).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchHistory(),
    )

    init {
        if (savedState.get<Boolean>("submitted") == true && savedState.get<String>("accountId") == bookRepository.currentAccountId()) {
            val restoredSubmitted = savedState.get<String>("submittedQuery")
            if (!restoredSubmitted.isNullOrBlank()) {
                val draftQuery = _state.value.query
                _state.update { it.copy(query = restoredSubmitted) }
                search()
                _state.update { it.copy(query = draftQuery) }
                savedState["query"] = draftQuery
            }
        }
        var previous: com.qgming.zlib.core.model.Session? = null
        var initialized = false
        viewModelScope.launch {
            bookRepository.sessionChanges.collect { session ->
                if (initialized && (session?.remixUserId != previous?.remixUserId || session?.remixUserKey != previous?.remixUserKey)) {
                    request?.cancel()
                    submittedQuery = null
                    _state.value = SearchUiState(
                        query = _state.value.query,
                        filterOptions = _state.value.filterOptions,
                        loadingOptions = _state.value.loadingOptions,
                        optionsError = _state.value.optionsError,
                    )
                    savedState["submitted"] = false
                    persistFilters(SearchFilters())
                }
                previous = session
                initialized = true
            }
        }
    }

    /** A route may re-enter composition after details; never reset its edited query or pages. */
    fun initializeQuery(query: String) {
        if (initialQueryApplied || query.isBlank()) return
        initialQueryApplied = true
        _state.value = SearchUiState(query = query.trim(), filterOptions = _state.value.filterOptions)
        savedState["query"] = query.trim()
        search()
    }

    fun onQueryChange(value: String) {
        savedState["query"] = value
        _state.update { it.copy(query = value, error = null) }
    }

    fun applyFilters(filters: SearchFilters) {
        if (!filters.yearRangeValid) return
        val valid = filters.validated(_state.value.filterOptions)
        _state.update { it.copy(filters = valid) }
        persistFilters(valid)
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
        // Restored selections may come from a previous server candidate list. Load the current
        // list before submitting them; unknown codes are otherwise silently ignored by EAPI.
        if (current.filters != current.filters.validated(current.filterOptions)) {
            if (!optionsLoaded) {
                request?.cancel()
                _state.update { it.copy(loading = true, loadingMore = false, searched = true, error = null, appendError = null) }
                request = viewModelScope.launch {
                    val result = bookRepository.searchFilterOptions()
                    ensureActive()
                    when (result) {
                        is AppResult.Success -> {
                            optionsLoaded = true
                            val filters = current.filters.validated(result.value)
                            _state.update { it.copy(loading = false, filterOptions = result.value, filters = filters) }
                            persistFilters(filters)
                            if (filters != current.filters) {
                                _state.update { it.copy(error = "部分筛选项已不可用，请确认筛选后重新搜索") }
                            } else {
                                // Preserve an edited draft while restoring the last submitted text.
                                val draft = _state.value.query
                                _state.update { it.copy(query = current.query) }
                                search()
                                _state.update { it.copy(query = draft) }
                                savedState["query"] = draft
                            }
                        }
                        is AppResult.Failure -> _state.update {
                            it.copy(loading = false, error = "无法确认已保存的筛选条件，请打开筛选重试", optionsError = result.error.userMessage())
                        }
                    }
                }
            } else {
                val filters = current.filters.validated(current.filterOptions)
                _state.update { it.copy(filters = filters, searched = true, error = "部分筛选项已不可用，请确认筛选后重新搜索") }
                persistFilters(filters)
            }
            return
        }
        val query = current.filters.toQuery(current.query)
        if (current.loading && query == submittedQuery) return
        request?.cancel()
        val expectedSession = bookRepository.currentSession()
        submittedQuery = query
        savedState["query"] = query.message
        savedState["submitted"] = true
        savedState["submittedQuery"] = query.message
        savedState["accountId"] = bookRepository.currentAccountId()
        persistFilters(current.filters)
        historyRepository?.let { repository -> viewModelScope.launch { runCatching { repository.record(query.message) } } }
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
            if (!sameAccount(expectedSession)) return@launch
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
        val expectedSession = bookRepository.currentSession()
        _state.update { it.copy(loadingMore = true, appendError = null) }
        request = viewModelScope.launch {
            val result = bookRepository.search(query, page = current.page + 1)
            ensureActive()
            if (!sameAccount(expectedSession)) return@launch
            when (result) {
                is AppResult.Success -> _state.update {
                    val merged = (it.books + result.value.books).distinctBy(Book::ref)
                    val progressed = result.value.page == current.page + 1 && merged.size > it.books.size
                    it.copy(
                        loadingMore = false,
                        books = merged,
                        page = if (progressed) result.value.page else it.page,
                        totalPages = if (progressed) result.value.totalPages else it.page,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loadingMore = false, appendError = result.error.userMessage())
                }
            }
        }
    }

    fun loadFilterOptions() {
        if (optionsLoaded || _state.value.loadingOptions) return
        _state.update { it.copy(loadingOptions = true, optionsError = null) }
        viewModelScope.launch {
            when (val result = bookRepository.searchFilterOptions()) {
                is AppResult.Success -> {
                    optionsLoaded = true
                    _state.update { it.copy(loadingOptions = false, filterOptions = result.value) }
                }
                is AppResult.Failure -> _state.update { it.copy(loadingOptions = false, optionsError = result.error.userMessage()) }
            }
        }
    }

    fun searchRecent(query: String) { onQueryChange(query); search() }
    fun removeRecent(query: String) = updateHistory { it.remove(query) }
    fun clearRecent() = updateHistory { it.clear() }

    private fun updateHistory(action: suspend (SearchHistoryRepository) -> Unit) {
        viewModelScope.launch {
            val repository = historyRepository ?: return@launch
            try { action(repository) } catch (_: java.io.IOException) {
                _state.update { it.copy(error = "无法保存最近搜索设置，请重试") }
            }
        }
    }

    private fun restoreFilters() = SearchFilters(
        languages = savedState.get<ArrayList<String>>("languages")?.toSet().orEmpty(),
        extensions = savedState.get<ArrayList<String>>("extensions")?.toSet().orEmpty(),
        yearFrom = savedState["yearFrom"] ?: "", yearTo = savedState["yearTo"] ?: "",
        exact = savedState["exact"] ?: false, order = savedState["order"],
    )

    private fun sameAccount(expected: com.qgming.zlib.core.model.Session?): Boolean {
        val current = bookRepository.currentSession()
        return current?.remixUserId == expected?.remixUserId && current?.remixUserKey == expected?.remixUserKey
    }

    private fun persistFilters(filters: SearchFilters) {
        savedState["languages"] = ArrayList(filters.languages)
        savedState["extensions"] = ArrayList(filters.extensions)
        savedState["yearFrom"] = filters.yearFrom; savedState["yearTo"] = filters.yearTo
        savedState["exact"] = filters.exact; savedState["order"] = filters.order
    }
}
