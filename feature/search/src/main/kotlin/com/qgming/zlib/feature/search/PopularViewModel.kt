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

    init {
        loadPopular()
        viewModelScope.launch {
            bookRepository.recommended().getOrNull()?.let { books ->
                _state.update { it.copy(recommended = books.distinctBy(Book::ref)) }
            }
        }
    }

    fun loadPopular() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = bookRepository.popular()) {
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
