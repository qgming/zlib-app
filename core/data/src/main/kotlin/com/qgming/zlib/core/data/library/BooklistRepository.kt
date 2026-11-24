package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.getOrNull
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.BooklistPage
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.PapiBooklistClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class BooklistRepository @Inject constructor(
    private val client: PapiBooklistClient,
    private val sessions: SessionManager,
) {
    val session get() = sessions.session
    private val previewPermits = Semaphore(2)

    suspend fun myBooklists(expected: Session): AppResult<List<CloudBooklist>> =
        withSession(expected) { client.myBooklists(expected) }

    suspend fun books(expected: Session, id: String, page: Int): AppResult<BooklistPage> =
        withSession(expected) { client.books(expected, id, page) }

    suspend fun previewCovers(expected: Session, id: String): List<BooklistCover> = previewPermits.withPermit {
        books(expected, id, 1).getOrNull()?.books.orEmpty().asSequence()
            .filter { it.coverUrl.isNotBlank() }.distinctBy(Book::id).take(BooklistCover.COUNT)
            .map { BooklistCover(it.id, it.coverUrl) }.toList()
    }

    private suspend fun <T> withSession(expected: Session, read: suspend () -> AppResult<T>): AppResult<T> {
        if (sessions.current() != expected) return AppResult.Failure(AppError.NotLoggedIn)
        val result = read()
        return if (sessions.current() == expected) result else AppResult.Failure(AppError.NotLoggedIn)
    }
}
