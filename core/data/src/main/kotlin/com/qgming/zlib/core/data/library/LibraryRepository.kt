package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookPage
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val api: EapiClient,
) {
    suspend fun saved(page: Int): AppResult<BookPage> = api.savedBooks(page)

    suspend fun history(page: Int): AppResult<BookPage> = api.downloadHistory(page)

    suspend fun detail(book: Book): AppResult<Book> = api.bookDetail(book.id, book.hash)
}
