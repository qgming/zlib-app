package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.OnlineBookPreview
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal sealed interface BookPreviewState {
    data object Loading : BookPreviewState
    data object Failed : BookPreviewState
    data object Unavailable : BookPreviewState
    data object SourceChanged : BookPreviewState
    data class Ready(val preview: OnlineBookPreview) : BookPreviewState
}

@HiltViewModel
internal class BookPreviewViewModel internal constructor(
    private val sessions: SessionManager,
    private val hosts: StateFlow<String?>? = null,
    private val resolve: suspend (String, String, Session?) -> AppResult<OnlineBookPreview?>,
) : ViewModel() {
    @Inject constructor(books: BookRepository, sessions: SessionManager, domains: DomainRepository) :
        this(sessions, domains.currentHost, books::resolveOnlinePreview)

    private val _state = MutableStateFlow<BookPreviewState>(BookPreviewState.Loading)
    val state = _state.asStateFlow()

    private var request: Job? = null
    private var opened = false
    private var requestSource: Source? = null

    private data class Source(val session: Session?, val host: String?)

    init {
        viewModelScope.launch {
            val sources = if (hosts == null) sessions.session else combine(sessions.session, hosts) { session, _ -> session }
            sources.collect {
                if (requestSource != null && requestSource != currentSource()) invalidateSource()
            }
        }
    }

    fun open(id: String, hash: String) {
        if (opened) return
        opened = true
        refresh(id, hash)
    }

    fun close() {
        opened = false
        requestSource = null
        request?.cancel()
        request = null
        _state.value = BookPreviewState.Loading
    }

    fun refresh(id: String, hash: String) {
        val source = currentSource()
        if (request?.isActive == true && requestSource == source) return
        request?.cancel()
        if (hosts != null && (source.host == null || (source.session != null && source.session.domain != source.host))) {
            invalidateSource()
            return
        }
        requestSource = source
        _state.value = BookPreviewState.Loading
        request = viewModelScope.launch {
            val result = resolve(id, hash, source.session)
            ensureActive()
            if (source != currentSource()) {
                invalidateSource()
                return@launch
            }
            _state.value = when (result) {
                is AppResult.Success -> result.value?.let(BookPreviewState::Ready)
                    ?: BookPreviewState.Unavailable
                is AppResult.Failure -> BookPreviewState.Failed
            }
        }
    }

    fun currentPreview(): OnlineBookPreview? = (_state.value as? BookPreviewState.Ready)?.preview
        ?.takeIf { requestSource == currentSource() }

    private fun currentSource(): Source {
        val session = sessions.current()
        return Source(session, if (hosts == null) session?.domain else hosts.value)
    }

    private fun invalidateSource() {
        requestSource = null
        request?.cancel()
        request = null
        _state.value = BookPreviewState.SourceChanged
    }
}
