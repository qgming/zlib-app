package com.qgming.zlib.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.designsystem.userMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URI
import javax.inject.Inject
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
    val probing: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val showCookieImport: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val domainRepository: DomainRepository,
    private val registry: AllowedHostRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(domainRepository.currentHost, domainRepository.probing) { host, probing ->
                host to probing
            }.collect { (host, probing) ->
                _state.update { it.copy(domain = host, probing = probing) }
            }
        }
    }

    fun onEmailChange(value: String) = _state.update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onCookieTextChange(value: String) = _state.update { it.copy(cookieText = value, error = null) }

    fun toggleCookieImport() = _state.update { it.copy(showCookieImport = !it.showCookieImport) }

    fun login() {
        val current = _state.value
        if (current.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.loginWithPassword(current.email, current.password)
            when (result) {
                is com.qgming.zlib.core.common.AppResult.Success -> {
                    _state.update { it.copy(loading = false, password = "", error = null) }
                }

                is com.qgming.zlib.core.common.AppResult.Failure -> {
                    _state.update { it.copy(loading = false, error = result.error.userMessage()) }
                }
            }
        }
    }

    fun importCookieText() {
        val raw = _state.value.cookieText
        if (raw.isBlank()) {
            _state.update { it.copy(error = "请粘贴 Cookie、Netscape 文件内容或 JSON。") }
            return
        }
        importCookies(raw, host = null)
    }

    fun importFromWebView(host: String, rawCookie: String) {
        importCookies(rawCookie, host)
    }

    private fun importCookies(raw: String, host: String?) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.importCookies(raw, host)
            when (result) {
                is com.qgming.zlib.core.common.AppResult.Success -> {
                    _state.update { it.copy(loading = false, error = null) }
                }

                is com.qgming.zlib.core.common.AppResult.Failure -> {
                    _state.update { it.copy(loading = false, error = result.error.userMessage()) }
                }
            }
        }
    }

    fun probeDomains() {
        viewModelScope.launch { domainRepository.probeAll(switchToBest = true) }
    }

    fun isAllowedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) && uri.host != null && registry.isAllowed(uri.host)
    }.getOrDefault(false)
}
