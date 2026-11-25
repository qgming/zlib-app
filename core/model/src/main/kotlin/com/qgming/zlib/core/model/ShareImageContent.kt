package com.qgming.zlib.core.model

import java.net.URI

enum class ShareImageKind { Book, Booklist }

/** Display-only snapshot. Credentials, references and signed links never belong in a share image. */
data class ShareImageContent(
    val kind: ShareImageKind,
    val title: String,
    val books: List<ShareImageBook>,
    val fields: List<ShareImageField> = emptyList(),
    val description: String = "",
    val totalBooks: Int? = null,
    val previewIsPartial: Boolean = false,
) {
    /** Limits both rendering work and the small display snapshot passed between activities. */
    fun bounded(): ShareImageContent {
        val visibleBooks = books.take(if (kind == ShareImageKind.Book) 1 else MAX_BOOKS).map { book ->
            ShareImageBook(
                title = shareText(book.title, MAX_TITLE_LENGTH),
                authors = shareText(book.authors, MAX_AUTHORS_LENGTH),
                coverUrl = shareCoverUrl(book.coverUrl),
            )
        }
        val knownTotal = totalBooks?.takeIf { kind == ShareImageKind.Booklist && it >= books.size }
        return copy(
            title = shareText(title, MAX_TITLE_LENGTH),
            books = visibleBooks,
            fields = if (kind == ShareImageKind.Book) fields.asSequence().map { field ->
                ShareImageField(shareText(field.label, MAX_FIELD_LABEL_LENGTH), shareText(field.value, MAX_FIELD_VALUE_LENGTH))
            }.filter { it.label.isNotEmpty() && it.value.isNotEmpty() }.take(MAX_FIELDS).toList() else emptyList(),
            description = if (kind == ShareImageKind.Book) shareText(description, MAX_DESCRIPTION_LENGTH, multiline = true) else "",
            totalBooks = knownTotal,
            previewIsPartial = kind == ShareImageKind.Booklist &&
                (previewIsPartial || books.size > visibleBooks.size || knownTotal != null && knownTotal > visibleBooks.size),
        )
    }

    companion object {
        const val MAX_BOOKS = 6
        const val MAX_TITLE_LENGTH = 200
        const val MAX_AUTHORS_LENGTH = 180
        const val MAX_FIELDS = 10
        const val MAX_FIELD_LABEL_LENGTH = 40
        const val MAX_FIELD_VALUE_LENGTH = 180
        const val MAX_DESCRIPTION_LENGTH = 1_200
        const val MAX_COVER_URL_LENGTH = 768
    }
}

data class ShareImageBook(val title: String, val authors: String, val coverUrl: String)

data class ShareImageField(val label: String, val value: String)

/** Query-bearing image links are omitted rather than rewritten or shared without their signature. */
private fun shareCoverUrl(raw: String): String {
    if (raw.length > ShareImageContent.MAX_COVER_URL_LENGTH || raw.any { it.isWhitespace() || it.isISOControl() } || '\\' in raw) return ""
    val uri = runCatching { URI(raw) }.getOrNull() ?: return ""
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
        uri.rawUserInfo != null || uri.port !in listOf(-1, 443) || uri.rawQuery != null || uri.rawFragment != null ||
        uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"
    ) return ""
    return uri.toASCIIString().takeIf { it.length <= ShareImageContent.MAX_COVER_URL_LENGTH }.orEmpty()
}

private val shareLinks = Regex("""(?i)\b(?:https?|content|file|android\.resource)://[^\s<>"']+""")
private val inlineSpaces = Regex("[^\\S\\n]+")
private val manyLineBreaks = Regex("\\n{3,}")

private fun shareText(raw: String, limit: Int, multiline: Boolean = false): String {
    val text = buildString {
        raw.shareTake(limit * 8).forEach { character ->
            when {
                character == '\r' -> Unit
                character == '\n' || character == '\u2028' || character == '\u2029' -> append(if (multiline) '\n' else ' ')
                character == '\t' -> append(' ')
                character.isISOControl() || character in "\u061c\u200b\u200e\u200f\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069\ufeff" -> Unit
                else -> append(character)
            }
        }
    }.replace(shareLinks, "").replace(inlineSpaces, " ").replace(manyLineBreaks, "\n\n").trim()
    return text.shareTake(limit).trimEnd()
}

/** Safe suggested name for a single PNG; never a path or a server-provided filename. */
fun shareImageFilename(content: ShareImageContent): String {
    val title = content.bounded().title
    var stem = buildString {
        title.forEach { character ->
            append(if (character in "<>:\"/\\|?*" || character.isISOControl()) '_' else character)
        }
    }.trim(' ', '.').removeSuffixIgnoreCase(".png").trim(' ', '.')
    if (stem.isBlank()) stem = if (content.kind == ShareImageKind.Book) "book-share" else "booklist-share"
    if (stem.substringBefore('.').uppercase() in RESERVED_FILENAMES) stem = "_$stem"
    return stem.shareTake(76).trimEnd(' ', '.') + ".png"
}

private fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this

private fun String.shareTake(limit: Int): String {
    if (length <= limit) return this
    val end = if (limit > 0 && this[limit - 1].isHighSurrogate() && this[limit].isLowSurrogate()) limit - 1 else limit
    return substring(0, end)
}

private val RESERVED_FILENAMES = setOf("CON", "PRN", "AUX", "NUL") +
    (1..9).flatMap { listOf("COM$it", "LPT$it") }
