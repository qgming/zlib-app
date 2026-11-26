package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.network.EapiClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.model.Book

/** Cloud favorites are never stored as independent local flags. */
@Singleton
class FavoritesRepository internal constructor(
    private val api: EapiClient,
    private val sessions: SessionManager,
    private val nanoTime: () -> Long,
) {
    @Inject constructor(api: EapiClient, sessions: SessionManager) : this(api, sessions, System::nanoTime)
    private val _changes = MutableStateFlow(0L)
    val changes = _changes.asStateFlow()
    private val queryMutex = Mutex()
    private val writeMutex = Mutex()
    private val confirmed = mutableMapOf<String, Pair<Boolean, Long>>()
    private val collectedIds = mutableSetOf<String>()
    private var nextPage = 1
    private var totalPages: Int? = null
    private var indexUntil: Long? = null
    private var cacheSession: Session? = null
    private var revision = 0L
    private var writing = false

    @Synchronized private fun resetForSession() {
        if (cacheSession != sessions.current()) {
            cacheSession = sessions.current()
            invalidate()
        }
    }

    @Synchronized fun invalidate() {
        confirmed.clear()
        collectedIds.clear()
        nextPage = 1
        totalPages = null
        indexUntil = null
        revision++
    }

    @Synchronized fun rememberSaved(books: List<Book>) {
        resetForSession()
        if (cacheSession == null || writing) return
        val now = nanoTime()
        confirmed.entries.removeAll { now >= it.value.second }
        books.forEach { confirmed[it.id] = true to (now + CACHE_NANOS) }
    }

    suspend fun isSaved(id: String): AppResult<Boolean> {
        if (id.isEmpty() || id.any { it !in '0'..'9' }) {
            return AppResult.Failure(AppError.Parse(appText(TextR.string.text_favorites_require_a_numeric_book_id)))
        }
        val session = sessions.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
            queryMutex.withLock { lookup(id, session) }
        } ?: membershipUnknown()
    }

    private suspend fun lookup(id: String, session: Session): AppResult<Boolean> {
        val generation = synchronized(this) {
            resetForSession()
            if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
            if (writing) return membershipUnknown()
            if (indexUntil?.let { nanoTime() >= it } == true) invalidate()
            revision
        }
        repeat(PAGES_PER_LOOKUP) {
            val page = synchronized(this) {
                if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
                if (generation != revision || writing) return membershipUnknown()
                confirmed[id]?.takeIf { nanoTime() < it.second }?.let { return AppResult.Success(it.first) }
                if (id in collectedIds) return AppResult.Success(true)
                if (totalPages?.let { nextPage > it } == true) return AppResult.Success(false)
                if (nextPage > MAX_INDEX_PAGES) return membershipUnknown()
                nextPage
            }
            when (val result = api.favoriteIdPage(page, session)) {
                is AppResult.Failure -> return result
                is AppResult.Success -> synchronized(this) {
                    if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
                    if (generation != revision || writing) return membershipUnknown()
                    val slice = result.value
                    if (totalPages?.let { it != slice.totalPages } == true || slice.ids.any(collectedIds::contains)) {
                        invalidate()
                        return membershipUnknown()
                    }
                    if (indexUntil == null) indexUntil = nanoTime() + CACHE_NANOS
                    collectedIds.addAll(slice.ids)
                    totalPages = slice.totalPages
                    nextPage++
                    if (id in collectedIds) return AppResult.Success(true)
                    if (nextPage > slice.totalPages) return AppResult.Success(false)
                }
            }
        }
        return membershipUnknown()
    }

    suspend fun setSaved(id: String, saved: Boolean): AppResult<Unit> {
        val session = sessions.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        return writeMutex.withLock {
            synchronized(this) {
                if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
                resetForSession()
                writing = true
                invalidate()
            }
            var applied = false
            try {
                val result = api.setBookSaved(id, saved, session)
                applied = result is AppResult.Success
                if (sessions.current() == session) result else AppResult.Failure(AppError.SessionExpired)
            } finally {
                synchronized(this) {
                    invalidate()
                    writing = false
                    if (applied && sessions.current() == session) {
                        confirmed[id] = saved to (nanoTime() + CACHE_NANOS)
                        _changes.update { it + 1 }
                    }
                }
            }
        }
    }

    private fun membershipUnknown() = AppResult.Failure(AppError.Parse(appText(TextR.string.text_favorite_status_not_confirmed_retry)))

    private companion object {
        const val CACHE_NANOS = 30_000_000_000L
        const val PAGES_PER_LOOKUP = 8
        const val MAX_INDEX_PAGES = 120
        const val LOOKUP_TIMEOUT_MILLIS = 8_000L
    }
}
