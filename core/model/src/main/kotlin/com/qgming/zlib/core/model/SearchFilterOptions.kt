package com.qgming.zlib.core.model

data class SearchLanguage(val code: String, val name: String)

data class SearchFilterOptions(
    val languages: List<SearchLanguage>,
    val extensions: List<String>,
) {
    companion object {
        val Fallback = SearchFilterOptions(
            languages = listOf(SearchLanguage("chinese", "中文"), SearchLanguage("english", "英语"), SearchLanguage("german", "德语")),
            extensions = listOf("epub", "pdf", "mobi", "azw3", "fb2"),
        )
    }
}

/** Only values confirmed in the EAPI contract belong here. */
enum class SearchSort(val wireValue: String?, val title: String) {
    DEFAULT(null, "默认排序"),
    POPULAR("popular", "热门优先"),
    BEST_MATCH("bestmatch", "最佳匹配"),
    RECENT("date", "最近添加"),
    TITLE_ASC("titleA", "书名升序"),
    TITLE_DESC("title", "书名降序"),
    YEAR_DESC("year", "出版年份由新到旧"),
    SIZE_DESC("filesize", "文件由大到小"),
    SIZE_ASC("filesizeA", "文件由小到大"),
}
