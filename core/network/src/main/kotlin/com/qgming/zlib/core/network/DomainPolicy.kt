package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppResult

/**
 * Pure host allowlist logic. No Android dependencies, fully unit-tested.
 *
 * Rules (AGENTS.md §3.2, docs/EAPI.md §3):
 * - HTTPS only, no port/path/query/userinfo, no IP literal, no single-label host.
 * - Built-in entries trust the exact host and its subdomains.
 * - User mirrors trust the exact host only.
 */
object DomainPolicy {

    /** Built-in suffixes from AGENTS.md §3.2. */
    val BUILT_IN_SUFFIXES: List<String> = listOf(
        "1lib.sk",
        "article.sk",
        "articles.sk",
        "z-lib.by",
        "z-lib.fm",
        "z-lib.gd",
        "z-lib.gl",
        "z-lib.sk",
        "z-library.ec",
        "z-library.im",
        "z-library.qa",
        "z-library.sk",
        "z-library.website",
        "z101.by",
        "zlib.bz",
        "zlib.re",
    )

    /**
     * Probed concurrently on first launch (AGENTS.md §3.2). The order is also the failover priority:
     * hosts that answer the EAPI directly come first, DiamWall-protected hosts and the dead
     * `z-lib.fm` certificate come last (ADR-065/066).
     */
    val DEFAULT_PROBE_HOSTS: List<String> = listOf(
        "zlib.bz",
        "zlib.re",
        "z101.by",
        "z-lib.by",
        "z-lib.gd",
        "z-lib.gl",
        "z-library.ec",
        "z-library.qa",
        "z-library.website",
        "article.sk",
        "articles.sk",
        "z-library.im",
        "z-lib.sk",
        "z-library.sk",
        "1lib.sk",
        "z-lib.fm",
    )

    private val hostPattern = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
    private val ipv4Pattern = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun isBuiltIn(host: String): Boolean {
        val h = host.lowercase()
        return BUILT_IN_SUFFIXES.any { suffix -> h == suffix || h.endsWith(".$suffix") }
    }

    fun isAllowed(host: String, customHosts: Set<String> = emptySet()): Boolean {
        val h = host.lowercase()
        if (isBuiltIn(h)) return true
        return customHosts.any { it.equals(h, ignoreCase = true) }
    }

    /**
     * True when [host] belongs to a domain family the server itself advertises. Used only to
     * validate a redirect target before it enters the runtime allowlist (ADR-065).
     */
    fun isKnownDomainFamily(host: String, known: Set<String> = BUILT_IN_SUFFIXES.toSet()): Boolean {
        val h = host.lowercase().trim()
        if (h.isEmpty()) return false
        return known.any { suffix -> h == suffix || h.endsWith(".$suffix") }
    }

    fun baseUrl(host: String): String = "https://$host"

    fun hostFromUrl(url: String): String? = runCatching {
        val uri = java.net.URI(url)
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        uri.host?.lowercase()
    }.getOrNull()

    /**
     * Accepts a bare host or an `https://host` / `https://host/` input and returns the bare
     * lowercase host. Returns null for anything that violates the allowlist format.
     */
    fun normalizeHostInput(raw: String?): String? {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return null
        val lower = input.lowercase()

        val hostPart = when {
            lower.startsWith("http://") -> return null
            lower.startsWith("https://") -> {
                val rest = input.substring(8)
                val slash = rest.indexOf('/')
                val withoutPath = if (slash < 0) rest else rest.substring(0, slash)
                val path = if (slash < 0) "" else rest.substring(slash)
                if (path.isNotEmpty() && path != "/") return null
                withoutPath
            }
            input.contains("://") -> return null
            else -> input.substringBefore('/').also { if (input.contains('/')) return null }
        }

        if (hostPart.isEmpty()) return null
        if (hostPart.contains('@') || hostPart.contains(':') || hostPart.contains('?') || hostPart.contains('#')) return null
        val host = hostPart.lowercase().trimEnd('.')
        if (host.isEmpty() || host.contains("..")) return null
        if (host.length > 253) return null
        if (ipv4Pattern.matches(host)) return null
        if (!host.contains('.') && host != "localhost") return null
        if (host == "localhost") return null
        if (!hostPattern.matches(host)) return null
        return host
    }

    /** True when [host] is the exact host of a redirect target that the runtime allowlist accepted. */
    fun isForeignHostAcceptable(host: String): Boolean = normalizeHostInput(host) != null

    /** Validates and normalises a user-supplied mirror host. */
    fun validateCustomHost(raw: String?): AppResult<String> {
        val host = normalizeHostInput(raw)
            ?: return AppResult.Failure(
                com.qgming.zlib.core.common.AppError.Parse("域名格式无效：只允许 HTTPS 主机名，不能带端口、路径或 IP。"),
            )
        if (isBuiltIn(host)) {
            return AppResult.Failure(
                com.qgming.zlib.core.common.AppError.Parse("这是内置域名，无需手动添加。"),
            )
        }
        return AppResult.Success(host)
    }
}
