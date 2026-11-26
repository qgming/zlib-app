package com.qgming.zlib.feature.account

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.AccountEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountUiState(val accounts: List<AccountEntry> = emptyList(), val autoSwitch: Boolean = false)
data class AddAccountState(
    val visible: Boolean = false, val email: String = "", val password: String = "", val cookie: String = "",
    val useCookie: Boolean = false, val busy: Boolean = false, val error: String? = null,
)

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val auth: AuthRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    val state = sessions.vault.map { vault -> AccountUiState(
        vault.entries.map { AccountEntry(it.session.remixUserId, it.profile, it.session.remixUserId == vault.activeId, it.expired) },
        vault.autoSwitch,
    ) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState())
    private val _form = MutableStateFlow(AddAccountState())
    val form = _form.asStateFlow()
    private val _busyId = MutableStateFlow<String?>(null)
    val busyId = _busyId.asStateFlow()
    private val _events = Channel<String>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun showAdd() { if (!_form.value.busy) _form.value = AddAccountState(visible = true) }
    fun dismissAdd() { if (!_form.value.busy) _form.value = AddAccountState() }
    fun email(value: String) = _form.update { it.copy(email = value, error = null) }
    fun password(value: String) = _form.update { it.copy(password = value, error = null) }
    fun cookie(value: String) = _form.update { it.copy(cookie = value, error = null) }
    fun toggleCookie() = _form.update { it.copy(useCookie = !it.useCookie, password = "", cookie = "", error = null) }

    fun add() {
        val value = _form.value
        if (value.busy || _busyId.value != null) return
        _form.value = value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = if (value.useCookie) auth.importCookies(value.cookie) else auth.loginWithPassword(value.email, value.password)
                when (result) {
                    is AppResult.Success -> { _form.value = AddAccountState(); _events.send(appText(TextR.string.text_account_added_and_activated)) }
                    is AppResult.Failure -> _form.value = value.copy(busy = false, password = "", cookie = "", error = result.error.userMessage())
                }
            } catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { _form.value = value.copy(busy = false, password = "", cookie = "", error = appText(TextR.string.text_cannot_save_the_account_please_retry)) }
        }
    }

    fun refresh(id: String) = perform(id) {
        when (val result = accounts.refresh(id)) {
            is AppResult.Success -> appText(TextR.string.text_account_refreshed)
            is AppResult.Failure -> result.error.userMessage()
        }
    }
    fun switch(id: String) = perform(id) {
        when (val result = auth.switchAccount(id)) {
            is AppResult.Success -> appText(TextR.string.text_account_switched_paused_downloads_can_be_resumed)
            is AppResult.Failure -> result.error.userMessage()
        }
    }
    fun logout(id: String) = perform(id) { auth.logout(id); appText(TextR.string.text_signed_out_of_this_account) }
    fun setAutoSwitch(enabled: Boolean) { viewModelScope.launch { sessions.setAutoSwitch(enabled) } }

    private fun perform(id: String, action: suspend () -> String) {
        if (_busyId.value != null || _form.value.busy) return
        _busyId.value = id
        viewModelScope.launch {
            try { _events.send(action()) }
            catch (cancellation: CancellationException) { throw cancellation }
            catch (_: Exception) { _events.send(appText(TextR.string.text_action_failed_please_retry)) }
            finally { _busyId.value = null }
        }
    }
}
