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
import com.qgming.zlib.core.model.DomainGroup
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainProbe
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
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

    fun currentHostOrNull(): String? = _currentHost.value?.takeIf(registry::isAllowed)

    fun isHealthy(host: String): Boolean =
        _checks.value.any { it.host == host && it.status == DomainStatus.HEALTHY }

    fun isApprovedHost(host: String): Boolean =
        DomainPolicy.normalizeHostInput(host) == host && registry.isAllowed(host)

    fun requiresApproval(host: String): Boolean = DomainPolicy.normalizeHostInput(host)?.let { normalized ->
        DomainPolicy.isCommunityMirror(normalized) && !registry.isAllowed(normalized)
    } == true

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
                    applyHost(normalized, lockHost = _settings.value.lockedHost)
                }
                publishChecks()
                if (_currentHost.value == null) {
                    probeInternal(switchToBest = true)
                }
                initialized = true
            }
        }
    }

    suspend fun probeAll(switchToBest: Boolean = true, includeCommunity: Boolean = false): List<DomainCheck> {
        mutex.withLock {
            return withContext(dispatchers.io) { probeInternal(switchToBest, includeCommunity) }
        }
    }

    suspend fun probeHost(host: String): DomainCheck = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock DomainCheck(host = host, status = DomainStatus.INVALID, detail = appText(TextR.string.text_invalid_domain_format))
        if (!registry.isProbeAllowed(normalized)) {
            return@withLock DomainCheck(normalized, DomainStatus.INVALID, appText(TextR.string.text_domain_is_not_on_the_allowed_list))
        }
        probeHostLocked(normalized)
    }

    suspend fun setCurrentHost(host: String): AppResult<String> = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_domain_format)))
        if (!registry.isAllowed(normalized)) {
            return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_domain_is_not_on_the_allowed_list)))
        }
        selectApprovedHost(normalized)
    }

    suspend fun selectVerifiedHost(host: String): AppResult<String> = mutex.withLock {
        if (!isApprovedHost(host)) {
            return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_domain_is_not_on_the_allowed_list)))
        }
        val check = probeHostLocked(host)
        if (!check.healthy || (check.finalHost ?: check.host) != host) {
            return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_domain_verification_unavailable)))
        }
        applyHost(host, lockHost = host)
        AppResult.Success(host)
    }

    suspend fun approveCommunityHost(host: String): AppResult<String> = mutex.withLock {
        approveCommunityHostLocked(host)
    }

    suspend fun approveAndSelectHost(host: String): AppResult<String> = mutex.withLock {
        when (val approval = approveCommunityHostLocked(host)) {
            is AppResult.Failure -> approval
            is AppResult.Success -> {
                probeHostLocked(approval.value)
                selectApprovedHost(approval.value)
            }
        }
    }

    suspend fun addCustomHost(raw: String, select: Boolean = false): AppResult<String> = mutex.withLock {
        val host = when (val validation = DomainPolicy.validateCustomHost(raw)) {
            is AppResult.Failure -> return@withLock validation
            is AppResult.Success -> validation.value
        }
        persistCustomHosts(currentSettings().customHosts + host)
        val check = probeHostLocked(host)

        if (select) {
            return@withLock selectApprovedHost(host)
        }
        val locked = _settings.value.lockedHost
        if ((locked == null && !isCurrentlyUsable()) || locked == host && currentHostOrNull() == null) {
            usableHostOf(check)?.let { applyHost(it, lockHost = locked) }
        }
        AppResult.Success(host)
    }

    suspend fun removeCustomHost(host: String): AppResult<Unit> = mutex.withLock {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return@withLock AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_domain_format)))
        if (normalized !in currentSettings().customHosts) return@withLock AppResult.Success(Unit)
        persistCustomHosts(currentSettings().customHosts - normalized)
        if (_settings.value.lockedHost == normalized) {
            settingsStore.setLockedHost(null)
            _settings.value = currentSettings().copy(lockedHost = null)
        }
        syncProbeCandidates()
        if (_currentHost.value == normalized) {
            _currentHost.value = null
            settingsStore.setCurrentHost(null)
            _settings.value = currentSettings().copy(currentHost = null)
            publishChecks()
            probeInternal(switchToBest = true)
        } else {
            publishChecks()
        }
        AppResult.Success(Unit)
    }

    suspend fun setAutoSwitchEnabled(enabled: Boolean): AppResult<Unit> = mutex.withLock {
        val lockedHost = if (enabled) null else (
            _currentHost.value ?: return@withLock AppResult.Failure(AppError.NoDomain)
        )
        settingsStore.setLockedHost(lockedHost)
        _settings.value = currentSettings().copy(lockedHost = lockedHost)
        syncProbeCandidates()
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
        includeCommunity: Boolean = false,
    ): List<DomainCheck> {
        val settings = currentSettings()
        val hosts = probeHosts(settings, includeCommunity)
        val previous = _checks.value
        publishChecks(hosts.map { DomainCheck(it, DomainStatus.PROBING) }, previous)
        _probing.value = true
        try {
            val results = probe.probeAll(hosts, concurrency = 8)
            val discovered = discoveryTargets(results)
            val responders = results.filter { it.healthy }.map { it.finalHost ?: it.host }.toSet()
            val retained = settings.discoveredHosts.filter { host ->
                host !in hosts || host in responders || host == settings.lockedHost || host == settings.currentHost
            }
            persistDiscovered((retained + discovered).toSet())

            val annotated = annotate(results, results.mapNotNull { it.redirectTarget }.toSet())
            publishChecks(annotated, previous)

            if (switchToBest) applyBestHost(annotated)
            return annotated
        } catch (failure: Exception) {
            _checks.value = previous
            throw failure
        } finally {
            _probing.value = false
        }

    }

    private suspend fun applyBestHost(checks: List<DomainCheck>) {
        val locked = currentSettings().lockedHost
        if (locked != null) {
            val target = checks.firstOrNull { it.host == locked }?.let(::usableHostOf)
            if (target != null && registry.isAllowed(locked)) applyHost(target, lockHost = locked)
            return
        }

        val usable = checks.mapNotNull(::usableHostOf)
        val current = currentHostOrNull()
        if (current != null && current in usable) return
        usable.firstOrNull()?.let { applyHost(it) }
    }

    private fun usableHostOf(check: DomainCheck): String? = check.takeIf { it.healthy }
        ?.let { it.finalHost ?: it.host }?.takeIf(registry::isAllowed)

    private suspend fun selectApprovedHost(host: String): AppResult<String> {
        val check = _checks.value.firstOrNull { it.host == host }
        val status = check?.status
        if (status == DomainStatus.DIAMWALL || status == DomainStatus.CHALLENGED) {
            return AppResult.Failure(AppError.DomainBlocked(status))
        }
        val resolved = check?.let(::usableHostOf) ?: host
        applyHost(resolved, lockHost = host)
        return AppResult.Success(resolved)
    }

    private fun isCurrentlyUsable(): Boolean {
        val current = _currentHost.value ?: return false
        return _checks.value.any { usableHostOf(it) == current }
    }

    private fun discoveryTargets(checks: List<DomainCheck>): Set<String> = checks
        .mapNotNull { it.redirectTarget }.filterNot(registry::isAllowed).toSet()

    private fun annotate(checks: List<DomainCheck>, targets: Set<String>): List<DomainCheck> =
        (checks + targets.filterNot { target -> checks.any { it.host == target } }.map { target ->
            DomainCheck(target, DomainStatus.HEALTHY, appText(TextR.string.text_discovered_through_a_redirect), finalHost = target)
        }).map(::groupCheck)

    private suspend fun approveCommunityHostLocked(raw: String): AppResult<String> {
        val host = DomainPolicy.normalizeHostInput(raw)
            ?: return AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_domain_format)))
        if (!DomainPolicy.isCommunityMirror(host)) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_domain_is_not_on_the_allowed_list)))
        }
        if (!registry.isAllowed(host)) persistCustomHosts(currentSettings().customHosts + host)
        publishChecks()
        return AppResult.Success(host)
    }

    private suspend fun persistCustomHosts(hosts: Set<String>) {
        if (hosts == currentSettings().customHosts) return
        withContext(NonCancellable) {
            settingsStore.setCustomHosts(hosts)
            registry.updateCustomHosts(hosts)
            _settings.value = currentSettings().copy(customHosts = hosts)
        }
    }

    private suspend fun persistDiscovered(hosts: Set<String>) {
        if (hosts != currentSettings().discoveredHosts) {
            settingsStore.setDiscoveredHosts(hosts)
            _settings.value = currentSettings().copy(discoveredHosts = hosts)
            syncProbeCandidates()
        }
    }

    private suspend fun applyHost(host: String, lockHost: String? = host) {
        if (!registry.isAllowed(host)) return
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
        if (_checks.value.none { it.host == host }) publishChecks(listOf(DomainCheck.unknown(host)))
        settingsStore.setCurrentHost(host)
        _settings.value = currentSettings().copy(currentHost = host)
        // Manual selection follows the lock so a later probe cannot undo it. A redirect target keeps
        // the lock on the redirector the user actually picked (ADR-065).
        if (lockNeedsUpdate && lockHost != null) {
            settingsStore.setLockedHost(lockHost)
            _settings.value = currentSettings().copy(lockedHost = lockHost)
            syncProbeCandidates()
        }
        if (session != null && session.domain != host) {
            sessionManager.updateDomain(session, host)
        }
    }

    private fun currentSettings(): AppSettings = _settings.value

    private fun applySettings(settings: AppSettings) {
        val normalizedHosts = settings.customHosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
        registry.updateCustomHosts(normalizedHosts)
        _settings.value = settings.copy(
            customHosts = normalizedHosts,
            discoveredHosts = settings.discoveredHosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet(),
            currentHost = DomainPolicy.normalizeHostInput(settings.currentHost) ?: settings.currentHost,
            lockedHost = DomainPolicy.normalizeHostInput(settings.lockedHost) ?: settings.lockedHost,
        )
        syncProbeCandidates()
    }

    private fun syncProbeCandidates() {
        val settings = currentSettings()
        registry.updateProbeCandidates(settings.discoveredHosts + listOfNotNull(settings.lockedHost))
    }

    private fun probeHosts(settings: AppSettings, includeCommunity: Boolean): List<String> {
        val candidates = (
            DomainPolicy.DEFAULT_PROBE_HOSTS +
                DomainPolicy.COMMUNITY_MIRROR_HOSTS +
                settings.customHosts +
                settings.discoveredHosts +
                listOfNotNull(_currentHost.value, settings.currentHost, settings.lockedHost, sessionManager.current()?.domain)
            )
            .mapNotNull(DomainPolicy::normalizeHostInput)
            .distinct()
        return candidates.filter(if (includeCommunity) registry::isProbeAllowed else registry::isAllowed)
    }

    private fun publishChecks(updated: List<DomainCheck> = emptyList(), previous: List<DomainCheck> = _checks.value) {
        val known = (previous + updated).associateBy { it.host }
        val hosts = probeHosts(currentSettings(), includeCommunity = true) + updated.map { it.host }
        _checks.value = hosts.distinct().sortedBy(::hostOrder).map { host ->
            groupCheck(known[host] ?: DomainCheck.unknown(host))
        }
    }

    private fun groupCheck(check: DomainCheck): DomainCheck = check.copy(
        group = if (DomainPolicy.isBuiltIn(check.host)) DomainGroup.OFFICIAL else DomainGroup.COMMUNITY,
    )

    private suspend fun probeHostLocked(host: String): DomainCheck {
        val previous = _checks.value
        publishChecks(listOf(DomainCheck(host, DomainStatus.PROBING)), previous)
        _probing.value = true
        return try {
            val check = groupCheck(withContext(dispatchers.io) { probe.probe(host) })
            val targets = discoveryTargets(listOf(check))
            if (targets.isNotEmpty()) persistDiscovered(currentSettings().discoveredHosts + targets)
            publishChecks(annotate(listOf(check), setOfNotNull(check.redirectTarget)), previous)
            check
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
        val community = DomainPolicy.COMMUNITY_MIRROR_HOSTS
        val communityIndex = community.indexOf(host)
        if (communityIndex >= 0) return defaults.size + communityIndex
        val customOffset = defaults.size + community.size
        val customIndex = registry.customHosts().sorted().indexOf(host)
        if (customIndex >= 0) return customOffset + customIndex
        return Int.MAX_VALUE
    }
}
