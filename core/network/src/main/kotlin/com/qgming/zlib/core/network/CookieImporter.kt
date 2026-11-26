package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Accepts three shapes of user-supplied cookies (ADR-012):
 * 1. JSON object: {"remix_userid":"...","remix_userkey":"..."}
 * 2. Netscape cookies.txt, including the `#HttpOnly_` format
 * 3. Raw Cookie header: "remix_userid=...; remix_userkey=..."
 */
object CookieImporter {

    data class ImportedCookies(
        val remixUserId: String,
        val remixUserKey: String,
        val cookies: Map<String, String>,
    )

    fun parse(raw: String, expectedHost: String? = null): AppResult<ImportedCookies> {
        if (raw.length > MAX_IMPORT_CHARS) return failure(appText(TextR.string.text_no_cookies_could_be_parsed))
        val input = raw.trim()
        val text = if (input.startsWith("Cookie:", ignoreCase = true)) input.substring(7).trim() else input
        if (text.isEmpty()) return failure(appText(TextR.string.text_cookie_content_is_empty))
        val host = expectedHost?.let {
            DomainPolicy.normalizeHostInput(it) ?: return failure(appText(TextR.string.text_invalid_domain_format))
        }

        val cookies = when {
            text.startsWith("{") || text.startsWith("[") -> parseJson(text, host)
            looksLikeNetscape(text) -> parseNetscape(text, host)
            else -> parseHeader(text)
        }

        if (cookies.isEmpty()) return failure(appText(TextR.string.text_no_cookies_could_be_parsed))

        val groups = cookies.groupBy { it.domain }.entries.sortedByDescending { it.key?.length ?: 0 }
        val pairs = groups.mapNotNull { (_, entries) ->
            val values = entries.groupBy({ it.name }, { it.value })
            val userId = values[Session.USER_ID_COOKIE]?.distinct()?.singleOrNull() ?: return@mapNotNull null
            val userKey = values[Session.USER_KEY_COOKIE]?.distinct()?.singleOrNull() ?: return@mapNotNull null
            ImportedCookies(userId, userKey, mapOf(Session.USER_ID_COOKIE to userId, Session.USER_KEY_COOKIE to userKey))
        }
        val pair = if (host == null) pairs.distinct().singleOrNull() else pairs.firstOrNull()
        return pair?.let { AppResult.Success(it) }
            ?: failure(appText(TextR.string.text_cookie_is_missing_remix_userid_remix_userkey))
    }

    private fun failure(message: String): AppResult.Failure =
        AppResult.Failure(AppError.Parse(message))

    private fun looksLikeNetscape(text: String): Boolean = text.lineSequence().any { line ->
        val row = line.trimStart()
        row.startsWith("# Netscape HTTP Cookie File", ignoreCase = true) ||
            (!row.startsWith('#') || row.startsWith(HTTP_ONLY_PREFIX)) && row.count { it == '\t' } >= 6
    }

    private fun parseJson(text: String, host: String?): List<ExportedCookie> = runCatching {
        val root = Json.parseToJsonElement(text)
        when (val rows = (root as? JsonObject)?.get("cookies") ?: root) {
            is JsonObject -> rows.mapNotNull { (name, element) ->
                when (element) {
                    is JsonPrimitive -> safeCookie(name, element.stringOrNull(), null)
                    is JsonObject -> jsonCookie(element, name, host)
                    else -> null
                }
            }

            is JsonArray -> rows.mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                jsonCookie(row, row.stringOrNull("name") ?: row.stringOrNull("key"), host)
            }

            else -> emptyList()
        }
    }.getOrElse { emptyList() }

    private fun jsonCookie(row: JsonObject, name: String?, host: String?): ExportedCookie? {
        val rawDomain = row.stringOrNull("domain")
        val domain = if ("domain" in row) {
            matchingDomain(rawDomain, host, row.boolOrNull("hostOnly")?.not() ?: (rawDomain?.startsWith('.') == true))
                ?: return null
        } else {
            null
        }
        return safeCookie(name, row.stringOrNull("value"), domain)
    }

    private fun safeCookie(name: String?, value: String?, domain: String?): ExportedCookie? {
        val key = when (name?.trim()) {
            Session.USER_ID_COOKIE -> Session.USER_ID_COOKIE
            Session.USER_KEY_COOKIE, Session.USER_KEY_COOKIE_ALT -> Session.USER_KEY_COOKIE
            else -> return null
        }
        val sanitized = value?.trim()?.removeSurrounding("\"")?.takeIf { Session.isCredentialValue(it) } ?: return null
        return ExportedCookie(key, sanitized, domain)
    }

    private fun parseNetscape(text: String, host: String?): List<ExportedCookie> = text.lineSequence().mapNotNull { line ->
        val row = line.trimStart().removePrefix(HTTP_ONLY_PREFIX)
        if (row.isBlank() || row.startsWith('#')) return@mapNotNull null
        val parts = row.split('\t', limit = 7)
        if (parts.size != 7) return@mapNotNull null
        val subdomains = when (parts[1].uppercase()) {
            "TRUE" -> true
            "FALSE" -> false
            else -> return@mapNotNull null
        }
        val domain = matchingDomain(parts[0], host, subdomains) ?: return@mapNotNull null
        safeCookie(parts[5], parts[6], domain)
    }.toList()

    private fun matchingDomain(rawDomain: String?, host: String?, includeSubdomains: Boolean): String? {
        val raw = rawDomain?.trim()?.removePrefix(".") ?: return null
        val domain = DomainPolicy.normalizeHostInput(raw)?.takeIf { it == raw.lowercase() } ?: return null
        return domain.takeIf { host == null || host == it || includeSubdomains && host.endsWith(".$it") }
    }

    private fun parseHeader(text: String): List<ExportedCookie> = text.split(';', '\n').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index <= 0) null else safeCookie(part.substring(0, index), part.substring(index + 1), null)
    }

    private data class ExportedCookie(val name: String, val value: String, val domain: String?)
    private const val HTTP_ONLY_PREFIX = "#HttpOnly_"
    private const val MAX_IMPORT_CHARS = 1024 * 1024
}
