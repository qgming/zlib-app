package com.qgming.zlib.core.common

import java.io.ByteArrayOutputStream
import java.net.URLDecoder

/** File-name cleaning for downloads. See docs/EAPI.md §2.4. */
object FileNames {

    private const val MAX_BASE_LENGTH = 120
    private const val MAX_BASE_UTF8_BYTES = 180
    private const val MAX_EXT_LENGTH = 16
    private const val FALLBACK = "download"

    private val illegalChars = Regex("[<>:\"/\\\\|?*\\u0000-\\u001f\\u007f]")
    private val whitespace = Regex("\\s+")

    /**
     * Produces a safe name: strips any directory component, control/illegal characters,
     * leading/trailing dots and spaces, and caps the base name (both chars and UTF-8 bytes)
     * while keeping a bounded extension.
     */
    fun sanitize(raw: String?, fallback: String = FALLBACK): String {
        if (raw.isNullOrBlank()) return fallback
        val lastSegment = raw.substringAfterLast('/').substringAfterLast('\\')
        var name = lastSegment
            .replace(illegalChars, "_")
            .replace(whitespace, " ")
            .trim()
            .trimStart('.', ' ')
            .trimEnd('.', ' ')
        if (name.isBlank() || name == "." || name == "..") return fallback

        val dot = name.lastIndexOf('.')
        if (dot > 0) {
            val base = truncateUtf8(name.substring(0, dot), MAX_BASE_LENGTH, MAX_BASE_UTF8_BYTES).trimEnd('.', ' ')
            val ext = truncateUtf8(name.substring(dot + 1), MAX_EXT_LENGTH, MAX_EXT_LENGTH * 2).trimEnd('.', ' ')
            name = when {
                base.isBlank() -> fallback
                ext.isBlank() -> base
                else -> "$base.$ext"
            }
        } else {
            name = truncateUtf8(name, MAX_BASE_LENGTH, MAX_BASE_UTF8_BYTES).trimEnd('.', ' ')
        }
        return name.ifBlank { fallback }
    }

    private fun truncateUtf8(value: String, maxChars: Int, maxBytes: Int): String {
        if (value.length <= maxChars && value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        val out = StringBuilder(minOf(value.length, maxChars))
        var bytes = 0
        var i = 0
        while (i < value.length && out.length < maxChars) {
            val cp = value.codePointAt(i)
            val charCount = Character.charCount(cp)
            val utf8Bytes = when {
                cp <= 0x7F -> 1
                cp <= 0x7FF -> 2
                cp <= 0xFFFF -> 3
                else -> 4
            }
            if (out.length + charCount > maxChars || bytes + utf8Bytes > maxBytes) break
            out.appendCodePoint(cp)
            bytes += utf8Bytes
            i += charCount
        }
        return out.toString()
    }

    /** Appends [extension] if the name has none. */
    fun withExtension(name: String, extension: String?): String {
        val ext = extension?.trim()?.trimStart('.')?.lowercase().orEmpty()
        if (ext.isEmpty()) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.length - 1 && name.length - dot - 1 <= 16
        return if (hasExtension) name else "$name.$ext"
    }

    /**
     * Parses `Content-Disposition`, preferring RFC 5987 `filename*=` then `filename=`.
     * Returns null when no usable name is present.
     */
    fun fromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("""filename\*\s*=\s*([^;]+)""", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.get(1)?.trim()?.trim('"')
        if (!encoded.isNullOrBlank()) {
            val value = encoded.substringAfter("''", encoded)
            val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        val plain = Regex("""filename\s*=\s*(?:"([^"]*)"|([^;]+))""", RegexOption.IGNORE_CASE)
            .find(header)
        val value = plain?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }?.trim()
        return value?.takeIf { it.isNotBlank() }
    }

    fun encodeUtf8(value: String): String {
        val out = ByteArrayOutputStream()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val i = byte.toInt() and 0xff
            if (i in 0x20..0x7e && i != 0x25) {
                out.write(i)
            } else {
                out.write('%'.code)
                out.write("0123456789ABCDEF"[i shr 4].code)
                out.write("0123456789ABCDEF"[i and 0x0f].code)
            }
        }
        return out.toString(Charsets.US_ASCII)
    }
}
