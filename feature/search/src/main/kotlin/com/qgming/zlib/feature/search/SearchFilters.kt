package com.qgming.zlib.feature.search

import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.SearchSort
import com.qgming.zlib.core.model.SearchFilterOptions

data class SearchFilters(
    val languages: Set<String> = emptySet(),
    val extensions: Set<String> = emptySet(),
    val yearFrom: String = "",
    val yearTo: String = "",
    val exact: Boolean = false,
    val order: String? = null,
) {
    val isActive: Boolean
        get() = exact || languages.isNotEmpty() || extensions.isNotEmpty() ||
            yearFrom.toIntOrNull() != null || yearTo.toIntOrNull() != null || order != null

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
        order = order?.takeIf { value -> SearchSort.entries.any { it.wireValue == value } },
    )

    fun validated(options: SearchFilterOptions): SearchFilters = copy(
        languages = languages.map(String::lowercase).intersect(options.languages.map { it.code }.toSet()),
        extensions = extensions.map(String::lowercase).intersect(options.extensions.toSet()),
        order = order?.takeIf { value -> SearchSort.entries.any { it.wireValue == value } },
    )

    fun toggleLanguage(language: String): SearchFilters = copy(languages = languages.toggled(language))

    fun toggleExtension(extension: String): SearchFilters = copy(extensions = extensions.toggled(extension))

    fun withYearFrom(value: String): SearchFilters = copy(yearFrom = value.filter(Char::isDigit).take(4))

    fun withYearTo(value: String): SearchFilters = copy(yearTo = value.filter(Char::isDigit).take(4))
}

private fun Set<String>.toggled(item: String): Set<String> = if (item in this) this - item else this + item
