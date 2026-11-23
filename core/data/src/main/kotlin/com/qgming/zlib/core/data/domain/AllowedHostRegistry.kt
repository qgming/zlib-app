package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.network.DomainPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime allowlist that adds user mirrors and verified redirect targets. The OkHttp
 * credential-stripping interceptor and the redirect guard consult this, so an accepted redirect
 * target keeps working without weakening the static allowlist (ADR-065).
 *
 * Discovered hosts are exact-host only and live in memory: a fresh process must verify them again
 * with the credential-free EAPI handshake before any cookie is sent.
 */
@Singleton
class AllowedHostRegistry @Inject constructor() {

    @Volatile
    private var customHosts: Set<String> = emptySet()

    @Volatile
    private var discoveredHosts: Set<String> = emptySet()

    /** Hosts that may be probed (credential-free) but are not yet trusted with credentials. */
    @Volatile
    private var probeCandidates: Set<String> = emptySet()

    fun updateCustomHosts(hosts: Set<String>) {
        customHosts = hosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
    }

    fun customHosts(): Set<String> = customHosts

    /** Registers a redirect target that just passed the credential-free handshake in this session. */
    fun addDiscoveredHost(host: String) {
        val normalized = DomainPolicy.normalizeHostInput(host) ?: return
        if (DomainPolicy.isBuiltIn(normalized) || normalized in customHosts) return
        discoveredHosts = discoveredHosts + normalized
    }

    fun removeDiscoveredHosts(hosts: Collection<String>) {
        if (hosts.isEmpty()) return
        val removed = hosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
        discoveredHosts = discoveredHosts - removed
    }

    fun discoveredHosts(): Set<String> = discoveredHosts

    /**
     * Hosts the prober may contact. Never used by the credential-stripping interceptor or the
     * redirect guard, so an unverified redirect target can be checked without ever seeing a cookie.
     */
    fun updateProbeCandidates(hosts: Set<String>) {
        probeCandidates = hosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
    }

    fun isProbeAllowed(host: String): Boolean =
        isAllowed(host) || probeCandidates.any { it.equals(host, ignoreCase = true) }

    fun isDiscovered(host: String): Boolean =
        discoveredHosts.any { it.equals(host, ignoreCase = true) }

    fun isAllowed(host: String): Boolean =
        DomainPolicy.isAllowed(host, customHosts + discoveredHosts)
}
