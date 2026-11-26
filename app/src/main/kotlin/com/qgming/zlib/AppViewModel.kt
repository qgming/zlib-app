package com.qgming.zlib

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.domain.DomainVerificationCoordinator
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.data.startup.StartupCoordinator
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

@HiltViewModel
class AppViewModel @Inject constructor(
    private val startupCoordinator: StartupCoordinator,
    private val authRepository: AuthRepository,
    private val domainRepository: DomainRepository,
    private val downloadRepository: DownloadRepository,
    private val domainVerification: DomainVerificationCoordinator,
) : ViewModel() {

    val startupState: StateFlow<StartupState> = startupCoordinator.state
    val session: StateFlow<Session?> = authRepository.session
    val accounts = authRepository.accounts

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val settings = domainRepository.settings
    val domainChecks = domainRepository.checks
    val domainProbing = domainRepository.probing

    private val _recoveryApprovalHost = MutableStateFlow<String?>(null)
    val recoveryApprovalHost = _recoveryApprovalHost.asStateFlow()
    private val _recoveryBusy = MutableStateFlow(false)
    val recoveryBusy = _recoveryBusy.asStateFlow()
    private val _verificationRequests = Channel<String>(Channel.BUFFERED)
    val verificationRequests = _verificationRequests.receiveAsFlow()
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private var verifyingHost: String? = null
    private var verificationFinishing = false

    private val _choosingDirectory = MutableStateFlow(false)
    val choosingDirectory = _choosingDirectory.asStateFlow()

    init {
        viewModelScope.launch {
            startupState.map { it is StartupState.Ready }.distinctUntilChanged().collectLatest { ready ->
                if (ready) {
                    launch { startupCoordinator.probeInBackground() }
                    launch { startupCoordinator.refreshAccountInBackground() }
                    launch {
                        downloadRepository.reconcilePublishedIfAny()
                        if (authRepository.session.value != null) downloadRepository.resumePendingIfAny()
                    }
                }
            }
        }
        start()
    }

    fun start() {
        viewModelScope.launch {
            startupCoordinator.start()
        }
    }

    fun chooseInitialDirectory(uri: android.net.Uri) {
        if (_choosingDirectory.value) return
        _choosingDirectory.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                when (val result = downloadRepository.setDownloadDirectory(uri)) {
                    is com.qgming.zlib.core.common.AppResult.Success -> startupCoordinator.start()
                    is com.qgming.zlib.core.common.AppResult.Failure -> _message.value = result.error.userMessage()
                }
            } finally { _choosingDirectory.value = false }
        }
    }

    fun retry() {
        if (_recoveryBusy.value || domainRepository.probing.value || verifyingHost != null || verificationFinishing) return
        _recoveryBusy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                startupCoordinator.retry()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _message.value = appText(TextR.string.text_domain_action_failed_please_retry)
            } finally {
                _recoveryBusy.value = false
            }
        }
    }

    fun chooseRecoveryHost(host: String) {
        if (_recoveryBusy.value || domainRepository.probing.value || verifyingHost != null) return
        if (domainRepository.requiresApproval(host)) {
            _recoveryApprovalHost.value = host
            return
        }
        performRecoverySelection(host, approve = false)
    }

    fun dismissRecoveryApproval() {
        if (!_recoveryBusy.value) _recoveryApprovalHost.value = null
    }

    fun confirmRecoveryApproval() {
        val host = _recoveryApprovalHost.value ?: return
        if (_recoveryBusy.value || verifyingHost != null) return
        _recoveryApprovalHost.value = null
        performRecoverySelection(host, approve = true)
    }

    fun probeRecoveryHost(host: String) {
        if (_recoveryBusy.value || domainRepository.probing.value || verifyingHost != null) return
        _recoveryBusy.value = true
        viewModelScope.launch {
            try {
                domainRepository.probeHost(host)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _message.value = appText(TextR.string.text_domain_action_failed_please_retry)
            } finally {
                _recoveryBusy.value = false
            }
        }
    }

    private fun performRecoverySelection(host: String, approve: Boolean) {
        _recoveryBusy.value = true
        viewModelScope.launch {
            try {
                if (needsVerification(host) && (approve || domainRepository.isApprovedHost(host))) {
                    if (approve) {
                        val result = domainRepository.approveCommunityHost(host)
                        if (result is AppResult.Failure) {
                            _message.value = result.error.userMessage()
                            return@launch
                        }
                    }
                    requestDomainVerification(host)
                    return@launch
                }
                val result = if (approve) domainRepository.approveAndSelectHost(host) else domainRepository.setCurrentHost(host)
                if (needsVerification(host) && domainRepository.isApprovedHost(host)) {
                    requestDomainVerification(host)
                    return@launch
                }
                when (result) {
                    is AppResult.Success -> {
                        _message.value = null
                        startupCoordinator.start()
                    }
                    is AppResult.Failure -> _message.value = result.error.userMessage()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _message.value = appText(TextR.string.text_domain_action_failed_please_retry)
            } finally {
                _recoveryBusy.value = false
            }
        }
    }

    private fun needsVerification(host: String): Boolean = domainChecks.value.any {
        it.host == host && it.status in setOf(DomainStatus.DIAMWALL, DomainStatus.CHALLENGED)
    }

    fun requestDomainVerification(host: String) {
        if (verifyingHost != null || verificationFinishing || !needsVerification(host) || !domainRepository.isApprovedHost(host)) return
        verifyingHost = host
        if (!_verificationRequests.trySend(host).isSuccess) verificationOpenFailed()
    }

    fun verificationOpenFailed() {
        verifyingHost?.let(domainVerification::clearHost)
        verifyingHost = null
        _events.trySend(appText(TextR.string.text_domain_verification_open_failed))
    }

    fun completeDomainVerification(host: String?, rawCookies: String?, completed: Boolean, verifiedNetwork: Long?) {
        val expected = verifyingHost ?: return
        verifyingHost = null
        if (!completed) {
            domainVerification.clearHost(expected)
            return
        }
        if (host != expected || !domainRepository.isApprovedHost(expected)) {
            domainVerification.clearHost(expected)
            _events.trySend(appText(TextR.string.text_domain_verification_failed))
            return
        }
        verificationFinishing = true
        _recoveryBusy.value = true
        viewModelScope.launch {
            try {
                domainVerification.clearHost(expected)
                if (rawCookies != null && !domainVerification.accept(expected, rawCookies, verifiedNetwork)) {
                    _events.send(appText(TextR.string.text_domain_verification_failed))
                    return@launch
                }
                when (val result = domainRepository.selectVerifiedHost(expected)) {
                    is AppResult.Success -> {
                        _message.value = null
                        _events.send(appText(TextR.string.text_switched_to, expected))
                        startupCoordinator.start()
                    }
                    is AppResult.Failure -> {
                        domainVerification.clearHost(expected)
                        _events.send(result.error.userMessage())
                    }
                }
            } catch (cancellation: CancellationException) {
                domainVerification.clearHost(expected)
                throw cancellation
            } catch (_: Exception) {
                domainVerification.clearHost(expected)
                _events.send(appText(TextR.string.text_domain_verification_failed))
            } finally {
                verificationFinishing = false
                _recoveryBusy.value = false
            }
        }
    }

    fun addCustomHost(raw: String) {
        if (_recoveryBusy.value || domainRepository.probing.value || verifyingHost != null || verificationFinishing) return
        _recoveryBusy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                when (val added = domainRepository.addCustomHost(raw)) {
                    is AppResult.Success -> {
                        if (needsVerification(added.value)) {
                            requestDomainVerification(added.value)
                            return@launch
                        }
                        when (val selection = domainRepository.setCurrentHost(added.value)) {
                            is AppResult.Success -> startupCoordinator.start()
                            is AppResult.Failure -> _message.value = selection.error.userMessage()
                        }
                    }
                    is AppResult.Failure -> _message.value = added.error.userMessage()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _message.value = appText(TextR.string.text_domain_action_failed_please_retry)
            } finally {
                _recoveryBusy.value = false
            }
        }
    }
}
