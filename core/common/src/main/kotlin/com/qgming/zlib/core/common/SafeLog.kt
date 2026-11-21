package com.qgming.zlib.core.common

import android.util.Log
import java.net.URI

/**
 * Central logging gate. Anything that could contain credentials, cookies, signed download
 * URLs or passwords must go through the redaction helpers in this object.
 *
 * Verbose logging is disabled in release builds by [com.qgming.zlib.ZlibApplication].
 */
object SafeLog {

    private val sensitiveHeaders = setOf(
        "cookie",
        "set-cookie",
        "authorization",
        "proxy-authorization",
        "remix-userid",
        "remix-userkey",
        "remix-user-key",
    )

    @Volatile
    var enabled: Boolean = true

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, redactText(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, redactText(message))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, redactText(message), throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, redactText(message), throwable)
    }

    /** Keeps scheme/host/path, drops user-info and the whole query string. */
    fun redactUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme ?: return raw.substringBefore('?')
            val host = uri.host ?: return raw.substringBefore('?')
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty()
            val hadQuery = !uri.rawQuery.isNullOrEmpty() || raw.contains('?')
            buildString {
                append(scheme).append("://").append(host).append(port).append(path)
                if (hadQuery) append("?<redacted>")
            }
        }.getOrElse {
            raw.substringBefore('?').substringBefore('#')
        }
    }

    fun redactHeader(name: String, value: String): String {
        val lower = name.lowercase()
        if (lower in sensitiveHeaders) {
            return if (lower == "cookie" || lower == "set-cookie") {
                value.split(';')
                    .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotEmpty) }
                    .joinToString("; ") { "$it=***" }
            } else {
                "***"
            }
        }
        return redactText(value)
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) -> redactHeader(name, value) }

    /** Removes credentials that may appear in free-form text (errors, URLs, stack traces). */
    fun redactText(text: String): String {
        var result = text
        result = result.replace(
            Regex("""(remix[_-]?user(?:id|key|_key)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s;&,}"']+)""", RegexOption.IGNORE_CASE),
            "$1***",
        )
        result = result.replace(
            Regex("""((?:password|passwd|pwd)\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s;&,}"']+)""", RegexOption.IGNORE_CASE),
            "$1***",
        )
        result = result.replace(
            Regex("""((?:cookie|set-cookie)\s*[=:]\s*)[^\r\n]+""", RegexOption.IGNORE_CASE),
            "$1***",
        )
        return result
    }
}
