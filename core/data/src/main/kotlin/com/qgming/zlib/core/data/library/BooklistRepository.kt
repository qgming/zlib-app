package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.getOrNull
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.BooklistMembership
import com.qgming.zlib.core.model.BooklistPage
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.PapiBooklistClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class BooklistRepository @Inject constructor(
    private val client: PapiBooklistClient,
    private val sessions: SessionManager,
) {
    val session get() = sessions.session
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private val previewPermits = Semaphore(2)

    suspend fun myBooklists(expected: Session): AppResult<List<CloudBooklist>> =
        readWithSession(expected) { client.myBooklists(expected) }

    suspend fun books(expected: Session, id: String, page: Int): AppResult<BooklistPage> =
        readWithSession(expected) { client.books(expected, id, page) }

    suspend fun memberships(expected: Session, bookId: String): AppResult<List<BooklistMembership>> =
        readWithSession(expected) { client.memberships(expected, bookId) }

    suspend fun addBook(expected: Session, membership: BooklistMembership, bookId: String): AppResult<Unit> =
        withSession(expected) {
            if (membership.isMember) return@withSession AppResult.Success(Unit)
            client.addBook(expected, membership.booklistId, bookId).also { result ->
                if (result is AppResult.Success) invalidate(expected)
            }
        }

    suspend fun removeBook(expected: Session, membership: BooklistMembership, bookId: String): AppResult<Unit> =
        withSession(expected) {
            if (!membership.isMember) return@withSession AppResult.Success(Unit)
            val resolvedId = membership.readlistBookId?.takeIf(PAPI_ID::matches) ?: when (val refreshed = memberships(expected, bookId)) {
                is AppResult.Failure -> return@withSession refreshed
                is AppResult.Success -> {
                    val latest = refreshed.value.firstOrNull { it.booklistId == membership.booklistId }
                    if (latest == null || !latest.isMember) {
                        invalidate(expected)
                        return@withSession AppResult.Success(Unit)
                    }
                    latest.readlistBookId?.takeIf(PAPI_ID::matches)
                        ?: return@withSession AppResult.Failure(
                            AppError.Parse(appText(TextR.string.text_unexpected_server_response_please_retry_later)),
                        )
                }
            }
            if (sessions.current() != expected) return@withSession AppResult.Failure(AppError.NotLoggedIn)
            client.removeBook(expected, membership.booklistId, resolvedId).also { result ->
                if (result is AppResult.Success) invalidate(expected)
            }
        }

    suspend fun createBooklist(expected: Session, title: String, bookId: String): AppResult<String?> =
        withSession(expected) { client.createBooklist(expected, title, bookId) }

    /** Invalidate the in-memory list and previews after a confirmed mutation. */
    fun invalidate(expected: Session) {
        if (sessions.current() == expected) _revision.update { it + 1 }
    }

    fun previews(scope: CoroutineScope): BooklistPreviews =
        BooklistPreviews(scope, isCurrentSession = { session.value == it }, readCovers = ::previewCovers)

    suspend fun previewCovers(expected: Session, id: String): List<BooklistCover>? = previewPermits.withPermit {
        books(expected, id, 1).getOrNull()?.books?.coverPreviews()
    }

    private fun List<Book>.coverPreviews(): List<BooklistCover> = asSequence()
        .filter { it.coverUrl.isNotBlank() }.distinctBy(Book::id).take(BooklistCover.COUNT)
        .map { BooklistCover(it.id, it.coverUrl) }.toList()

    private suspend fun <T> readWithSession(expected: Session, read: suspend () -> AppResult<T>): AppResult<T> =
        retryPapiRead(isCurrentSession = { sessions.current() == expected }, read = read)

    private suspend fun <T> withSession(expected: Session, request: suspend () -> AppResult<T>): AppResult<T> {
        if (sessions.current() != expected) return AppResult.Failure(AppError.NotLoggedIn)
        val result = request()
        return if (sessions.current() == expected) result else AppResult.Failure(AppError.NotLoggedIn)
    }

    private companion object {
        val PAPI_ID = Regex("[1-9][0-9]{0,19}")
    }
}
