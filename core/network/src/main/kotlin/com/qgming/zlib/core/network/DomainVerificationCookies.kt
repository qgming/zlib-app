package com.qgming.zlib.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class DomainVerificationCookies(
    private val isAllowedHost: (String) -> Boolean,
    private val networkIdentity: () -> Long?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private class Entry(val header: String, val createdAtMillis: Long)

    private val entries = linkedMapOf<String, Entry>()
    private var lastNetwork: Long? = null

    init {
        require(ttlMillis in 1..MAX_TTL_MILLIS)
    }

    @Synchronized
    fun putFromWebView(host: String, rawCookie: String?, verifiedNetwork: Long? = runCatching(networkIdentity).getOrNull()): Boolean {
        val currentNetwork = observeNetwork() ?: return false
        if (verifiedNetwork != currentNetwork) return false
        if (DomainPolicy.normalizeHostInput(host) != host || !isAllowedHost(host)) return false
        val header = sanitizeCookieHeader(rawCookie) ?: return false
        if (entries.size >= MAX_HOSTS && host !in entries) entries.remove(entries.keys.first())
        entries[host] = Entry(header, nowMillis())
        return true
    }

    @Synchronized
    fun cookieHeaderFor(url: HttpUrl): String? {
        if (observeNetwork() == null) return null
        if (!DomainPolicy.isStandardHttps(url) || !url.encodedPath.startsWith("/eapi/")) return null
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
        return entry.header
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
        const val DEFAULT_TTL_MILLIS = 15 * 60 * 1000L
        private const val MAX_TTL_MILLIS = 30 * 60 * 1000L
        private const val MAX_HOSTS = 64
        private const val MAX_RAW_LENGTH = 16 * 1024
        private const val MAX_VALUE_LENGTH = 4096
        private const val MAX_COOKIE_COUNT = 64

        fun sanitizeCookieHeader(rawCookie: String?): String? {
            if (rawCookie.isNullOrEmpty() || rawCookie.length > MAX_RAW_LENGTH || rawCookie.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
            val parts = rawCookie.split(';')
            if (parts.size > MAX_COOKIE_COUNT) return null
            var value: String? = null
            for (part in parts) {
                val entry = part.trim()
                if (entry.substringBefore('=') != COOKIE_NAME) continue
                if (value != null || '=' !in entry) return null
                val candidate = entry.substringAfter('=')
                if (candidate.isEmpty() || candidate.length > MAX_VALUE_LENGTH || candidate.any {
                        it.code !in 0x21..0x7e || it == '"' || it == ',' || it == ';' || it == '\\'
                    }
                ) return null
                value = candidate
            }
            return value?.let { "$COOKIE_NAME=$it" }
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
