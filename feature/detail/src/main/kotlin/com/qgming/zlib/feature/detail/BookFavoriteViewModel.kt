package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.FavoritesRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.designsystem.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BookFavoriteState(
    val saved: Boolean? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Separate from detail/download loading so a favorite failure cannot hide the book. */
@HiltViewModel
class BookFavoriteViewModel @Inject constructor(
    private val favorites: FavoritesRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(BookFavoriteState())
    val state = _state.asStateFlow()
    private var bookId: String? = null
    private var session = sessions.current()
    private var generation = 0L
    private var request: Job? = null

    init {
        viewModelScope.launch {
            sessions.session.collect {
                if (session != sessions.current()) {
                    session = sessions.current()
                    generation++
                    request?.cancel()
                    _state.value = BookFavoriteState()
                    bookId?.let(::load)
                }
            }
        }
    }

    fun load(id: String) {
        if (bookId == id && _state.value.busy) return
        bookId = id
        request?.cancel()
        val requestGeneration = ++generation
        val requestSession = sessions.current()
        _state.value = BookFavoriteState(busy = true)
        request = viewModelScope.launch {
            val result = favorites.isSaved(id)
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            _state.value = when (result) {
                is AppResult.Success -> BookFavoriteState(saved = result.value)
                is AppResult.Failure -> BookFavoriteState(error = result.error.userMessage())
            }
        }
    }

    fun toggle() {
        val id = bookId ?: return
        if (session != sessions.current()) {
            session = sessions.current()
            generation++
            request?.cancel()
            _state.value = BookFavoriteState()
            load(id)
            return
        }
        val current = _state.value
        val saved = current.saved ?: return
        if (current.busy) return
        val requestGeneration = ++generation
        val requestSession = sessions.current()
        _state.value = current.copy(busy = true, error = null)
        request = viewModelScope.launch {
            val result = favorites.setSaved(id, !saved)
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            _state.value = when (result) {
                is AppResult.Success -> BookFavoriteState(saved = !saved)
                is AppResult.Failure -> current.copy(busy = false, error = result.error.userMessage())
            }
        }
    }

    fun retry() {
        if (_state.value.saved == null) bookId?.let(::load) else toggle()
    }
}
