package com.qgming.zlib.core.model

/**
 * A book as returned by EAPI. Fields are normalised and never nullable-string:
 * missing server values become empty strings / null numbers.
 */
data class Book(
    val id: String,
    val hash: String,
    val title: String,
    val subtitle: String = "",
    val authors: String = "",
    val publisher: String = "",
    val year: String = "",
    val language: String = "",
    val extension: String = "",
    val sizeBytes: Long? = null,
    val isbn10: String = "",
    val isbn13: String = "",
    val coverUrl: String = "",
    val description: String = "",
    val categories: List<String> = emptyList(),
    val edition: String = "",
    val pages: Int? = null,
    val rating: Double? = null,
    val href: String = "",
) {
    /** Canonical reference used across routes, Room and download state. */
    val ref: String get() = "$id:$hash"

    companion object {
        fun refOf(id: String, hash: String): String = "$id:$hash"
    }
}

/** Parses/validates `id:hash` references. Hash is required for downloads. */
object BookRefs {
    fun parse(raw: String): Pair<String, String>? {
        val index = raw.indexOf(':')
        if (index <= 0 || index == raw.lastIndex) return null
        val id = raw.substring(0, index).trim()
        val hash = raw.substring(index + 1).trim()
        if (id.isEmpty() || hash.isEmpty()) return null
        return id to hash
    }

    fun isValid(raw: String): Boolean = parse(raw) != null
}
