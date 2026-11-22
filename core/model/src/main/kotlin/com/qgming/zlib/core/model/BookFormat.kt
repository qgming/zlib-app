package com.qgming.zlib.core.model

/** One existing file returned by the formats EAPI; equal extensions can have different refs. */
data class BookFormat(
    val id: String,
    val hash: String,
    val extension: String,
    val sizeBytes: Long? = null,
    val sizeText: String = "",
) {
    val ref: String get() = Book.refOf(id, hash)
}
