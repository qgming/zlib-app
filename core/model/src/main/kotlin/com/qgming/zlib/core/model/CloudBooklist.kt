package com.qgming.zlib.core.model

data class CloudBooklist(
    val id: String,
    val title: String,
    val bookCount: Int?,
    val covers: List<BooklistCover> = emptyList(),
)

data class BooklistCover(val bookId: String, val url: String) {
    companion object {
        const val COUNT = 4
    }
}

data class BooklistPage(
    val books: List<Book>,
    val nextPage: Int?,
)
