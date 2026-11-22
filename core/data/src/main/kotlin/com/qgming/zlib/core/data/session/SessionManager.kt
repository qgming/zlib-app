package com.qgming.zlib.core.data.session

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Session
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class SessionManager @Inject constructor(private val store: SessionStore, private val dispatchers: AppDispatchers) {
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()
    private val _vault = MutableStateFlow(SessionVault())
    val vault = _vault.asStateFlow()
    private val lock = Mutex()
    @Volatile private var loaded = false
    fun current(): Session? = _session.value

    suspend fun load() = lock.withLock {
        if (!loaded) { publish(withContext(dispatchers.io) { store.readVault() }); loaded = true }
    }

    suspend fun save(session: Session) = add(session)
    suspend fun add(session: Session, profile: Account? = null) = lock.withLock {
        require(session.isValid && (profile == null || profile.id == session.remixUserId))
        val old = _vault.value.entries.firstOrNull { it.session.remixUserId == session.remixUserId }
        val entry = SessionEntry(session, profile ?: old?.profile)
        val rows = if (old == null) _vault.value.entries + entry else _vault.value.entries.map {
            if (it.session.remixUserId == session.remixUserId) entry else it
        }
        persist(_vault.value.copy(entries = rows, activeId = session.remixUserId))
    }

    suspend fun updateDomain(expected: Session, host: String) = lock.withLock {
        if (current() == expected) {
            persist(_vault.value.copy(entries = _vault.value.entries.map {
                if (it.session == expected) it.copy(session = expected.withDomain(host)) else it
            }))
        }
    }

    suspend fun updateProfile(expected: Session, profile: Account): Boolean = lock.withLock {
        if (_vault.value.entries.none { it.session == expected } || profile.id != expected.remixUserId) return@withLock false
        persist(_vault.value.copy(entries = _vault.value.entries.map { if (it.session == expected) it.copy(profile = profile, expired = false) else it }))
        true
    }

    suspend fun activate(id: String, host: String? = null, expected: Session? = current()): Boolean = lock.withLock {
        if (current() != expected) return@withLock false
        val entry = _vault.value.entries.firstOrNull { it.session.remixUserId == id && !it.expired } ?: return@withLock false
        persist(_vault.value.copy(activeId = id, entries = _vault.value.entries.map {
            if (it === entry && host != null) it.copy(session = it.session.withDomain(host)) else it
        }))
        true
    }

    suspend fun expire(expected: Session) = lock.withLock {
        if (_vault.value.entries.none { it.session == expected }) return@withLock
        persist(_vault.value.copy(
            activeId = _vault.value.activeId.takeUnless { current() == expected },
            entries = _vault.value.entries.map { if (it.session == expected) it.copy(expired = true) else it },
        ))
    }

    suspend fun remove(id: String) = lock.withLock {
        val rows = _vault.value.entries.filterNot { it.session.remixUserId == id }
        val active = if (_vault.value.activeId == id) rows.firstOrNull { !it.expired }?.session?.remixUserId else _vault.value.activeId
        persist(_vault.value.copy(entries = rows, activeId = active))
    }

    suspend fun setAutoSwitch(enabled: Boolean) = lock.withLock { persist(_vault.value.copy(autoSwitch = enabled)) }
    suspend fun clear() = lock.withLock { withContext(dispatchers.io) { store.clear() }; publish(SessionVault()); loaded = true }
    private suspend fun persist(vault: SessionVault) = withContext(NonCancellable) {
        withContext(dispatchers.io) { store.saveVault(vault) }
        publish(vault)
        loaded = true
    }
    private fun publish(vault: SessionVault) { _vault.value = vault; _session.value = vault.current }
}
