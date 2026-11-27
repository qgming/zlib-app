package com.qgming.zlib.feature.downloads

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.LocalFile
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class DownloadNotice(val generation: Long, val message: String)
internal data class DownloadFileIntent(val generation: Long, val file: LocalFile)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModel @Inject constructor(
    private val repository: DownloadRepository,
    private val settings: SettingsStore,
    private val sessions: SessionManager,
) : ViewModel() {

    val queue: StateFlow<List<DownloadItem>?> = repository.queue
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = MutableSharedFlow<DownloadNotice>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val events = _events.asSharedFlow()
    private val _fileIntents = MutableSharedFlow<DownloadFileIntent>(extraBufferCapacity = 1)
    internal val fileIntents = _fileIntents.asSharedFlow()
    private val _feedbackGeneration = MutableStateFlow(0L)
    internal val feedbackGeneration = _feedbackGeneration.asStateFlow()
    private var feedbackScope: FeedbackScope? = null
    private var openRequest: Job? = null
    private var visible = false
    private var pageGeneration = 0L

    init {
        viewModelScope.launch {
            combine(sessions.session, settings.settings.map { it.downloadTreeUri }.distinctUntilChanged()) { session, tree ->
                FeedbackScope(session, tree)
            }.distinctUntilChanged().collect(::updateFeedbackScope)
        }
    }

    internal fun show() { visible = true }

    internal fun hide() {
        visible = false
        pageGeneration += 1
        invalidateFeedback()
    }

    internal fun consumeNotice(notice: DownloadNotice) {
        if (_events.replayCache.lastOrNull() === notice) _events.resetReplayCache()
    }

    internal fun canShowNotice(notice: DownloadNotice): Boolean =
        visible && notice.generation == feedbackGeneration.value && feedbackScope?.session == sessions.current()

    internal fun canLaunch(intent: DownloadFileIntent): Boolean =
        visible && feedbackScope != null && intent.generation == feedbackGeneration.value &&
            intent.file.treeUri == feedbackScope?.tree && feedbackScope?.session == sessions.current()

    internal fun fileLaunchFailed(message: String, generation: Long) {
        if (visible && generation == feedbackGeneration.value) {
            _events.tryEmit(DownloadNotice(generation, message))
        }
    }

    internal fun openCompleted(id: String) {
        // The first SettingsStore snapshot establishes the directory scope before accepting opens.
        if (!visible || feedbackScope == null || feedbackScope?.session != sessions.current()) return
        openRequest?.cancel()
        val generation = feedbackGeneration.value
        openRequest = viewModelScope.launch {
            val result = repository.completedFile(id)
            currentCoroutineContext().ensureActive()
            if (!visible || generation != feedbackGeneration.value) return@launch
            when (result) {
                is AppResult.Success -> _fileIntents.emit(DownloadFileIntent(generation, result.value))
                is AppResult.Failure -> emitMessage(result.error.userMessage(), generation)
            }
        }
    }

    fun pause(id: String) {
        viewModelScope.launch { repository.pause(id) }
    }

    fun resume(id: String) {
        val generation = feedbackGeneration.value
        viewModelScope.launch {
            when (val result = repository.resume(id)) {
                is AppResult.Failure -> emitMessage(result.error.userMessage(), generation)
                is AppResult.Success -> Unit
            }
        }
    }

    fun retry(id: String) {
        val generation = feedbackGeneration.value
        viewModelScope.launch {
            when (val result = repository.retry(id)) {
                is AppResult.Failure -> emitMessage(result.error.userMessage(), generation)
                is AppResult.Success -> Unit
            }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch { repository.cancel(id) }
    }

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun setDirectory(uri: android.net.Uri) {
        val session = sessions.current()
        val requestedPage = pageGeneration
        viewModelScope.launch {
            val result = repository.setDownloadDirectory(uri)
            // An Activity Result may arrive before ON_START. Keep that result for this page's
            // next collector, but never bring it back after another navigation/stop.
            if (session != sessions.current() || requestedPage != pageGeneration) return@launch
            // Apply the new tree before emitting its success message, so its toast uses that scope.
            updateFeedbackScope(FeedbackScope(session, settings.read().downloadTreeUri))
            when (result) {
                is AppResult.Success -> emitMessage(if (result.value.pausedTasks > 0) {
                    appText(TextR.string.text_download_folder_saved_tasks_are_paused_and_can_be_resumed, result.value.pausedTasks)
                } else appText(TextR.string.text_download_folder_saved), feedbackGeneration.value)
                is AppResult.Failure -> emitMessage(result.error.userMessage(), feedbackGeneration.value)
            }
        }
    }

    private suspend fun emitMessage(message: String, generation: Long) {
        if (generation == feedbackGeneration.value) _events.emit(DownloadNotice(generation, message))
    }

    private fun updateFeedbackScope(scope: FeedbackScope) {
        if (feedbackScope != null && feedbackScope != scope) invalidateFeedback()
        feedbackScope = scope
    }

    private fun invalidateFeedback() {
        openRequest?.cancel()
        openRequest = null
        _events.resetReplayCache()
        _feedbackGeneration.value += 1
    }

    private data class FeedbackScope(val session: Session?, val tree: String?)
}
