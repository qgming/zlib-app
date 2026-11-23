package com.qgming.zlib.core.data.library

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
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private var cacheSession: Session? = null
    private val revision = java.util.concurrent.atomic.AtomicLong(0)

    @Synchronized private fun resetForSession() {
        if (cacheSession != sessions.current()) {
            cacheSession = sessions.current()
            invalidate()
        }
    }

    @Synchronized fun invalidate() { cache.clear(); revision.incrementAndGet() }

    @Synchronized fun rememberSaved(books: List<Book>) {
        resetForSession()
        if (cacheSession == null) return
        val until = nanoTime() + 30_000_000_000L
        books.forEach { cache[it.id] = true to until }
    }

    suspend fun isSaved(id: String): AppResult<Boolean> = queryMutex.withLock {
        resetForSession()
        val session = sessions.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        cache[id]?.takeIf { nanoTime() < it.second }?.let { return AppResult.Success(it.first) }
        val requestedRevision = revision.get()
        val result = api.isBookSaved(id)
        synchronized(this) {
            if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
            if (requestedRevision != revision.get()) return AppResult.Failure(AppError.Parse("收藏已更新，请重试"))
            if (result is AppResult.Success) cache[id] = result.value to (nanoTime() + 30_000_000_000L)
            result
        }
    }

    suspend fun setSaved(id: String, saved: Boolean): AppResult<Unit> = queryMutex.withLock {
        val session = sessions.current() ?: return AppResult.Failure(AppError.NotLoggedIn)
        resetForSession()
        invalidate()
        val result = api.setBookSaved(id, saved)
        synchronized(this) {
            if (sessions.current() != session) return AppResult.Failure(AppError.SessionExpired)
            if (result is AppResult.Success) {
                invalidate()
                cache[id] = saved to (nanoTime() + 30_000_000_000L)
                _changes.update { it + 1 }
            }
        }
        result
    }
}
