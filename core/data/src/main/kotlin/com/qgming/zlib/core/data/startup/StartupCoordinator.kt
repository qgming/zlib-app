package com.qgming.zlib.core.data.startup

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface StartupState {
    data object Loading : StartupState
    data object DirectoryRequired : StartupState
    data object Ready : StartupState
    data class Failed(val error: AppError) : StartupState
}

/**
 * Gate the first use on local directory selection, then verify a usable domain before entering
 * the shell so Discover/Bookshelf immediately talk to a live host instead of waiting on a blank
 * screen. Remaining mirrors are probed in the background to refine latency-based ranking weights.
 */
@Singleton
class StartupCoordinator @Inject constructor(
    private val sessionManager: SessionManager,
    private val domainRepository: DomainRepository,
    private val accountRepository: AccountRepository,
    private val dispatchers: AppDispatchers,
    private val settingsStore: SettingsStore,
) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Loading)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var completed = false

    suspend fun start() {
        if (completed && _state.value is StartupState.Ready) return
        mutex.withLock {
            if (completed && _state.value is StartupState.Ready) return
            _state.value = StartupState.Loading
            withContext(dispatchers.io) {
                if (settingsStore.read().downloadTreeUri.isNullOrBlank()) {
                    _state.value = StartupState.DirectoryRequired
                    return@withContext
                }
                when (val result = domainRepository.ensureUsableHostForStartup()) {
                    is AppResult.Success -> {
                        completed = true
                        _state.value = StartupState.Ready
                    }
                    is AppResult.Failure -> {
                        _state.value = StartupState.Failed(result.error)
                    }
                }
            }
        }
    }

    suspend fun refreshAccountInBackground() {
        if (sessionManager.current() != null) accountRepository.refresh()
    }

    suspend fun retry() {
        completed = false
        start()
    }

    /**
     * One-shot background probe of mirrors not yet checked at startup. Populates latencies and
     * ranking weights without blocking entry or switching away from the active healthy host.
     */
    suspend fun probeInBackground() {
        withContext(dispatchers.io) {
            domainRepository.probeRemainingForRankingInBackground()
        }
    }
}
