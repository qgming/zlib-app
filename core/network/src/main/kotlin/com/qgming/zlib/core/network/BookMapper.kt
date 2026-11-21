package com.qgming.zlib.core.network

import com.qgming.zlib.core.model.Book
import kotlinx.serialization.json.JsonObject

/** Maps one `books[]` entry. Entries without a hash are dropped by the caller. */
internal object BookMapper {

    fun fromJson(obj: JsonObject): Book? {
        val id = obj.stringOrNull("id") ?: obj.intOrNull("id")?.toString() ?: return null
        val hash = obj.stringOrNull("hash") ?: return null
        if (hash.isBlank()) return null

        val identifier = obj.stringOrNull("identifier").orEmpty()
        val isbnRaw = obj.stringOrNull("isbn").orEmpty()
        val explicit13 = com.qgming.zlib.core.common.Isbn.normalize(obj.stringOrNull("isbn13"))
        val explicit10 = com.qgming.zlib.core.common.Isbn.normalize(obj.stringOrNull("isbn10"))
        val extracted = com.qgming.zlib.core.common.Isbn.extract(
            listOf(identifier, isbnRaw, obj.stringOrNull("isbn13").orEmpty(), obj.stringOrNull("isbn10").orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(","),
        )

        val isbn13 = explicit13
            ?: extracted.firstOrNull { it.length == 13 }
            ?: extracted.firstOrNull { it.length == 10 }?.let { com.qgming.zlib.core.common.Isbn.toIsbn13(it) }
        val isbn10 = explicit10
            ?: extracted.firstOrNull { it.length == 10 }
            ?: isbn13?.let { com.qgming.zlib.core.common.Isbn.toIsbn10(it) }

        val categories = obj.array("categories")?.asStringList().orEmpty().ifEmpty {
            obj.stringOrNull("category")?.let(::listOf).orEmpty()
        }

        return Book(
            id = id,
            hash = hash,
            title = obj.stringOrNull("title").orEmpty().ifBlank { "未命名" },
            subtitle = obj.stringOrNull("subtitle").orEmpty(),
            authors = obj.stringOrNull("author").orEmpty().ifBlank { obj.stringOrNull("authors").orEmpty() },
            publisher = obj.stringOrNull("publisher").orEmpty(),
            year = obj.stringOrNull("year").orEmpty(),
            language = obj.stringOrNull("language").orEmpty(),
            extension = obj.stringOrNull("extension").orEmpty().lowercase(),
            sizeBytes = obj.longOrNull("filesize") ?: obj.longOrNull("filesizeString"),
            isbn10 = isbn10.orEmpty(),
            isbn13 = isbn13.orEmpty(),
            coverUrl = obj.stringOrNull("cover").orEmpty(),
            description = obj.stringOrNull("description").orEmpty(),
            categories = categories.distinct(),
            edition = obj.stringOrNull("edition").orEmpty(),
            pages = obj.intOrNull("pages"),
            rating = obj.doubleOrNull("interestScore") ?: obj.doubleOrNull("qualityScore"),
            href = obj.stringOrNull("href").orEmpty(),
        )
    }

    fun listFrom(root: JsonObject, key: String = "books"): List<Book> {
        val array = root.array(key) ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.let(::fromJson) }
    }
}
