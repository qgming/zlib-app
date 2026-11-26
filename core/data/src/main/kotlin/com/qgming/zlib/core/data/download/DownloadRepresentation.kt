package com.qgming.zlib.core.data.download

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

@Serializable
internal data class DownloadRepresentation private constructor(val resourceSha256: String, val etag: String) {
    fun matchesUrl(url: HttpUrl): Boolean = resourceSha256 == downloadSha256(url.toString())

    fun matchesResponse(url: HttpUrl, etag: String?): Boolean = this == from(url, etag)

    companion object {
        fun from(url: HttpUrl, etag: String?): DownloadRepresentation? {
            val value = etag?.trim()?.takeIf { candidate ->
                candidate.length in 2..512 && candidate.first() == '"' && candidate.last() == '"' &&
                    candidate.substring(1, candidate.lastIndex).all { it == '!' || it in '#'..'~' }
            } ?: return null
            return DownloadRepresentation(downloadSha256(url.toString()), value)
        }
    }
}

internal fun downloadSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).toHexString()
