package com.qgming.zlib.core.data.download

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Some mirrors answer `/eapi/book/{id}/{hash}/file` with 200 JSON instead of 302:
 * `{"success":1,"file":{"downloadLink":"https://…","allowDownload":true,…}}`.
 * Extract only a usable HTTPS/path link; never log the signed URL.
 */
internal object DownloadLinkParser {

    fun failureMessage(body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["success"] as? JsonPrimitive)?.content != "0") return null
        return (root["error"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    fun extract(body: String): String? {
        val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        if ((root["success"] as? JsonPrimitive)?.content == "0") return null
        val value = root["file"] ?: root["url"] ?: root["link"] ?: root["download"]
        return when (value) {
            is JsonPrimitive -> if (value is JsonNull) null else value.content.takeIf { it.isNotBlank() }

            is JsonArray -> value.firstOrNull()
                ?.let { (it as? JsonPrimitive)?.content }
                ?.takeIf { it.isNotBlank() }

            is JsonObject -> {
                val allowed = (value["allowDownload"] as? JsonPrimitive)?.content
                if (allowed == "0" || allowed == "false") {
                    null
                } else {
                    listOf("downloadLink", "downloadUrl", "download", "url", "link", "href", "path", "file")
                        .firstNotNullOfOrNull { key ->
                            (value[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                        }
                }
            }

            else -> null
        }
    }
}
