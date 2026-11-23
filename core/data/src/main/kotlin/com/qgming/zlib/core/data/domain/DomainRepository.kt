package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

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
import kotlinx.coroutines.flow.update
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
                populateChecks()
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
            ?: return@withLock DomainCheck(host = host, status = DomainStatus.INVALID, detail = appText(TextR.string.text_invalid_domain_format))
        if (!registry.isAllowed(normalized)) {
            return@withLock DomainCheck(normalized, DomainStatus.INVALID, appText(TextR.string.text_domain_is_not_on_the_allowed_list))
        }
        val check = checkHost(normalized)
        check.redirectTarget?.let { adoptDiscovered(it) }
        val updated = _checks.value.filterNot { it.host == normalized } + check
        _checks.value = updated.sortedBy { hostOrder(it.host) }
        check
    }

    suspend fun setCurrentHost(host: String): AppResult<String> = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_domain_format)))
        if (!registry.isAllowed(normalized)) {
            return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_domain_is_not_on_the_allowed_list)))
        }
        val resolved = resolveUsable(normalized)
        applyHost(resolved, lockHost = normalized)
        AppResult.Success(resolved)
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
        syncProbeCandidates()

        val check = checkHost(host)
        check.redirectTarget?.let { adoptDiscovered(it) }
        _checks.value = (_checks.value.filterNot { it.host == host } + check).sortedBy { hostOrder(it.host) }

        if (check.healthy &&
            _settings.value.lockedHost == null &&
            !isCurrentlyUsable()
        ) {
            applyHost(resolveUsable(host))
        }
        AppResult.Success(host)
    }

    suspend fun removeCustomHost(host: String): AppResult<Unit> = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_domain_format)))
        val hosts = currentSettings().customHosts - normalized
        settingsStore.setCustomHosts(hosts)
        registry.updateCustomHosts(hosts)
        _settings.value = currentSettings().copy(customHosts = hosts)
        syncProbeCandidates()
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

    suspend fun setAutoSwitchEnabled(enabled: Boolean): AppResult<Unit> = mutex.withLock {
        val lockedHost = if (enabled) null else (
            _currentHost.value ?: return@withLock AppResult.Failure(AppError.NoDomain)
        )
        settingsStore.setLockedHost(lockedHost)
        _settings.value = currentSettings().copy(lockedHost = lockedHost)
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
        val previous = _checks.value
        _checks.value = hosts.map { DomainCheck(it, DomainStatus.PROBING) }
        _probing.value = true
        val results = try {
            probe.probeAll(hosts, concurrency = 8)
        } catch (failure: Exception) {
            _checks.value = previous
            throw failure
        } finally {
            _probing.value = false
        }

        // A redirector is usable only through the target that passed the credential-free handshake.
        val redirectTargets = results.mapNotNull { it.redirectTarget }.distinct()
        // Stored targets are probed credential-free first; only a healthy answer re-trusts them.
        val revalidated = results.filter { it.healthy && it.host in settings.discoveredHosts }.map { it.host }
        (redirectTargets + revalidated).distinct().forEach(registry::addDiscoveredHost)
        persistDiscovered(results, redirectTargets)

        val annotated = results + redirectTargets
            .filterNot { target -> results.any { it.host == target } }
            .map { target -> DomainCheck(target, DomainStatus.HEALTHY, appText(TextR.string.text_discovered_through_a_redirect)) }
        _checks.value = annotated.sortedBy { hostOrder(it.host) }

        if (switchToBest) {
            val usable = annotated.mapNotNull(::usableHostOf).distinct()
            val locked = _settings.value.lockedHost
            if (locked != null) {
                // An explicit lock wins over automatic failover (ADR-013). The lock stays on the host
                // the user picked; a redirector resolves to its verified target (ADR-065).
                val target = annotated.firstOrNull { it.host == locked }?.let(::usableHostOf)
                val next = target ?: if (_currentHost.value == null) locked else null
                if (next != null && next != _currentHost.value) applyHost(next, lockHost = locked)
                return annotated
            }

            val current = _currentHost.value
            val best = when {
                current != null && usable.contains(current) -> current
                else -> usable.firstOrNull()
            }
            if (best != null) applyHost(best)
        }
        return annotated
    }

    /** Host that can actually serve EAPI: the verified redirect target, else the host itself. */
    private fun usableHostOf(check: DomainCheck): String? = when {
        !check.healthy -> null
        check.redirectTarget != null -> check.redirectTarget
        else -> check.host
    }

    private fun resolveUsable(host: String): String {
        val check = _checks.value.firstOrNull { it.host == host && it.healthy } ?: return host
        return check.redirectTarget ?: host
    }

    private fun isCurrentlyUsable(): Boolean {
        val current = _currentHost.value ?: return false
        return _checks.value.any { usableHostOf(it) == current }
    }

    private suspend fun adoptDiscovered(host: String) {
        registry.addDiscoveredHost(host)
        val stored = currentSettings().discoveredHosts
        if (host in stored) return
        val merged = stored + host
        settingsStore.setDiscoveredHosts(merged)
        _settings.value = currentSettings().copy(discoveredHosts = merged)
        syncProbeCandidates()
    }

    /**
     * Keeps the verified targets for the next probe round; targets that stop answering are dropped
     * unless they are the current or the locked host (so the user keeps seeing the failure).
     */
    private suspend fun persistDiscovered(results: List<DomainCheck>, redirectTargets: List<String>) {
        val stored = currentSettings().discoveredHosts
        if (stored.isEmpty() && redirectTargets.isEmpty()) return
        val healthyNow = results.filter { it.healthy }
            .flatMap { listOfNotNull(it.host, it.redirectTarget) }
            .toSet()
        val keep = stored.filter {
            it in healthyNow || it == _settings.value.lockedHost || it == _currentHost.value
        }
        val merged = (keep + redirectTargets).toSet()
        registry.removeDiscoveredHosts(stored - keep)
        if (merged != stored) {
            settingsStore.setDiscoveredHosts(merged)
            _settings.value = currentSettings().copy(discoveredHosts = merged)
            syncProbeCandidates()
        }
    }

    private suspend fun applyHost(host: String, lockHost: String? = host) {
        val session = sessionManager.current()
        val lockNeedsUpdate = _settings.value.lockedHost != null && _settings.value.lockedHost != lockHost
        if (_currentHost.value == host &&
            _settings.value.currentHost == host &&
            session?.domain == host &&
            !lockNeedsUpdate
        ) {
            return
        }

        if (_currentHost.value != host) _currentHost.value = host
        if (_checks.value.none { it.host == host }) {
            _checks.update { (it + DomainCheck.unknown(host)).sortedBy { check -> hostOrder(check.host) } }
        }
        settingsStore.setCurrentHost(host)
        _settings.value = currentSettings().copy(currentHost = host)
        // Manual selection follows the lock so a later probe cannot undo it. A redirect target keeps
        // the lock on the redirector the user actually picked (ADR-065).
        if (lockNeedsUpdate && lockHost != null) {
            settingsStore.setLockedHost(lockHost)
            _settings.value = currentSettings().copy(lockedHost = lockHost)
        }
        if (session != null && session.domain != host) {
            sessionManager.updateDomain(session, host)
        }
    }

    private fun currentSettings(): AppSettings = _settings.value

    private fun applySettings(settings: AppSettings) {
        _settings.value = settings
        registry.updateCustomHosts(settings.customHosts)
        syncProbeCandidates()
    }

    private fun syncProbeCandidates() {
        val settings = currentSettings()
        registry.updateProbeCandidates(settings.customHosts + settings.discoveredHosts)
    }

    private fun probeHosts(settings: AppSettings): List<String> {
        val remembered = _settings.value.currentHost
        val candidates = (
            DomainPolicy.DEFAULT_PROBE_HOSTS +
                settings.customHosts +
                settings.discoveredHosts +
                listOfNotNull(_currentHost.value, remembered, sessionManager.current()?.domain)
            )
            .mapNotNull(DomainPolicy::normalizeHostInput)
            .distinct()
        // Stored redirect targets stay probeable before they are trusted again in this session.
        return candidates.filter { registry.isAllowed(it) || it in settings.discoveredHosts }
    }

    private fun populateChecks() {
        val known = _checks.value.associateBy { it.host }
        _checks.value = probeHosts(currentSettings()).map { known[it] ?: DomainCheck.unknown(it) }
    }

    private suspend fun checkHost(host: String): DomainCheck {
        val previous = _checks.value
        _checks.value = (previous.filterNot { it.host == host } + DomainCheck(host, DomainStatus.PROBING))
            .sortedBy { hostOrder(it.host) }
        _probing.value = true
        return try {
            withContext(dispatchers.io) { probe.probe(host) }
        } catch (failure: Exception) {
            _checks.value = previous
            throw failure
        } finally {
            _probing.value = false
        }
    }

    private fun hostOrder(host: String): Int {
        val defaults = DomainPolicy.DEFAULT_PROBE_HOSTS
        val index = defaults.indexOf(host)
        if (index >= 0) return index
        // User mirrors and auto-discovered targets follow the built-in order.
        val customIndex = registry.customHosts().sorted().indexOf(host)
        if (customIndex >= 0) return defaults.size + customIndex
        return defaults.size + 100
    }
}
