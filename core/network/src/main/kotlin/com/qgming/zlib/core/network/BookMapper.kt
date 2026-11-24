package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import com.qgming.zlib.core.model.Book
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Maps one `books[]` entry. Entries without a hash are dropped by the caller. */
internal object BookMapper {

    private val idPattern = Regex("[A-Za-z0-9]{1,32}")
    private val hashPattern = Regex("[A-Za-z0-9]{1,64}")

    fun isSafeRef(id: String, hash: String): Boolean =
        idPattern.matches(id) && hashPattern.matches(hash)

    fun fromJson(obj: JsonObject): Book? {
        val id = obj.stringOrNull("id") ?: obj.intOrNull("id")?.toString() ?: return null
        val hash = obj.stringOrNull("hash") ?: return null
        if (!isSafeRef(id, hash)) return null

        val identifier = obj.stringOrNull("identifier").orEmpty()
        val isbnRaw = obj.stringOrNull("isbn").orEmpty()
        val explicit13 = com.qgming.zlib.core.common.Isbn.normalize(obj.stringOrNull("isbn13"))
            ?.takeIf { it.length == 13 }
        val explicit10 = com.qgming.zlib.core.common.Isbn.normalize(obj.stringOrNull("isbn10"))
            ?.takeIf { it.length == 10 }
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

        val categories = when (val field = obj["categories"]) {
            is JsonObject -> field.values.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.stringOrNull() }
            is JsonPrimitive -> field.stringOrNull()?.split(',', ';').orEmpty()
            else -> field?.asStringList().orEmpty()
        }.map(String::trim).filter(String::isNotEmpty).ifEmpty {
            obj.stringOrNull("category")?.trim()?.takeIf(String::isNotEmpty)?.let(::listOf).orEmpty()
        }

        return Book(
            id = id,
            hash = hash,
            title = obj.stringOrNull("title").orEmpty().ifBlank { appText(TextR.string.text_untitled) },
            subtitle = obj.stringOrNull("subtitle").orEmpty(),
            authors = obj.stringOrNull("author").orEmpty().ifBlank { obj.stringOrNull("authors").orEmpty() },
            publisher = obj.stringOrNull("publisher").orEmpty(),
            year = obj.stringOrNull("year").orEmpty(),
            language = obj.stringOrNull("language").orEmpty(),
            extension = obj.stringOrNull("extension").orEmpty().lowercase(),
            sizeBytes = obj.longOrNull("filesize") ?: obj.longOrNull("filesizeString"),
            isbn10 = isbn10.orEmpty(),
            isbn13 = isbn13.orEmpty(),
            coverUrl = usableCover(obj.stringOrNull("cover")),
            description = obj.stringOrNull("description").orEmpty(),
            categories = categories.distinct(),
            edition = obj.stringOrNull("edition").orEmpty(),
            pages = obj.intOrNull("pages"),
            rating = obj.doubleOrNull("interestScore") ?: obj.doubleOrNull("qualityScore"),
            href = obj.stringOrNull("href").orEmpty(),
        )
    }

    fun listFrom(root: JsonObject, includeHistoryDate: Boolean = false): List<Book> {
        val array = root.array("books") ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val book = fromJson(item) ?: return@mapNotNull null
            if (includeHistoryDate) {
                book.copy(
                    historyDate = item.stringOrNull("date_download")
                        ?: item.stringOrNull("date") ?: item.stringOrNull("downloaded_at"),
                )
            } else {
                book
            }
        }
    }

    internal fun usableCover(raw: String?): String {
        val url = raw?.trim()?.toHttpUrlOrNull() ?: return ""
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) return ""
        // Downloaded lists can omit the collection folder. Only details know its value.
        if (url.encodedPath.contains("//") || url.encodedPath.endsWith("/cover-not-exists.png")) return ""
        return url.toString()
    }
}
