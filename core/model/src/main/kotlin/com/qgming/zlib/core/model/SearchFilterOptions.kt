package com.qgming.zlib.core.model

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

data class SearchLanguage(val code: String, val name: String)

data class SearchFilterOptions(
    val languages: List<SearchLanguage>,
    val extensions: List<String>,
) {
    companion object {
        val Fallback get() = SearchFilterOptions(
            languages = listOf(SearchLanguage("chinese", appText(TextR.string.text_chinese)), SearchLanguage("english", appText(TextR.string.text_english)), SearchLanguage("german", appText(TextR.string.text_german))),
            extensions = listOf("epub", "pdf", "mobi", "azw3", "fb2"),
        )
    }
}

/** Only values confirmed in the EAPI contract belong here. */
enum class SearchSort(val wireValue: String?, private val titleRes: Int) {
    DEFAULT(null, TextR.string.text_default_order),
    POPULAR("popular", TextR.string.text_most_popular),
    BEST_MATCH("bestmatch", TextR.string.text_best_match),
    RECENT("date", TextR.string.text_recently_added),
    TITLE_ASC("titleA", TextR.string.text_title_a_to_z),
    TITLE_DESC("title", TextR.string.text_title_z_to_a),
    YEAR_DESC("year", TextR.string.text_publication_year_newest_first),
    SIZE_DESC("filesize", TextR.string.text_file_size_largest_first),
    SIZE_ASC("filesizeA", TextR.string.text_file_size_smallest_first);

    val title: String get() = appText(titleRes)
}
