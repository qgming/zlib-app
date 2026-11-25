package com.qgming.zlib.core.model

/** The displayable part of a comment, without profile data or write controls. */
data class BookComment(
    val id: String,
    val parentId: String?,
    val text: String,
    val author: String,
    val date: String,
    val avatarUrl: String? = null,
    val likeCount: Int = 0,
)

data class BookCommentsPage(
    val comments: List<BookComment> = emptyList(),
    val disabled: Boolean = false,
    val nextPage: Int? = null,
)
