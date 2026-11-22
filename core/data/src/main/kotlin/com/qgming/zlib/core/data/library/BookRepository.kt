package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookFormat
import com.qgming.zlib.core.model.BookPage
import com.qgming.zlib.core.model.SearchQuery
import com.qgming.zlib.core.network.EapiClient
import com.qgming.zlib.core.data.session.SessionManager
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val api: EapiClient,
    sessions: SessionManager? = null,
) {
    val sessionChanges = sessions?.session ?: emptyFlow()
    suspend fun search(query: SearchQuery, page: Int): AppResult<BookPage> =
        api.search(query, page)

    suspend fun detail(id: String, hash: String): AppResult<Book> =
        api.bookDetail(id, hash)

    suspend fun formats(book: Book): AppResult<List<BookFormat>> = api.bookFormats(book.id, book.hash)

    suspend fun recommended(): AppResult<List<Book>> = api.recommended()

    suspend fun popular(): AppResult<List<Book>> = api.mostPopular()
}
