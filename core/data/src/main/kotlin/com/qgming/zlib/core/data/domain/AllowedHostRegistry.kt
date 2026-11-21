package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.network.DomainPolicy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime allowlist that adds user mirrors. The OkHttp credential-stripping interceptor
 * consults this, so a custom mirror keeps working without weakening the redirect policy.
 */
@Singleton
class AllowedHostRegistry @Inject constructor() {

    @Volatile
    private var customHosts: Set<String> = emptySet()

    fun updateCustomHosts(hosts: Set<String>) {
        customHosts = hosts.mapNotNull(DomainPolicy::normalizeHostInput).toSet()
    }

    fun customHosts(): Set<String> = customHosts

    fun isAllowed(host: String): Boolean = DomainPolicy.isAllowed(host, customHosts)
}
