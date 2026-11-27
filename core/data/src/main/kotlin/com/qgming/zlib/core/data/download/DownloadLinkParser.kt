package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.network.DomainPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.Response
import okio.ByteString.Companion.decodeHex

/**
 * Some mirrors answer `/eapi/book/{id}/{hash}/file` with 200 JSON instead of 302:
 * `{"success":1,"file":{"downloadLink":"https://…","allowDownload":true,…}}`.
 * Extract only a usable HTTPS/path link; never log the signed URL.
 */
internal object DownloadLinkParser {

    /**
     * Null means a direct file response. Mirrors may label JSON as HTML/octet-stream or omit its
     * MIME type entirely. Peek at the bounded body without consuming bytes from a real book.
     * A resumed segment is never a protocol envelope, even if its first byte happens to be '{'.
     * The caller owns the response and must close it before following a returned link.
     */
    fun fromResponse(response: Response): AppResult<HttpUrl>? {
        if (response.code == 206 || response.code == 416) return null
        val declaredJson = response.header("Content-Type").orEmpty().contains("json", ignoreCase = true)
        if (!declaredJson && !startsWithObject(response)) return null

        val bytes = response.peekBody(MAX_PROTOCOL_BYTES + 1).bytes()
        val body = if (bytes.size <= MAX_PROTOCOL_BYTES) bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF") else ""
        if (!declaredJson && bytes.size <= MAX_PROTOCOL_BYTES) {
            val root = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            if (root == null || root.keys.none { it in PROTOCOL_FIELDS }) return null
        }
        val link = if (response.code == 200) extract(body) else null
        if (link == null) return AppResult.Failure(downloadHttpError(response.code, eapi = true, body = body))
        val target = response.request.url.resolve(link)?.takeIf(DomainPolicy::isStandardHttps)
            ?: return AppResult.Failure(AppError.Parse(appText(TextR.string.text_the_download_url_does_not_use_https)))
        return AppResult.Success(target)
    }

    private fun startsWithObject(response: Response): Boolean = response.body.source().peek().use { source ->
        if (source.request(3) && source.rangeEquals(0, UTF8_BOM)) source.skip(3)
        var inspected = 0L
        var opened = false
        while (inspected++ < MAX_PROTOCOL_BYTES && source.request(1)) {
            when (source.readByte().toInt()) {
                0x20, 0x09, 0x0A, 0x0D -> continue
                0x7B -> if (!opened) opened = true else return false
                0x22, 0x7D -> return opened
                else -> return false // Includes RTF's {\rtf... and ordinary text beginning with '{'.
            }
        }
        false
    }

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

    private const val MAX_PROTOCOL_BYTES = 64 * 1024L
    private val UTF8_BOM = "efbbbf".decodeHex()
    private val PROTOCOL_FIELDS = setOf("success", "file", "url", "link", "download")
}
