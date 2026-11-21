package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.model.SearchResult
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val api: EapiClient,
) {
    suspend fun search(query: SearchQuery, page: Int): AppResult<SearchResult> =
        api.search(query, page)

    suspend fun detail(id: String, hash: String): AppResult<Book> =
        api.bookDetail(id, hash)

    suspend fun recommended(): AppResult<List<Book>> = api.recommended()

    suspend fun popular(): AppResult<List<Book>> = api.mostPopular()
}
