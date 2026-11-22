package com.qgming.zlib.core.model

/** A real document in the configured SAF download directory, independent of any cloud book. */
data class LocalFile(
    val treeUri: String,
    val uri: String,
    val name: String,
    val extension: String,
    val sizeBytes: Long? = null,
    val modifiedAt: Long = 0,
    val book: Book? = null,
) {
    val title: String get() = book?.title?.takeIf(String::isNotBlank) ?: name.substringBeforeLast('.', name)
}

object EbookFiles {
    val extensions = setOf("epub", "pdf", "mobi", "azw", "azw3", "fb2", "djvu", "djv", "txt", "rtf", "lit", "cbz", "cbr", "doc", "docx", "chm")

    fun extension(name: String): String? = name.substringAfterLast('.', "").lowercase().takeIf { it in extensions }

    fun mimeType(extension: String): String = when (extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mobi", "azw" -> "application/x-mobipocket-ebook"
        "azw3" -> "application/vnd.amazon.ebook"
        "fb2" -> "application/x-fictionbook+xml"
        "djvu", "djv" -> "image/vnd.djvu"
        "txt" -> "text/plain"
        "rtf" -> "application/rtf"
        "cbz" -> "application/vnd.comicbook+zip"
        "cbr" -> "application/vnd.comicbook-rar"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "chm" -> "application/vnd.ms-htmlhelp"
        else -> "application/octet-stream"
    }
}
