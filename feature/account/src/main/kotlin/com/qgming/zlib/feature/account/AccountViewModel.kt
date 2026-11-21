package com.qgming.zlib.feature.account

import android.content.Context
import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.DomainCheck
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val account: Account? = null,
    val session: Session? = null,
    val currentHost: String? = null,
    val checks: List<DomainCheck> = emptyList(),
    val probing: Boolean = false,
    val refreshing: Boolean = false,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository,
    private val domainRepository: DomainRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val refreshing = MutableStateFlow(false)

    private val domainState = combine(
        domainRepository.currentHost,
        domainRepository.checks,
        domainRepository.probing,
    ) { host, checks, probing -> Triple(host, checks, probing) }

    val state: StateFlow<AccountUiState> = combine(
        accountRepository.account,
        authRepository.session,
        refreshing,
        domainState,
    ) { account, session, isRefreshing, domain ->
        AccountUiState(
            account = account,
            session = session,
            currentHost = domain.first,
            checks = domain.second,
            probing = domain.third,
            refreshing = isRefreshing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                when (val result = accountRepository.refresh()) {
                    is AppResult.Failure -> _events.send(result.error.userMessage())
                    is AppResult.Success -> Unit
                }
            } finally {
                refreshing.value = false
            }
        }
    }

    fun probeDomains() {
        viewModelScope.launch {
            domainRepository.probeAll(switchToBest = true)
        }
    }

    fun switchDomain(host: String) {
        viewModelScope.launch {
            val result = domainRepository.setCurrentHost(host)
            if (result is AppResult.Failure) _events.send(result.error.userMessage())
        }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }
            authRepository.logout()
            accountRepository.clear()
        }
    }
}
