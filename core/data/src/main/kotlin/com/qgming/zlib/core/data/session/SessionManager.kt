package com.qgming.zlib.core.data.session

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.model.Session
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory, synchronous view of the single account session. Loaded once at startup;
 * every network call reads it through [current] without blocking.
 */
@Singleton
class SessionManager @Inject constructor(
    private val store: SessionStore,
    private val dispatchers: AppDispatchers,
) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val loadLock = Mutex()
    @Volatile
    private var loaded = false

    fun current(): Session? = _session.value

    suspend fun load() {
        if (loaded) return
        loadLock.withLock {
            if (loaded) return
            _session.value = withContext(dispatchers.io) { store.read() }
            loaded = true
        }
    }

    suspend fun save(session: Session) {
        withContext(dispatchers.io) { store.save(session) }
        _session.value = session
        loaded = true
    }

    suspend fun clear() {
        withContext(dispatchers.io) { store.clear() }
        _session.value = null
        loaded = true
    }
}
