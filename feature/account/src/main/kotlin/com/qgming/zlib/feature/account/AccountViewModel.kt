package com.qgming.zlib.feature.account

import android.webkit.CookieManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Account
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val account: Account? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private data class RequestState(val loading: Boolean = false, val error: String? = null)

    private val requestState = MutableStateFlow(RequestState())

    val state: StateFlow<AccountUiState> = combine(
        accountRepository.account,
        requestState,
    ) { account, request ->
        AccountUiState(
            account = account,
            loading = request.loading,
            error = request.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())

    init {
        refresh()
    }

    fun refresh() {
        if (requestState.value.loading) return
        viewModelScope.launch {
            requestState.value = RequestState(loading = true)
            requestState.value = try {
                when (val result = accountRepository.refresh()) {
                    is AppResult.Failure -> RequestState(error = result.error.userMessage())
                    is AppResult.Success -> RequestState()
                }
            } finally {
                requestState.value = requestState.value.copy(loading = false)
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeAllCookies { cookieManager.flush() }
            }
            authRepository.logout()
            accountRepository.clear()
        }
    }
}
