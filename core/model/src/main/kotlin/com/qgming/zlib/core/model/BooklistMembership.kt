package com.qgming.zlib.core.model

/** Whether the book being viewed is in one of the current account's own booklists. */
data class BooklistMembership(
    val booklist: CloudBooklist,
    val isMember: Boolean,
    val readlistBookId: String? = null,
) {
    val booklistId: String get() = booklist.id
    val title: String get() = booklist.title
}
