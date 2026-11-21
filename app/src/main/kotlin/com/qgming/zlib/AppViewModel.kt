package com.qgming.zlib

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.data.auth.AuthRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.startup.StartupCoordinator
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class AppViewModel @Inject constructor(
    private val startupCoordinator: StartupCoordinator,
    private val authRepository: AuthRepository,
    private val domainRepository: DomainRepository,
) : ViewModel() {

    val startupState: StateFlow<StartupState> = startupCoordinator.state
    val session: StateFlow<Session?> = authRepository.session

    init {
        start()
    }

    fun start() {
        viewModelScope.launch {
            startupCoordinator.start()
            if (startupCoordinator.state.value is StartupState.Ready) {
                launch { startupCoordinator.probeInBackground() }
            }
        }
    }

    fun retry() {
        viewModelScope.launch { startupCoordinator.retry() }
    }

    fun addCustomHost(raw: String) {
        viewModelScope.launch {
            domainRepository.addCustomHost(raw)
            startupCoordinator.retry()
        }
    }
}
