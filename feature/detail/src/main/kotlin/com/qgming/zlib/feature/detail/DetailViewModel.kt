package com.qgming.zlib.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.library.BookRepository
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.data.download.ExistingDownload
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val book: Book? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val formats: FormatMenuState? = null,
    val enqueuing: Boolean = false,
    val existing: ExistingDownload? = null,
    val existingSelection: Book? = null,
)

data class FormatMenuState(
    val options: List<BookFormat> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class DetailViewModel internal constructor(
    private val bookRepository: BookRepository,
    private val sessions: SessionManager,
    private val enqueue: suspend (Book) -> AppResult<Unit>,
    private val findExisting: suspend (Book) -> ExistingDownload? = { null },
    private val forceEnqueue: suspend (Book) -> AppResult<Unit> = enqueue,
) : ViewModel() {
    @Inject constructor(
        bookRepository: BookRepository,
        downloadRepository: DownloadRepository,
        sessions: SessionManager,
    ) : this(bookRepository, sessions, { downloadRepository.enqueue(it) }, downloadRepository::findExistingDownload,
        { downloadRepository.enqueue(it, forceRedownload = true) })

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _openFiles = Channel<ExistingDownload>(Channel.BUFFERED)
    val openFiles = _openFiles.receiveAsFlow()

    private var lastId: String? = null
    private var lastHash: String? = null
    private var detailRequest: Job? = null
    private var formatRequest: Job? = null
    private var downloadRequest: Job? = null
    private var session = sessions.current()
    private var generation = 0L
    private var formatGeneration = 0L

    init {
        viewModelScope.launch {
            sessions.session.collect {
                if (resetSession()) {
                    val id = lastId
                    val hash = lastHash
                    if (sessions.current() != null && id != null && hash != null) load(id, hash)
                }
            }
        }
    }

    private fun resetSession(): Boolean {
        if (session == sessions.current()) return false
        session = sessions.current()
        generation++
        formatGeneration++
        detailRequest?.cancel()
        formatRequest?.cancel()
        downloadRequest?.cancel()
        while (_events.tryReceive().isSuccess) { /* Discard previous-account feedback. */ }
        while (_openFiles.tryReceive().isSuccess) { }
        _state.value = DetailUiState()
        return true
    }

    fun load(id: String, hash: String) {
        resetSession()
        val current = _state.value
        if (current.book?.ref == "$id:$hash" && !current.loading) return
        if (current.loading && lastId == id && lastHash == hash) return

        lastId = id
        lastHash = hash
        detailRequest?.cancel()
        dismissFormats()
        val requestGeneration = ++generation
        val requestSession = sessions.current()
        _state.value = DetailUiState(loading = true)
        detailRequest = viewModelScope.launch {
            when (val result = bookRepository.detail(id, hash)) {
                is AppResult.Success -> if (generation == requestGeneration && sessions.current() == requestSession) _state.update {
                    it.copy(book = result.value, loading = false, error = null)
                }

                is AppResult.Failure -> if (generation == requestGeneration && sessions.current() == requestSession) _state.update {
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

    fun showFormats() {
        if (resetSession()) return
        val book = _state.value.book ?: return
        if (_state.value.enqueuing || _state.value.formats?.loading == true) return
        val current = book.currentFormat()
        formatRequest?.cancel()
        val requestGeneration = ++formatGeneration
        val requestSession = sessions.current()
        _state.update { it.copy(formats = FormatMenuState(loading = true)) }
        formatRequest = viewModelScope.launch {
            if (sessions.current() != requestSession) return@launch
            val result = bookRepository.formats(book)
            if (formatGeneration != requestGeneration || sessions.current() != requestSession) return@launch
            val menu = when (result) {
                is AppResult.Success -> FormatMenuState(
                    options = (result.value + listOfNotNull(current)).distinctBy(BookFormat::ref),
                )
                is AppResult.Failure -> FormatMenuState(options = listOfNotNull(current), error = result.error.userMessage())
            }
            _state.update { it.copy(formats = menu) }
        }
    }

    fun dismissFormats() {
        formatGeneration++
        formatRequest?.cancel()
        _state.update { it.copy(formats = null) }
    }

    fun selectFormat(ref: String) {
        if (resetSession()) return
        val state = _state.value
        val book = state.book ?: return
        val menu = state.formats ?: return
        if (menu.loading || state.enqueuing) return
        val format = menu.options.firstOrNull { it.ref == ref } ?: return
        val requestGeneration = generation
        val requestSession = sessions.current()
        dismissFormats()
        _state.update { it.copy(enqueuing = true) }
        downloadRequest = viewModelScope.launch {
            if (sessions.current() != requestSession) return@launch
            // The selected file has its own reference. Do not reuse the detail file's hash/size.
            val selected = book.copy(
                id = format.id, hash = format.hash, extension = format.extension, sizeBytes = format.sizeBytes,
                localUri = null, downloadedAt = null, historyDate = null,
            )
            val existing = findExisting(selected)
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            if (existing != null) {
                _state.update { it.copy(enqueuing = false, existing = existing, existingSelection = selected) }
                return@launch
            }
            val result = enqueue(selected)
            if (generation != requestGeneration || sessions.current() != requestSession) return@launch
            _state.update { it.copy(enqueuing = false) }
            _events.send(when (result) {
                is AppResult.Success -> "${format.extension.uppercase()} 已加入下载队列"
                is AppResult.Failure -> result.error.userMessage()
            })
        }
    }

    fun dismissExisting() { if (!_state.value.enqueuing) _state.update { it.copy(existing = null, existingSelection = null) } }

    fun openExisting() {
        if (resetSession()) return
        val selected = _state.value.existingSelection ?: return
        val expectedSession = sessions.current()
        val requestedGeneration = generation
        dismissExisting()
        viewModelScope.launch {
            val file = findExisting(selected)
            if (requestedGeneration != generation || sessions.current() != expectedSession) return@launch
            if (file == null) _events.send("文件已不存在或无法读取，请重试下载") else _openFiles.send(file)
        }
    }

    fun redownload() {
        if (resetSession() || _state.value.enqueuing) return
        val selected = _state.value.existingSelection ?: return
        val requested = generation
        val expected = sessions.current()
        _state.update { it.copy(existing = null, existingSelection = null, enqueuing = true) }
        downloadRequest = viewModelScope.launch {
            val result = forceEnqueue(selected)
            if (requested != generation || sessions.current() != expected) return@launch
            _state.update { it.copy(enqueuing = false) }
            _events.send(when (result) {
                is AppResult.Success -> "已重新加入下载队列"
                is AppResult.Failure -> result.error.userMessage()
            })
        }
    }

    private fun Book.currentFormat(): BookFormat? =
        if (extension.matches(Regex("[a-z0-9]{1,20}"))) BookFormat(id, hash, extension, sizeBytes) else null
}
