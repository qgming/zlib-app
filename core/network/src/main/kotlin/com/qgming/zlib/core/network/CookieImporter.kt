package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Session
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Accepts three shapes of user-supplied cookies (ADR-012):
 * 1. JSON object: {"remix_userid":"...","remix_userkey":"..."}
 * 2. Netscape cookies.txt (tab separated, lines starting with # ignored)
 * 3. Raw Cookie header: "remix_userid=...; remix_userkey=..."
 */
object CookieImporter {

    data class ImportedCookies(
        val remixUserId: String,
        val remixUserKey: String,
        val cookies: Map<String, String>,
    )

    private val attributeNames = setOf(
        "path", "domain", "expires", "max-age", "secure", "httponly", "samesite", "priority",
    )

    fun parse(raw: String, expectedHost: String? = null): AppResult<ImportedCookies> {
        val text = raw.trim().removePrefix("Cookie:").trim()
        if (text.isEmpty()) return failure("Cookie 内容为空")

        val cookies = when {
            text.startsWith("{") || text.startsWith("[") -> parseJson(text)
            looksLikeNetscape(text) -> parseNetscape(text, expectedHost)
            else -> parseHeader(text)
        }

        if (cookies.isEmpty()) return failure("没有解析到任何 Cookie")

        val userId = cookies[Session.USER_ID_COOKIE].orEmpty()
        val userKey = cookies[Session.USER_KEY_COOKIE] ?: cookies[Session.USER_KEY_COOKIE_ALT].orEmpty()
        if (userId.isBlank() || userKey.isBlank()) {
            return failure("Cookie 中缺少 remix_userid / remix_userkey")
        }
        return AppResult.Success(ImportedCookies(userId, userKey, cookies))
    }

    private fun failure(message: String): AppResult.Failure =
        AppResult.Failure(AppError.Parse(message))

    private fun looksLikeNetscape(text: String): Boolean {
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .any { it.split('\t').size >= 7 }
    }

    private fun parseJson(text: String): Map<String, String> = runCatching {
        when (val element = Json.parseToJsonElement(text)) {
            is JsonObject -> {
                val nested = element["cookies"]
                if (nested is JsonObject) flatten(nested) else flatten(element)
            }

            is JsonArray -> element.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
                val name = (obj["name"] as? JsonPrimitive)?.content
                    ?: (obj["key"] as? JsonPrimitive)?.content
                val value = (obj["value"] as? JsonPrimitive)?.content
                if (name.isNullOrBlank() || value.isNullOrBlank()) null else name to value
            }.toMap()

            else -> emptyMap()
        }
    }.getOrElse { emptyMap() }

    private fun flatten(obj: JsonObject): Map<String, String> = obj.mapNotNull { (name, element) ->
        val value = when (element) {
            is JsonPrimitive -> element.content
            is JsonObject -> (element["value"] as? JsonPrimitive)?.content
            else -> null
        }
        if (name.isBlank() || value.isNullOrBlank()) null else name to value
    }.toMap()

    private fun parseNetscape(text: String, expectedHost: String?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = line.split('\t')
            if (parts.size < 7) return@forEach
            val domain = parts[0].trim().removePrefix(".")
            if (expectedHost != null && !matchesHost(domain, expectedHost)) return@forEach
            val name = parts[5].trim()
            val value = parts[6].trim()
            if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
        }
        return result
    }

    private fun matchesHost(cookieDomain: String, expectedHost: String): Boolean {
        val host = expectedHost.lowercase()
        val domain = cookieDomain.lowercase()
        return domain == host || host.endsWith(".$domain") || domain.endsWith(".$host")
    }

    private fun parseHeader(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        text.split(';', '\n').forEach { part ->
            val piece = part.trim()
            if (piece.isEmpty()) return@forEach
            val index = piece.indexOf('=')
            if (index <= 0) return@forEach
            val name = piece.substring(0, index).trim()
            if (name.lowercase() in attributeNames) return@forEach
            val value = piece.substring(index + 1).trim().removeSurrounding("\"")
            if (name.isNotEmpty() && value.isNotEmpty()) result[name] = value
        }
        return result
    }

}
