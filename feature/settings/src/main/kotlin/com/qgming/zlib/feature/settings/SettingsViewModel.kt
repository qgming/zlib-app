package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.DomainCheck
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
    val glassEnabled: Boolean = true,
    val dynamicColor: Boolean = true,
    val directory: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val domainRepository: DomainRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    private val directory = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            downloadRepository.directoryStore.ensureLoaded()
            directory.value = downloadRepository.directoryStore.currentUri()?.toString()
        }
    }

    val state: StateFlow<SettingsUiState> = combine(
        domainRepository.checks,
        domainRepository.currentHost,
        domainRepository.probing,
        domainRepository.settings,
        directory,
    ) { checks, current, probing, settings, dir ->
        SettingsUiState(
            checks = checks,
            currentHost = current,
            probing = probing,
            customHosts = settings.customHosts,
            lockedHost = settings.lockedHost,
            glassEnabled = settings.glassEnabled,
            dynamicColor = settings.dynamicColor,
            directory = dir,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

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

    fun setGlassEnabled(enabled: Boolean) {
        viewModelScope.launch { domainRepository.setGlassEnabled(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { domainRepository.setDynamicColor(enabled) }
    }

    fun setDownloadDirectory(uri: android.net.Uri) {
        viewModelScope.launch {
            when (val result = downloadRepository.setDownloadDirectory(uri)) {
                is AppResult.Success -> {
                    directory.value = uri.toString()
                    _events.send("下载目录已保存")
                }

                is AppResult.Failure -> _events.send(result.error.userMessage())
            }
        }
    }
}
