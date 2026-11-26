package com.qgming.zlib.feature.settings

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.DomainCheck
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.model.BookGridColumns
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val checks: List<DomainCheck> = emptyList(),
    val currentHost: String? = null,
    val probing: Boolean = false,
    val customHosts: Set<String> = emptySet(),
    val lockedHost: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bookGridColumns: BookGridColumns = BookGridColumns.AUTO,
    val directory: String? = null,
    val account: Account? = null,
)

data class AddDomainState(
    val visible: Boolean = false,
    val host: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val domainRepository: DomainRepository,
    private val downloadRepository: DownloadRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val directory = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            val name = downloadRepository.downloadDirectoryName()
            directory.value = if (name != null && !downloadRepository.downloadDirectoryAccessible()) {
                appText(TextR.string.text_folder_unavailable_choose_it_again_to_grant_access)
            } else name
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        domainRepository.checks,
        domainRepository.currentHost,
        domainRepository.probing,
        domainRepository.settings,
    ) { checks, current, probing, settings ->
        SettingsUiState(
            checks = checks,
            currentHost = current,
            probing = probing,
            customHosts = settings.customHosts,
            lockedHost = settings.lockedHost,
            themeMode = settings.themeMode,
            bookGridColumns = settings.bookGridColumns,
        )
    }.combine(directory) { settings, name -> settings.copy(directory = name) }
        .combine(accountRepository.account) { settings, account -> settings.copy(account = account) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val _domainBusy = MutableStateFlow(false)
    val domainBusy = _domainBusy.asStateFlow()
    private val _addDomain = MutableStateFlow(AddDomainState())
    val addDomain = _addDomain.asStateFlow()
    private val _pendingMirrorHost = MutableStateFlow<String?>(null)
    val pendingMirrorHost = _pendingMirrorHost.asStateFlow()
    private val _verificationRequests = Channel<String>(Channel.BUFFERED)
    val verificationRequests = _verificationRequests.receiveAsFlow()

    fun probeAll() = domainAction {
        domainRepository.probeAll(switchToBest = true, includeCommunity = true)
        _events.send(appText(TextR.string.text_all_domain_checks_complete))
    }

    fun probeHost(host: String) = domainAction {
        domainRepository.probeHost(host)
    }

    fun showAddDomain() {
        if (!_addDomain.value.busy) _addDomain.value = AddDomainState(visible = true)
    }

    fun dismissAddDomain() {
        if (!_addDomain.value.busy) _addDomain.value = AddDomainState()
    }

    fun onDomainInput(value: String) = _addDomain.update { it.copy(host = value, error = null) }

    fun addCustomHost() {
        val form = _addDomain.value
        if (form.busy || _domainBusy.value || form.host.isBlank()) return
        _addDomain.value = form.copy(busy = true, error = null)
        _domainBusy.value = true
        viewModelScope.launch {
            try {
                when (val result = domainRepository.addCustomHost(form.host)) {
                    is AppResult.Success -> {
                        _addDomain.value = AddDomainState()
                        _events.send(appText(TextR.string.text_added, result.value))
                    }
                    is AppResult.Failure -> _addDomain.value = form.copy(error = result.error.userMessage())
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _addDomain.value = form.copy(error = appText(TextR.string.text_cannot_add_domain_please_retry))
            } finally {
                _domainBusy.value = false
                _addDomain.update { it.copy(busy = false) }
            }
        }
    }

    fun removeCustomHost(host: String) = domainAction {
        when (val result = domainRepository.removeCustomHost(host)) {
            is AppResult.Success -> _events.send(appText(TextR.string.text_removed, host))
            is AppResult.Failure -> _events.send(result.error.userMessage())
        }
    }

    fun selectHost(host: String) {
        if (_domainBusy.value || _addDomain.value.busy || state.value.probing || _pendingMirrorHost.value != null) return
        if (domainRepository.requiresApproval(host)) {
            _pendingMirrorHost.value = host
            return
        }
        domainAction {
            if (requiresVerification(host) && domainRepository.isApprovedHost(host)) _verificationRequests.send(host)
            else completeSelection(host, domainRepository.setCurrentHost(host))
        }
    }

    fun dismissCommunityMirror() {
        _pendingMirrorHost.value = null
    }

    fun confirmCommunityMirror() {
        val host = _pendingMirrorHost.value ?: return
        if (_domainBusy.value || _addDomain.value.busy || state.value.probing) return
        _pendingMirrorHost.value = null
        domainAction {
            if (requiresVerification(host)) {
                when (val result = domainRepository.approveCommunityHost(host)) {
                    is AppResult.Success -> _verificationRequests.send(result.value)
                    is AppResult.Failure -> _events.send(result.error.userMessage())
                }
            } else {
                completeSelection(host, domainRepository.approveAndSelectHost(host))
            }
        }
    }

    private fun requiresVerification(host: String): Boolean = domainRepository.checks.value.any {
        it.host == host && (it.status == DomainStatus.DIAMWALL || it.status == DomainStatus.CHALLENGED)
    }

    private suspend fun completeSelection(host: String, result: AppResult<String>) {
        if (domainRepository.isApprovedHost(host) && requiresVerification(host)) {
            _verificationRequests.send(host)
            return
        }
        when (result) {
            is AppResult.Success -> _events.send(appText(TextR.string.text_switched_to, result.value))
            is AppResult.Failure -> _events.send(result.error.userMessage())
        }
    }

    fun setAutoSwitch(enabled: Boolean) = domainAction {
        val result = domainRepository.setAutoSwitchEnabled(enabled)
        if (result is AppResult.Failure) _events.send(result.error.userMessage())
    }

    private fun domainAction(action: suspend () -> Unit) {
        if (_domainBusy.value || _addDomain.value.busy || state.value.probing) return
        _domainBusy.value = true
        viewModelScope.launch {
            try {
                action()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _events.send(appText(TextR.string.text_domain_action_failed_please_retry))
            } finally {
                _domainBusy.value = false
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { domainRepository.setThemeMode(mode) }
    }

    fun setBookGridColumns(columns: BookGridColumns) {
        viewModelScope.launch { domainRepository.setBookGridColumns(columns) }
    }

    fun setDownloadDirectory(uri: android.net.Uri) {
        viewModelScope.launch {
            when (val result = downloadRepository.setDownloadDirectory(uri)) {
                is AppResult.Success -> {
                    directory.value = downloadRepository.downloadDirectoryName()
                    _events.send(if (result.value.pausedTasks > 0) {
                        appText(TextR.string.text_download_folder_saved_resume_tasks_in_download_manager, result.value.pausedTasks)
                    } else appText(TextR.string.text_download_folder_saved))
                }

                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
