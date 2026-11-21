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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val startupCoordinator: StartupCoordinator,
    private val authRepository: AuthRepository,
    private val domainRepository: DomainRepository,
    private val downloadRepository: DownloadRepository,
) : ViewModel() {

    val startupState: StateFlow<StartupState> = startupCoordinator.state
    val session: StateFlow<Session?> = authRepository.session

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val glassEnabled: StateFlow<Boolean> = domainRepository.settings
        .map { it.glassEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            startupCoordinator.start()
            if (startupCoordinator.state.value is StartupState.Ready) {
                launch { startupCoordinator.probeInBackground() }
                launch { downloadRepository.resumePendingIfAny() }
            }
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
