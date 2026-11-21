package com.qgming.zlib.core.model

import com.qgming.zlib.core.common.DomainStatus

/** Probe result for a single host. */
data class DomainCheck(
    val host: String,
    val status: DomainStatus,
    val detail: String = "",
    /** Host that served the final response after redirects, when different. */
    val finalHost: String? = null,
    val checkedAtMillis: Long = 0L,
) {
    val healthy: Boolean get() = status == DomainStatus.HEALTHY

    companion object {
        fun unknown(host: String) = DomainCheck(host = host, status = DomainStatus.UNKNOWN)
    }
}

enum class LoginStatus {
    LOGGED_OUT,
    AUTHENTICATING,
    LOGGED_IN,
    AUTH_FAILED,
}
