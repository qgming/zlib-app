package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.OnlineBookPreview
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface BookPreviewState {
    data object Loading : BookPreviewState
    data object Failed : BookPreviewState
    data object Unavailable : BookPreviewState
    data class Ready(val preview: OnlineBookPreview) : BookPreviewState
}

@HiltViewModel
internal class BookPreviewViewModel internal constructor(
    private val sessions: SessionManager,
    private val resolve: suspend (String, String, Session?) -> AppResult<OnlineBookPreview?>,
) : ViewModel() {
    @Inject constructor(books: BookRepository, sessions: SessionManager) : this(sessions, books::resolveOnlinePreview)

    private val _state = MutableStateFlow<BookPreviewState>(BookPreviewState.Loading)
    val state = _state.asStateFlow()

    private var request: Job? = null
    private var opened = false

    fun open(id: String, hash: String) {
        if (opened) return
        opened = true
        refresh(id, hash)
    }

    fun refresh(id: String, hash: String) {
        if (request?.isActive == true) return
        _state.value = BookPreviewState.Loading
        request = viewModelScope.launch {
            val result = resolve(id, hash, sessions.current())
            _state.value = when (result) {
                is AppResult.Success -> result.value?.let(BookPreviewState::Ready)
                    ?: BookPreviewState.Unavailable
                is AppResult.Failure -> BookPreviewState.Failed
            }
        }
    }

    fun currentPreview(): OnlineBookPreview? =
        (_state.value as? BookPreviewState.Ready)?.preview
}
