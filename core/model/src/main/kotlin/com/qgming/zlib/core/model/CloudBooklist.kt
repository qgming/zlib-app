package com.qgming.zlib.core.model

data class CloudBooklist(
    val id: String,
    val title: String,
    val bookCount: Int?,
    val covers: List<BooklistCover> = emptyList(),
) {
    val needsCoverPreview: Boolean
        get() = covers.size < minOf(bookCount ?: BooklistCover.COUNT, BooklistCover.COUNT)

    fun withCoverPreview(preview: List<BooklistCover>): CloudBooklist =
        if (preview.isEmpty()) this else copy(covers = (covers + preview).distinctBy(BooklistCover::bookId).take(BooklistCover.COUNT))

    companion object {
        const val MAX_TITLE_LENGTH = 200
    }
}

data class BooklistCover(val bookId: String, val url: String) {
    companion object {
        const val COUNT = 4
    }
}

data class BooklistPage(
    val books: List<Book>,
    val nextPage: Int?,
)
