package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.network.DomainPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** Credential authorization and anonymous probe candidates are separate (ADR-102). */
@Singleton
class AllowedHostRegistry @Inject constructor() {

    @Volatile
    private var customHosts: Set<String> = emptySet()

    /** Hosts that may be probed (credential-free) but are not yet trusted with credentials. */
    @Volatile
    private var probeCandidates: Set<String> = emptySet()

    fun updateCustomHosts(hosts: Set<String>) {
        customHosts = hosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
    }

    fun customHosts(): Set<String> = customHosts

    /**
     * Hosts the prober may contact. Never used by the credential-stripping interceptor or the
     * redirect guard, so an unverified redirect target can be checked without ever seeing a cookie.
     */
    fun updateProbeCandidates(hosts: Set<String>) {
        probeCandidates = hosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
    }

    fun isProbeAllowed(host: String): Boolean =
        DomainPolicy.isCatalogHost(host) || isAllowed(host) || host.lowercase() in probeCandidates

    fun isAllowed(host: String): Boolean =
        DomainPolicy.normalizeHostInput(host) == host.lowercase() && DomainPolicy.isAllowed(host, customHosts)
}
