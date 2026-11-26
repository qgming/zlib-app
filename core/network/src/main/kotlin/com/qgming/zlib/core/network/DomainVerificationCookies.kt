package com.qgming.zlib.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DomainVerificationCookies(
    private val isAllowedHost: (String) -> Boolean,
    private val networkIdentity: () -> Long?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private class Entry(val header: String, val userAgent: String?, val createdAtMillis: Long)

    private val entries = linkedMapOf<String, Entry>()
    private var lastNetwork: Long? = null

    init {
        require(ttlMillis in 1..MAX_TTL_MILLIS)
    }

    @Synchronized
    fun putFromWebView(
        host: String,
        rawCookie: String?,
        verifiedNetwork: Long? = runCatching(networkIdentity).getOrNull(),
        userAgent: String? = null,
    ): Boolean {
        val currentNetwork = observeNetwork() ?: return false
        if (verifiedNetwork != currentNetwork) return false
        if (DomainPolicy.normalizeHostInput(host) != host || !isAllowedHost(host)) return false
        val header = sanitizeCookieHeader(rawCookie) ?: return false
        val cleanUserAgent = sanitizeUserAgent(userAgent)
        if (entries.size >= MAX_HOSTS && host !in entries) entries.remove(entries.keys.first())
        entries[host] = Entry(header, cleanUserAgent, nowMillis())
        return true
    }

    @Synchronized
    fun cookieHeaderFor(url: HttpUrl): String? = activeEntryFor(url)?.header

    @Synchronized
    fun userAgentFor(url: HttpUrl): String? = activeEntryFor(url)?.userAgent

    private fun activeEntryFor(url: HttpUrl): Entry? {
        if (observeNetwork() == null) return null
        if (!DomainPolicy.isStandardHttps(url) || !isEligiblePath(url.encodedPath)) return null
        if (!isAllowedHost(url.host)) {
            entries.remove(url.host)
            return null
        }
        val entry = entries[url.host] ?: return null
        val elapsed = nowMillis() - entry.createdAtMillis
        if (elapsed < 0 || elapsed >= ttlMillis) {
            entries.remove(url.host)
            return null
        }
        return entry
    }

    @Synchronized
    fun clearHost(host: String) {
        entries.remove(host)
    }

    @Synchronized
    fun clear() {
        entries.clear()
        lastNetwork = null
    }

    private fun observeNetwork(): Long? {
        val current = runCatching(networkIdentity).getOrNull()
        if (current == null || current != lastNetwork) entries.clear()
        lastNetwork = current
        return current
    }

    companion object {
        const val COOKIE_NAME = "__diamwall"
        val ALLOWED_COOKIE_NAMES: Set<String> = setOf(
            "__diamwall",
            "cf_clearance",
            "__cf_bm",
            "ddos_guard",
            "_dd_",
            "__ddg1_",
            "__ddg2_",
        )
        const val DEFAULT_TTL_MILLIS = 15 * 60 * 1000L
        private const val MAX_TTL_MILLIS = 30 * 60 * 1000L
        private const val MAX_HOSTS = 64
        private const val MAX_RAW_LENGTH = 16 * 1024
        private const val MAX_VALUE_LENGTH = 4096
        private const val MAX_USER_AGENT_LENGTH = 512
        private const val MAX_COOKIE_COUNT = 64

        fun isEligiblePath(encodedPath: String): Boolean =
            encodedPath.startsWith("/eapi/") || encodedPath.startsWith("/papi/")

        fun sanitizeCookieHeader(rawCookie: String?): String? {
            if (rawCookie.isNullOrEmpty() || rawCookie.length > MAX_RAW_LENGTH || rawCookie.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
            val parts = rawCookie.split(';')
            if (parts.size > MAX_COOKIE_COUNT) return null
            val allowed = linkedMapOf<String, String>()
            for (part in parts) {
                val entry = part.trim()
                val eq = entry.indexOf('=')
                if (eq <= 0) continue
                val name = entry.substring(0, eq).trim()
                val canonicalName = ALLOWED_COOKIE_NAMES.firstOrNull { it.equals(name, ignoreCase = true) } ?: continue
                if (canonicalName in allowed) return null
                val candidate = entry.substring(eq + 1)
                if (candidate.isEmpty() || candidate.length > MAX_VALUE_LENGTH || candidate.any {
                        it.code !in 0x21..0x7e || it == '"' || it == ',' || it == ';' || it == '\\'
                    }
                ) return null
                allowed[canonicalName] = candidate
            }
            if (allowed.isEmpty()) return null
            return allowed.entries.joinToString("; ") { (name, value) -> "$name=$value" }
        }

        fun sanitizeUserAgent(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value.length > MAX_USER_AGENT_LENGTH) return null
            if (value.any { it.code !in 0x20..0x7e }) return null
            return value
        }
    }
}

object DomainVerificationNavigation {
    fun isAllowed(host: String, rawUrl: String): Boolean {
        if (DomainPolicy.normalizeHostInput(host) != host) return false
        val url = rawUrl.toHttpUrlOrNull() ?: return false
        return DomainPolicy.isStandardHttps(url) && url.host == host
    }
}
