package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.network.DomainVerificationCookies
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DomainVerificationCoordinator @Inject constructor(
    private val cookies: DomainVerificationCookies,
) {
    fun accept(host: String, rawCookie: String?, verifiedNetwork: Long?, userAgent: String? = null): Boolean =
        cookies.putFromWebView(host, rawCookie, verifiedNetwork, userAgent)

    fun clearHost(host: String) = cookies.clearHost(host)
}
