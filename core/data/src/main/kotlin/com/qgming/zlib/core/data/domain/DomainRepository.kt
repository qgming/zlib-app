package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.model.DomainCheck
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainProbe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the current domain and the probe results. Switching preserves cookies (ADR-013);
 * the session's domain is rewritten in place while credentials stay untouched.
 */
@Singleton
class DomainRepository @Inject constructor(
    private val probe: DomainProbe,
    private val settingsStore: SettingsStore,
    private val sessionManager: SessionManager,
    private val registry: AllowedHostRegistry,
    private val dispatchers: AppDispatchers,
) {
    private val _checks = MutableStateFlow<List<DomainCheck>>(emptyList())
    val checks: StateFlow<List<DomainCheck>> = _checks.asStateFlow()

    private val _currentHost = MutableStateFlow<String?>(null)
    val currentHost: StateFlow<String?> = _currentHost.asStateFlow()

    private val _probing = MutableStateFlow(false)
    val probing: StateFlow<Boolean> = _probing.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    fun currentHostOrNull(): String? = _currentHost.value

    fun isHealthy(host: String): Boolean =
        _checks.value.any { it.host == host && it.status == DomainStatus.HEALTHY }

    suspend fun initialize() {
        if (initialized) return
        mutex.withLock {
            if (initialized) return
            withContext(dispatchers.io) {
                applySettings(settingsStore.read())
                sessionManager.load()

                val remembered = _settings.value.lockedHost
                    ?: sessionManager.current()?.domain
                    ?: _settings.value.currentHost

                val normalized = remembered?.let(DomainPolicy::normalizeHostInput)
                if (normalized != null && registry.isAllowed(normalized)) {
                    _currentHost.value = normalized
                }
                if (_currentHost.value == null) {
                    probeInternal(switchToBest = true)
                }
                initialized = true
            }
        }
    }

    suspend fun probeAll(switchToBest: Boolean = true): List<DomainCheck> {
        mutex.withLock {
            val settings = currentSettings()
            return withContext(dispatchers.io) { probeInternal(switchToBest, settings) }
        }
    }

    suspend fun probeHost(host: String): DomainCheck = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock DomainCheck(host = host, status = DomainStatus.INVALID, detail = "域名格式无效")
        val check = withContext(dispatchers.io) { probe.probe(normalized) }
        val updated = _checks.value.filterNot { it.host == normalized } + check
        _checks.value = updated.sortedBy { hostOrder(it.host) }
        check
    }

    suspend fun setCurrentHost(host: String): AppResult<String> = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock AppResult.Failure(AppError.Parse("域名格式无效"))
        if (!registry.isAllowed(normalized)) {
            return@withLock AppResult.Failure(AppError.Parse("域名不在允许列表"))
        }
        applyHost(normalized)
        AppResult.Success(normalized)
    }

    suspend fun addCustomHost(raw: String): AppResult<String> = mutex.withLock {
        val host = when (val validation = DomainPolicy.validateCustomHost(raw)) {
            is AppResult.Failure -> return@withLock validation
            is AppResult.Success -> validation.value
        }
        val hosts = currentSettings().customHosts + host
        settingsStore.setCustomHosts(hosts)
        registry.updateCustomHosts(hosts)
        _settings.value = currentSettings().copy(customHosts = hosts)

        val check = withContext(dispatchers.io) { probe.probe(host) }
        _checks.value = (_checks.value.filterNot { it.host == host } + check).sortedBy { hostOrder(it.host) }

        if (check.status == DomainStatus.HEALTHY &&
            _settings.value.lockedHost == null &&
            (_currentHost.value == null || !isHealthy(_currentHost.value!!))
        ) {
            applyHost(host)
        }
        AppResult.Success(host)
    }

    suspend fun removeCustomHost(host: String): AppResult<Unit> = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock AppResult.Failure(AppError.Parse("域名格式无效"))
        val hosts = currentSettings().customHosts - normalized
        settingsStore.setCustomHosts(hosts)
        registry.updateCustomHosts(hosts)
        _settings.value = currentSettings().copy(customHosts = hosts)
        _checks.value = _checks.value.filterNot { it.host == normalized }
        if (_settings.value.lockedHost == normalized) {
            settingsStore.setLockedHost(null)
            _settings.value = currentSettings().copy(lockedHost = null)
        }
        if (_currentHost.value == normalized) {
            _currentHost.value = null
            probeInternal(switchToBest = true)
        }
        AppResult.Success(Unit)
    }

    suspend fun setLockedHost(host: String?): AppResult<Unit> = mutex.withLock {
        val normalized = host?.let(DomainPolicy::normalizeHostInput)
        if (host != null && normalized == null) {
            return@withLock AppResult.Failure(AppError.Parse("域名格式无效"))
        }
        if (normalized != null && !registry.isAllowed(normalized)) {
            return@withLock AppResult.Failure(AppError.Parse("域名不在允许列表"))
        }
        settingsStore.setLockedHost(normalized)
        _settings.value = currentSettings().copy(lockedHost = normalized)
        if (normalized != null) applyHost(normalized)
        AppResult.Success(Unit)
    }

    suspend fun setThemeMode(mode: ThemeMode) = mutex.withLock {
        settingsStore.setThemeMode(mode)
        _settings.value = currentSettings().copy(themeMode = mode)
    }

    suspend fun setBookGridColumns(columns: BookGridColumns) = mutex.withLock {
        settingsStore.setBookGridColumns(columns)
        _settings.value = currentSettings().copy(bookGridColumns = columns)
    }

    /** Called after a successful login/cookie import so the session domain becomes current. */
    suspend fun adoptSessionHost(host: String) = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host) ?: return@withLock
        if (!registry.isAllowed(normalized)) return@withLock
        applyHost(normalized)
    }

    private suspend fun probeInternal(
        switchToBest: Boolean,
        settings: AppSettings = currentSettings(),
    ): List<DomainCheck> {
        val hosts = probeHosts(settings)
        _probing.value = true
        val results = try {
            probe.probeAll(hosts, concurrency = 8)
        } finally {
            _probing.value = false
        }
        _checks.value = results.sortedBy { hostOrder(it.host) }
        if (switchToBest) {
            // An explicit lock wins over automatic failover (ADR-013).
            if (_settings.value.lockedHost != null) return results

            val current = _currentHost.value
            val best = when {
                current != null && results.any { it.host == current && it.status == DomainStatus.HEALTHY } -> current
                else -> hosts.firstOrNull { host -> results.any { it.host == host && it.status == DomainStatus.HEALTHY } }
            }
            if (best != null) applyHost(best)
        }
        return results
    }

    private suspend fun applyHost(host: String) {
        val session = sessionManager.current()
        val lockNeedsUpdate = _settings.value.lockedHost != null && _settings.value.lockedHost != host
        if (_currentHost.value == host &&
            _settings.value.currentHost == host &&
            session?.domain == host &&
            !lockNeedsUpdate
        ) {
            return
        }

        if (_currentHost.value != host) _currentHost.value = host
        settingsStore.setCurrentHost(host)
        _settings.value = currentSettings().copy(currentHost = host)
        // Manual selection follows the lock so a later probe cannot undo it.
        if (lockNeedsUpdate) {
            settingsStore.setLockedHost(host)
            _settings.value = currentSettings().copy(lockedHost = host)
        }
        if (session != null && session.domain != host) {
            sessionManager.save(session.withDomain(host))
        }
    }

    private fun currentSettings(): AppSettings = _settings.value

    private fun applySettings(settings: AppSettings) {
        _settings.value = settings
        registry.updateCustomHosts(settings.customHosts)
    }

    private fun probeHosts(settings: AppSettings): List<String> {
        val remembered = _settings.value.currentHost
        return (DomainPolicy.DEFAULT_PROBE_HOSTS + settings.customHosts + listOfNotNull(remembered))
            .mapNotNull(DomainPolicy::normalizeHostInput)
            .filter(registry::isAllowed)
            .distinct()
    }

    private fun hostOrder(host: String): Int {
        val defaults = DomainPolicy.DEFAULT_PROBE_HOSTS
        val index = defaults.indexOf(host)
        return if (index >= 0) index else defaults.size + 100
    }
}
