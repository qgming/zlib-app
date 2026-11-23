package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SimilarBooksState(val books: List<Book> = emptyList(), val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class SimilarBooksViewModel @Inject constructor(
    private val repository: BookRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(SimilarBooksState())
    val state = _state.asStateFlow()
    private var ref: Pair<String, String>? = null
    private var job: Job? = null
    private var generation = 0L
    private var session = sessions.current()

    init {
        viewModelScope.launch {
            sessions.session.collect {
                if (session != it) {
                    session = it
                    generation++
                    job?.cancel()
                    _state.value = SimilarBooksState()
                    ref?.let { (id, hash) -> if (session != null) load(id, hash, force = true) }
                }
            }
        }
    }

    fun load(id: String, hash: String, force: Boolean = false) {
        if (!force && ref == (id to hash)) return
        ref = id to hash
        job?.cancel()
        val requested = ++generation
        val expectedSession = sessions.current()
        _state.value = SimilarBooksState(loading = true)
        job = viewModelScope.launch {
            val result = repository.similar(id, hash)
            if (requested != generation || sessions.current() != expectedSession) return@launch
            _state.value = when (result) {
                is AppResult.Success -> SimilarBooksState(books = result.value)
                is AppResult.Failure -> SimilarBooksState(error = result.error.userMessage())
            }
        }
    }

    fun retry() { ref?.let { (id, hash) -> load(id, hash, force = true) } }
}
