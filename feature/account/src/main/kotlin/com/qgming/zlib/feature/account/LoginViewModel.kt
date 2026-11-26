package com.qgming.zlib.feature.account

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.DomainCheck
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val cookieText: String = "",
    val domain: String? = null,
    val checks: List<DomainCheck> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val showCookieImport: Boolean = false,
    val showDomainPicker: Boolean = false,
    val switchingDomain: Boolean = false,
    val domainError: String? = null,
    val pendingMirrorHost: String? = null,
)

@HiltViewModel
class LoginViewModel internal constructor(
    private val domainRepository: DomainRepository,
    private val loginWithPassword: suspend (String, String) -> AppResult<Session>,
    private val importCookies: suspend (String) -> AppResult<Session>,
) : ViewModel() {
    @Inject constructor(authRepository: AuthRepository, domainRepository: DomainRepository) : this(
        domainRepository, authRepository::loginWithPassword, authRepository::importCookies,
    )

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()
    private val _verificationRequests = Channel<String>(Channel.BUFFERED)
    val verificationRequests = _verificationRequests.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(domainRepository.currentHost, domainRepository.checks) { host, checks -> host to checks }
                .collect { (host, checks) ->
                    _state.update { it.copy(domain = host, checks = checks) }
            }
        }
    }

    private fun requestCommunityMirror(host: String) {
        _state.update {
            if (!it.showDomainPicker || it.loading || it.switchingDomain || it.pendingMirrorHost != null) it
            else it.copy(pendingMirrorHost = host, domainError = null)
        }
    }

    fun dismissCommunityMirror() {
        _state.update { it.copy(pendingMirrorHost = null) }
    }

    fun confirmCommunityMirror() {
        val host = _state.value.pendingMirrorHost ?: return
        if (requiresVerification(host)) {
            switchDomain {
                when (val result = domainRepository.approveCommunityHost(host)) {
                    is AppResult.Success -> {
                        _verificationRequests.send(result.value)
                        result
                    }
                    is AppResult.Failure -> result
                }
            }
        } else {
            switchDomain { selectionResult(host, domainRepository.approveAndSelectHost(host)) }
        }
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onCookieTextChange(value: String) = _state.update { it.copy(cookieText = value, error = null) }

    fun toggleCookieImport() {
        if (_state.value.loading) return
        _state.update { it.copy(showCookieImport = !it.showCookieImport, password = "", cookieText = "", error = null) }
    }

    fun login() {
        val current = _state.value
        authenticate { loginWithPassword(current.email, current.password) }
    }

    fun importCookieText() {
        val raw = _state.value.cookieText
        if (raw.isBlank()) {
            _state.update { it.copy(error = appText(TextR.string.text_paste_cookie_content_a_netscape_cookie_file_or_json)) }
            return
        }
        authenticate { importCookies(raw) }
    }

    private fun authenticate(action: suspend () -> AppResult<Session>) {
        if (_state.value.loading || _state.value.switchingDomain) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val error = when (val result = action()) {
                    is AppResult.Success -> null
                    is AppResult.Failure -> result.error.userMessage()
                }
                _state.update { it.copy(error = error) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(error = appText(TextR.string.text_cannot_save_your_session_please_retry)) }
            } finally {
                _state.update { it.copy(loading = false, password = "", cookieText = "") }
            }
        }
    }

    fun showDomains() {
        if (!_state.value.loading) _state.update { it.copy(showDomainPicker = true, domainError = null) }
    }

    fun dismissDomains() {
        if (!_state.value.switchingDomain) {
            _state.update { it.copy(showDomainPicker = false, domainError = null, pendingMirrorHost = null) }
        }
    }

    fun selectDomain(host: String) {
        if (_state.value.loading || _state.value.switchingDomain || _state.value.pendingMirrorHost != null) return
        if (domainRepository.requiresApproval(host)) {
            requestCommunityMirror(host)
            return
        }
        switchDomain {
            if (requiresVerification(host) && domainRepository.isApprovedHost(host)) {
                _verificationRequests.send(host)
                AppResult.Success(host)
            } else {
                selectionResult(host, domainRepository.setCurrentHost(host))
            }
        }
    }

    fun probeDomain(host: String) {
        if (_state.value.loading || _state.value.switchingDomain) return
        _state.update { it.copy(switchingDomain = true, domainError = null) }
        viewModelScope.launch {
            try {
                domainRepository.probeHost(host)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(domainError = appText(TextR.string.text_domain_action_failed_please_retry)) }
            } finally {
                _state.update { it.copy(switchingDomain = false) }
            }
        }
    }

    private fun requiresVerification(host: String): Boolean = domainRepository.checks.value.any {
        it.host == host && (it.status == DomainStatus.DIAMWALL || it.status == DomainStatus.CHALLENGED)
    }

    private suspend fun selectionResult(host: String, result: AppResult<String>): AppResult<String> {
        if (domainRepository.isApprovedHost(host) && requiresVerification(host)) {
            _verificationRequests.send(host)
            return AppResult.Success(host)
        }
        return result
    }

    private fun switchDomain(action: suspend () -> AppResult<String>) {
        if (_state.value.loading || _state.value.switchingDomain) return
        _state.update { it.copy(switchingDomain = true, domainError = null, pendingMirrorHost = null) }
        viewModelScope.launch {
            try {
                when (val result = action()) {
                    is AppResult.Success -> _state.update { it.copy(showDomainPicker = false, error = null) }
                    is AppResult.Failure -> _state.update { it.copy(domainError = result.error.userMessage()) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                _state.update { it.copy(domainError = appText(TextR.string.text_cannot_switch_domains_please_retry)) }
            } finally {
                _state.update { it.copy(switchingDomain = false) }
            }
        }
    }
}
