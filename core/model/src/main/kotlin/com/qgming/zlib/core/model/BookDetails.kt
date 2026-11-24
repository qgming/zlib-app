package com.qgming.zlib.core.model

import java.net.URI

data class BookDetails(val book: Book, val onlinePreview: OnlineBookPreview?)

class OnlineBookPreview private constructor(
    val readerUrl: String,
    val publicBookUrl: String?,
) {
    companion object {
        fun fromDetail(
            readerUrl: String,
            bookUrl: String,
            detailHost: String,
        ): OnlineBookPreview? {
            val reader = httpsUri(readerUrl) ?: return null
            if (reader.hasAccountCredentials()) return null
            val book = parseUri(bookUrl)
            val publicUrl = book?.takeIf {
                it.hasPathUnder("/book/") && if (it.isAbsolute) {
                    httpsUri(bookUrl)?.host.equals(detailHost, ignoreCase = true)
                } else {
                    it.rawAuthority == null
                }
            }?.let { "https://$detailHost${it.rawPath}" }
            return OnlineBookPreview(readerUrl, publicUrl)
        }

        private fun parseUri(url: String): URI? = runCatching { URI(url) }.getOrNull()

        private fun httpsUri(url: String): URI? = parseUri(url)?.takeIf {
            it.scheme.equals("https", ignoreCase = true) && it.host != null &&
                it.rawAuthority.equals(it.host, ignoreCase = true)
        }

        private fun URI.hasAccountCredentials(): Boolean = (query.orEmpty() + "&" + fragment.orEmpty())
            .split('&', ';', '?').any { parameter ->
                parameter.substringBefore('=').lowercase().replace("-", "").replace("_", "") in
                    setOf("remixuserid", "remixuserkey", "password")
            }

        private fun URI.hasPathUnder(prefix: String): Boolean {
            val path = rawPath ?: return false
            return path.startsWith(prefix) && path.length > prefix.length && path == normalize().rawPath &&
                path.split('/').none { segment ->
                    segment.replace("%2e", ".", ignoreCase = true) in setOf(".", "..") ||
                        segment.contains("%2f", ignoreCase = true) || segment.contains("%5c", ignoreCase = true)
                }
        }
    }
}
