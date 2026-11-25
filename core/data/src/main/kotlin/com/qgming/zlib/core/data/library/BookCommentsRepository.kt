package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.model.BookCommentsPage
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.network.PapiCommentsClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookCommentsRepository @Inject constructor(
    private val client: PapiCommentsClient,
    private val sessions: SessionManager,
) {
    val session get() = sessions.session

    suspend fun comments(expected: Session, bookId: String, page: Int): AppResult<BookCommentsPage> =
        retryPapiRead(isCurrentSession = { sessions.current() == expected }) { client.comments(expected, bookId, page) }
}
