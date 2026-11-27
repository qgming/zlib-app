package com.qgming.zlib.feature.library

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.LocalFilesRepository
import com.qgming.zlib.core.data.download.LocalFilesState
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.LocalFile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class LocalFileAction(val file: LocalFile? = null, val confirming: Boolean = false, val busy: Boolean = false, val error: String? = null)
internal data class LocalFileIntent(val file: LocalFile, val share: Boolean, val generation: Long)
internal data class LocalFileNotice(val message: String, val generation: Long)

@HiltViewModel
class LocalFilesViewModel internal constructor(
    observeFiles: Flow<LocalFilesState>,
    settings: Flow<AppSettings>,
    private val refreshFiles: (Boolean) -> Unit,
    private val readFile: suspend (LocalFile) -> AppResult<LocalFile>,
    private val deleteFile: suspend (LocalFile) -> AppResult<Unit>,
) : ViewModel() {
    @Inject constructor(repository: LocalFilesRepository, settings: SettingsStore) : this(
        repository.observeFiles(), settings.settings, repository::refresh, repository::readable, repository::deleteLocal,
    )

    val state = observeFiles.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
        LocalFilesState(loading = true),
    )
    private val _action = MutableStateFlow(LocalFileAction())
    internal val action = _action.asStateFlow()
    private val _events = MutableSharedFlow<LocalFileNotice>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val events = _events.asSharedFlow()
    private val _fileIntents = MutableSharedFlow<LocalFileIntent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    internal val fileIntents = _fileIntents.asSharedFlow()
    private val _feedbackGeneration = MutableStateFlow(0L)
    internal val feedbackGeneration = _feedbackGeneration.asStateFlow()
    private var treeUri: String? = null
    private var treeLoaded = false
    private var visible = false
    private var openRequest: Job? = null
    private var deleteRequest: Job? = null

    init {
        // Observe only the saved URI here; directory scans still run only while the UI collects state.
        viewModelScope.launch {
            settings.map { it.downloadTreeUri }.distinctUntilChanged().collect { tree ->
                if (treeLoaded && treeUri != tree) invalidateFeedback()
                treeUri = tree
                treeLoaded = true
            }
        }
    }

    internal fun show() { visible = true }

    internal fun hide() {
        visible = false
        invalidateFeedback()
    }

    internal fun canShowNotice(notice: LocalFileNotice): Boolean =
        visible && notice.generation == feedbackGeneration.value

    internal fun canLaunch(intent: LocalFileIntent): Boolean =
        visible && treeLoaded && intent.generation == feedbackGeneration.value && intent.file.treeUri == treeUri

    internal fun fileLaunchFailed(message: String, generation: Long) {
        if (visible && generation == feedbackGeneration.value) {
            _events.tryEmit(LocalFileNotice(message, generation))
            refresh()
        }
    }

    fun refresh(userInitiated: Boolean = false) = refreshFiles(userInitiated)

    internal fun showActions(file: LocalFile) {
        if (visible && treeLoaded && deleteRequest?.isActive != true && !_action.value.busy &&
            file.treeUri == treeUri && file.treeUri == state.value.treeUri
        ) _action.value = LocalFileAction(file)
    }

    internal fun dismissActions() { if (!_action.value.busy) _action.value = LocalFileAction() }
    internal fun confirmDeletion() { _action.value = _action.value.copy(confirming = true) }

    internal fun delete() {
        val current = _action.value
        val file = current.file ?: return
        if (!visible || !treeLoaded || file.treeUri != treeUri || !current.confirming || current.busy || deleteRequest?.isActive == true) return
        val generation = feedbackGeneration.value
        _action.value = current.copy(busy = true, error = null)
        deleteRequest = viewModelScope.launch {
            val result = deleteFile(file)
            // Deletion may finish after navigation; keep the write but discard its old-page UI.
            if (!visible || generation != feedbackGeneration.value) return@launch
            when (result) {
                is AppResult.Success -> {
                    _action.value = LocalFileAction()
                    _events.emit(LocalFileNotice(appText(TextR.string.text_local_file_deleted), generation))
                }
                is AppResult.Failure -> _action.value = current.copy(error = result.error.userMessage())
            }
        }
    }

    internal fun open(file: LocalFile, share: Boolean = false) {
        if (!visible || !treeLoaded || file.treeUri != treeUri) return
        dismissActions()
        openRequest?.cancel()
        val generation = feedbackGeneration.value
        openRequest = viewModelScope.launch {
            val result = readFile(file)
            currentCoroutineContext().ensureActive()
            if (!visible || generation != feedbackGeneration.value || file.treeUri != treeUri) return@launch
            when (result) {
                is AppResult.Success -> _fileIntents.emit(LocalFileIntent(result.value, share, generation))
                is AppResult.Failure -> _events.emit(LocalFileNotice(result.error.userMessage(), generation))
            }
        }
    }

    private fun invalidateFeedback() {
        openRequest?.cancel()
        openRequest = null
        _action.value = LocalFileAction()
        _feedbackGeneration.value += 1
    }
}
