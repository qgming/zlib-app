package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val domainRepository: DomainRepository,
    private val downloadRepository: DownloadRepository,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val directory = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            directory.value = downloadRepository.downloadDirectoryName()
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

    fun probeAll() {
        viewModelScope.launch { domainRepository.probeAll(switchToBest = true) }
    }

    fun addCustomHost(raw: String) {
        viewModelScope.launch {
            when (val result = domainRepository.addCustomHost(raw)) {
                is AppResult.Success -> {
                    _events.send("已添加 ${result.value}")
                }

                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }

    fun removeCustomHost(host: String) {
        viewModelScope.launch {
            when (val result = domainRepository.removeCustomHost(host)) {
                is AppResult.Success -> _events.send("已移除 $host")
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }

    fun selectHost(host: String) {
        viewModelScope.launch {
            when (val result = domainRepository.setCurrentHost(host)) {
                is AppResult.Success -> _events.send("已切换到 ${result.value}")
                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }

    fun setLocked(enabled: Boolean) {
        viewModelScope.launch {
            val host = if (enabled) state.value.currentHost else null
            if (enabled && host == null) {
                _events.send("请先选择一个可用域名")
                return@launch
            }
            val result = domainRepository.setLockedHost(host)
            if (result is AppResult.Failure) _events.send(result.error.userMessage())
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
                    _events.send("下载目录已保存")
                }

                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
