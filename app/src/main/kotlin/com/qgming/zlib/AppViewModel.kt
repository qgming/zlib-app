package com.qgming.zlib

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.data.startup.StartupCoordinator
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@HiltViewModel
class AppViewModel @Inject constructor(
    private val startupCoordinator: StartupCoordinator,
    private val authRepository: AuthRepository,
    private val domainRepository: DomainRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val startupState: StateFlow<StartupState> = startupCoordinator.state
    val session: StateFlow<Session?> = authRepository.session
    val accounts = authRepository.accounts

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val settings = domainRepository.settings
    private val _choosingDirectory = MutableStateFlow(false)
    val choosingDirectory = _choosingDirectory.asStateFlow()

    init {
        viewModelScope.launch {
            startupState.map { it is StartupState.Ready }.distinctUntilChanged().collectLatest { ready ->
                if (ready) {
                    launch { startupCoordinator.probeInBackground() }
                    launch { startupCoordinator.refreshAccountInBackground() }
                    if (authRepository.session.value != null) launch { downloadRepository.resumePendingIfAny() }
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
        viewModelScope.launch { startupCoordinator.retry() }
    }

    fun addCustomHost(raw: String) {
        viewModelScope.launch {
            when (val result = domainRepository.addCustomHost(raw)) {
                is com.qgming.zlib.core.common.AppResult.Success -> {
                    _message.value = null
                    startupCoordinator.retry()
                }

                is com.qgming.zlib.core.common.AppResult.Failure -> {
                    _message.value = result.error.userMessage()
                }
            }
        }
    }
}
