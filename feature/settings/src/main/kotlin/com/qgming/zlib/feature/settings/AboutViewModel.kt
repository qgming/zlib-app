package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.update.AppUpdateRepository
import com.qgming.zlib.core.data.update.UpdateDownloadManager
import com.qgming.zlib.core.data.update.UpdateDownloadPhase
import com.qgming.zlib.core.data.update.UpdateDownloadState
import com.qgming.zlib.core.model.UpdateCheckResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

enum class UpdateFailure { Network, RateLimit, Response }
enum class AboutUpdateEvent { AlreadyCurrent }

data class AboutUpdateState(
    val visible: Boolean = false,
    val checking: Boolean = false,
    val result: UpdateCheckResult? = null,
    val failure: UpdateFailure? = null,
)

@HiltViewModel
class AboutViewModel internal constructor(
    private val check: suspend (String) -> AppResult<UpdateCheckResult>,
    private val downloads: UpdateDownloadManager? = null,
) : ViewModel() {
    @Inject constructor(repository: AppUpdateRepository, downloads: UpdateDownloadManager) : this(repository::check, downloads)

    private val _update = MutableStateFlow(AboutUpdateState())
    val update = _update.asStateFlow()
    private val _events = Channel<AboutUpdateEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var request: Job? = null
    private var manualCheckRequested = false
    val download = downloads?.state ?: MutableStateFlow(UpdateDownloadState()).asStateFlow()
    private var startupChecked = false

    init {
        if (downloads != null) viewModelScope.launch {
            downloads.state.collect { transfer ->
                val release = transfer.release
                if (transfer.phase == UpdateDownloadPhase.Ready && release != null) {
                    _update.value = AboutUpdateState(visible = true, result = UpdateCheckResult.Available(release))
                }
            }
        }
    }

    fun checkOnStart(version: String) {
        if (startupChecked) return
        startupChecked = true
        viewModelScope.launch {
            downloads?.restoreReady()
            checkUpdate(version, automatic = true)
        }
    }

    fun checkUpdate(version: String, automatic: Boolean = false) {
        if (downloads?.state?.value?.let { it.busy || it.phase == UpdateDownloadPhase.Ready } == true) {
            downloads.state.value.release?.let { _update.value = AboutUpdateState(visible = true, result = UpdateCheckResult.Available(it)) }
            return
        }
        if (_update.value.checking) {
            if (!automatic) manualCheckRequested = true
            return
        }
        manualCheckRequested = !automatic
        _update.value = AboutUpdateState(checking = true)
        request = viewModelScope.launch {
            try {
                _update.value = when (val result = check(version)) {
                    is AppResult.Success -> AboutUpdateState(
                        visible = result.value !is UpdateCheckResult.Current &&
                            (manualCheckRequested || result.value is UpdateCheckResult.Available),
                        result = result.value,
                    )
                    is AppResult.Failure -> AboutUpdateState(visible = manualCheckRequested, failure = when (val error = result.error) {
                        is AppError.Network -> UpdateFailure.Network
                        is AppError.Server -> if (error.code == 403 || error.code == 429) UpdateFailure.RateLimit else UpdateFailure.Response
                        else -> UpdateFailure.Response
                    })
                }
                if (manualCheckRequested && _update.value.result is UpdateCheckResult.Current) {
                    _events.send(AboutUpdateEvent.AlreadyCurrent)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _update.value = AboutUpdateState(visible = manualCheckRequested, failure = UpdateFailure.Response)
            }
        }
    }

    fun dismissUpdate() {
        request?.cancel()
        request = null
        manualCheckRequested = false
        _update.value = AboutUpdateState()
    }

    fun downloadUpdate() {
        val release = (_update.value.result as? UpdateCheckResult.Available)?.release ?: return
        viewModelScope.launch { downloads?.start(release) }
    }

    fun cancelUpdate() { downloads?.cancel(); dismissUpdate() }
    fun setDirectoryAndDownload(uri: android.net.Uri) {
        viewModelScope.launch { if (downloads?.setDirectory(uri) == true) downloadUpdate() }
    }
    fun installerFile() = downloads?.installerFile()
}
