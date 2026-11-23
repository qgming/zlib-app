package com.qgming.zlib.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.getOrNull
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PopularUiState(
    val loading: Boolean = true,
    val popular: List<Book> = emptyList(),
    val recommended: List<Book> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PopularViewModel @Inject constructor(
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(PopularUiState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var recommendedJob: Job? = null

    init {
        loadPopular()
        loadRecommended()
        var previous: com.qgming.zlib.core.model.Session? = null
        var initialized = false
        viewModelScope.launch {
            bookRepository.sessionChanges.collect { session ->
                if (initialized && (session?.remixUserId != previous?.remixUserId || session?.remixUserKey != previous?.remixUserKey)) {
                    loadJob?.cancel(); loadJob = null
                    recommendedJob?.cancel()
                    _state.value = PopularUiState()
                    if (session != null) { loadPopular(); loadRecommended() }
                } else if (initialized && session?.domain != previous?.domain && session != null) {
                    if (_state.value.error != null || _state.value.popular.isEmpty()) {
                        loadJob?.cancel(); loadJob = null
                        loadPopular()
                    }
                    if (_state.value.recommended.isEmpty()) {
                        recommendedJob?.cancel()
                        loadRecommended()
                    }
                }
                previous = session
                initialized = true
            }
        }
    }

    private fun loadRecommended() {
        recommendedJob = viewModelScope.launch {
            bookRepository.recommended().getOrNull()?.let { books ->
                ensureActive()
                _state.update { it.copy(recommended = books.distinctBy(Book::ref)) }
            }
        }
    }

    fun loadPopular() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val result = bookRepository.popular()
            ensureActive()
            when (result) {
                is AppResult.Success -> _state.update {
                    it.copy(loading = false, popular = result.value.distinctBy(Book::ref))
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.userMessage())
                }
            }
        }
    }
}
