package com.qgming.zlib.feature.library

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.LocalFilesRepository
import com.qgming.zlib.core.data.download.LocalFilesState
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.LocalFile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class LocalFileAction(val file: LocalFile? = null, val confirming: Boolean = false, val busy: Boolean = false, val error: String? = null)
internal data class LocalFileIntent(val file: LocalFile, val share: Boolean)

@HiltViewModel
class LocalFilesViewModel @Inject constructor(private val repository: LocalFilesRepository) : ViewModel() {
    val state = repository.observeFiles().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0),
        LocalFilesState(loading = true),
    )
    private val _action = MutableStateFlow(LocalFileAction())
    internal val action = _action.asStateFlow()
    private val _events = Channel<String>(Channel.BUFFERED)
    internal val events = _events.receiveAsFlow()
    private val _fileIntents = Channel<LocalFileIntent>(Channel.BUFFERED)
    internal val fileIntents = _fileIntents.receiveAsFlow()

    fun refresh(userInitiated: Boolean = false) = repository.refresh(userInitiated)

    internal fun showActions(file: LocalFile) {
        if (!_action.value.busy && file.treeUri == state.value.treeUri) _action.value = LocalFileAction(file)
    }

    internal fun dismissActions() { if (!_action.value.busy) _action.value = LocalFileAction() }
    internal fun confirmDeletion() { _action.value = _action.value.copy(confirming = true) }

    internal fun delete() {
        val current = _action.value
        val file = current.file ?: return
        if (!current.confirming || current.busy) return
        _action.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = repository.deleteLocal(file)) {
                is AppResult.Success -> {
                    _action.value = LocalFileAction()
                    _events.send(appText(TextR.string.text_local_file_deleted))
                }
                is AppResult.Failure -> _action.value = current.copy(error = result.error.userMessage())
            }
        }
    }

    internal fun open(file: LocalFile, share: Boolean = false) {
        dismissActions()
        viewModelScope.launch {
            when (val result = repository.readable(file)) {
                is AppResult.Success -> _fileIntents.send(LocalFileIntent(result.value, share))
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
