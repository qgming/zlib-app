package com.qgming.zlib.feature.account

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.DomainCheck
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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

    init {
        viewModelScope.launch {
            combine(domainRepository.currentHost, domainRepository.checks) { host, checks ->
                host to checks
            }.collect { (host, checks) ->
                _state.update { it.copy(domain = host, checks = checks) }
            }
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
        if (!_state.value.switchingDomain) _state.update { it.copy(showDomainPicker = false, domainError = null) }
    }

    fun selectDomain(host: String) {
        if (_state.value.loading || _state.value.switchingDomain) return
        _state.update { it.copy(switchingDomain = true, domainError = null) }
        viewModelScope.launch {
            try {
                when (val result = domainRepository.setCurrentHost(host)) {
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
