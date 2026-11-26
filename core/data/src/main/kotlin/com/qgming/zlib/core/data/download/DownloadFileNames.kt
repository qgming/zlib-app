package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.FileNames
import java.security.MessageDigest

/**
 * Temporary files are owned by the complete `id:hash` reference, never by the display title,
 * so resuming and removal can only ever touch this task's own file (ADR-060).
 */
internal object DownloadFileNames {
    private val unsafe = Regex("[^A-Za-z0-9._-]")

    fun partName(ref: String): String {
        val idPart = ref.substringBefore(':', missingDelimiterValue = "")
            .replace(unsafe, "_")
            .trim('.', '_')
            .take(24)
        val digest = sha256Hex(ref).take(24)
        return if (idPart.isBlank()) "zlib-$digest.part" else "zlib-$idPart-$digest.part"
    }

    /**
     * Final readable name priority per EAPI §2.4: `Content-Disposition`, then the last segment of
     * the final URL, then the title-based fallback. Always sanitized and extension-completed.
     */
    fun finalName(
        contentDisposition: String?,
        urlSegment: String?,
        fallback: String,
        extension: String?,
    ): String {
        val safeFallback = FileNames.sanitize(fallback, fallback = "download")
        val fromHeader = FileNames.fromContentDisposition(contentDisposition)
            ?.let { FileNames.sanitize(it, fallback = "") }
            ?.takeIf(String::isNotBlank)
        val fromUrl = urlSegment
            ?.let { FileNames.sanitize(it, fallback = "") }
            ?.takeIf { it.isNotBlank() && it != "." && it != ".." }
        val base = fromHeader ?: fromUrl ?: safeFallback
        val selectedExtension = extension?.trim()?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]{1,20}")) }
        val withExtension = if (selectedExtension != null) {
            val suffix = base.substringAfterLast('.', "")
            val stem = if (base.indexOf('.') > 0 && suffix.matches(Regex("[a-zA-Z0-9]{1,12}")) &&
                (fromHeader == null && fromUrl != null || suffix.lowercase() in BOOK_EXTENSIONS)
            ) base.substringBeforeLast('.') else base
            if (base.endsWith(".$selectedExtension", ignoreCase = true)) base else "$stem.$selectedExtension"
        } else base
        return FileNames.sanitize(withExtension, fallback = safeFallback)
    }

    /**
     * Keeps the readable title-based name and walks `" (2)"`, `" (3)"`, ... until no document
     * with that name exists. Callers must re-check [exists] right before rename/create; SAF gives
     * no atomic "create if absent", so this is the strongest guarantee available.
     */
    fun uniqueFinalName(
        base: String,
        fallbackSuffix: String = "download",
        exists: (String) -> Boolean,
    ): String {
        val cleaned = base.ifBlank { fallbackSuffix.ifBlank { "download" } }
        if (!exists(cleaned)) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < cleaned.length - 1
        val stem = if (hasExtension) cleaned.substring(0, dot) else cleaned
        val extension = if (hasExtension) cleaned.substring(dot) else ""
        var index = 2L
        while (true) {
            val candidate = "$stem ($index)$extension"
            if (!exists(candidate)) return candidate
            index++
        }
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val BOOK_EXTENSIONS = setOf("epub", "pdf", "fb2", "mobi", "azw", "azw3", "djvu", "doc", "docx", "rtf", "txt", "cbz", "cbr", "lit", "chm", "html", "htm", "odt")
}
