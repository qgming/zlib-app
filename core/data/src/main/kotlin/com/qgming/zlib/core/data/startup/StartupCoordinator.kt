package com.qgming.zlib.core.data.startup

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
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
    data object Ready : StartupState
    data class Failed(val error: AppError) : StartupState
}

/**
 * One-shot launch sequence: decrypt session → load settings → pick/probe domain →
 * refresh the profile. Never blocks the first frame longer than necessary.
 */
@Singleton
class StartupCoordinator @Inject constructor(
    private val sessionManager: SessionManager,
    private val domainRepository: DomainRepository,
    private val accountRepository: AccountRepository,
    private val dispatchers: AppDispatchers,
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
                domainRepository.initialize()
                sessionManager.load()

                if (domainRepository.currentHostOrNull() == null) {
                    _state.value = StartupState.Failed(AppError.NoDomain)
                    return@withContext
                }

                if (sessionManager.current() != null) {
                    accountRepository.refresh()
                }
                completed = true
                _state.value = StartupState.Ready
            }
        }
    }

    suspend fun retry() {
        completed = false
        withContext(dispatchers.io) {
            domainRepository.probeAll(switchToBest = true)
        }
        start()
    }

    /**
     * Background health check once the shell is visible. Does not block startup and keeps the
     * current domain when it is still healthy (ADR-013).
     */
    suspend fun probeInBackground() {
        withContext(dispatchers.io) {
            domainRepository.probeAll(switchToBest = true)
        }
    }
}
