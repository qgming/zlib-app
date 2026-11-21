package com.qgming.zlib.feature.search

import com.qgming.zlib.core.model.SearchQuery

data class SearchFilters(
    val languages: Set<String> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val yearFrom: String = "",
    val yearTo: String = "",
    val exact: Boolean = false,
    val order: String? = null,
) {
    val yearRangeValid: Boolean
        get() {
            val from = yearFrom.toIntOrNull()
            val to = yearTo.toIntOrNull()
            return from == null || to == null || from <= to
        }

    fun toQuery(message: String): SearchQuery = SearchQuery(
        message = message.trim(),
        languages = languages.sorted(),
        extensions = extensions.sorted(),
        yearFrom = yearFrom.toIntOrNull(),
        yearTo = yearTo.toIntOrNull(),
        exact = exact,
        order = order,
    )
}
