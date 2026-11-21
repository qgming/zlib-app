package com.qgming.zlib.feature.detail

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

data class DetailUiState(
    val book: Book? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val bookRepository: BookRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var lastId: String? = null
    private var lastHash: String? = null

    fun load(id: String, hash: String) {
        val current = _state.value
        if (current.book?.ref == "$id:$hash" && !current.loading) return
        if (current.loading && lastId == id && lastHash == hash) return

        lastId = id
        lastHash = hash
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            when (val result = bookRepository.detail(id, hash)) {
                is AppResult.Success -> _state.update {
                    it.copy(book = result.value, loading = false, error = null)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(loading = false, error = result.error.userMessage())
                }
            }
        }
    }

    fun retry() {
        val id = lastId ?: return
        val hash = lastHash ?: return
        _state.update { it.copy(book = null) }
        load(id, hash)
    }
}
