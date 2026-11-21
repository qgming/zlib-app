package com.qgming.zlib.core.data.session

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.model.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val dispatchers = object : AppDispatchers {
        override val io = dispatcher
        override val default = dispatcher
        override val main = dispatcher
    }

    private class FakeSessionStore(initial: Session? = null) : SessionStore {
        private val state = MutableStateFlow(initial)
        override val session: Flow<Session?> = state.asStateFlow()
        override suspend fun read(): Session? = state.value
        override suspend fun save(session: Session) {
            state.value = session
        }

        override suspend fun clear() {
            state.value = null
        }
    }

    private fun session(key: String = "key", domain: String = "z-lib.gd") = Session(
        remixUserId = "1",
        remixUserKey = key,
        domain = domain,
        cookies = mapOf("remix_userid" to "1", "remix_userkey" to key),
    )

    @Test
    fun `load exposes the persisted session`() = runTest {
        val store = FakeSessionStore(session())
        val manager = SessionManager(store, dispatchers)

        manager.load()

        assertEquals("1", manager.current()?.remixUserId)
        assertEquals("z-lib.gd", manager.current()?.domain)
    }

    @Test
    fun `save updates memory and store`() = runTest {
        val store = FakeSessionStore()
        val manager = SessionManager(store, dispatchers)

        manager.save(session(key = "new-key", domain = "z-library.sk"))

        assertEquals("new-key", manager.current()?.remixUserKey)
        assertEquals("z-library.sk", manager.session.value?.domain)
        assertEquals("new-key", store.read()?.remixUserKey)
    }

    @Test
    fun `clear removes the session from memory and store`() = runTest {
        val store = FakeSessionStore(session())
        val manager = SessionManager(store, dispatchers)
        manager.load()

        manager.clear()

        assertNull(manager.current())
        assertNull(manager.session.value)
        assertNull(store.read())
    }

    @Test
    fun `session keeps cookies when switching domain`() {
        val session = session()
        val switched = session.withDomain("z-library.ec")

        assertEquals("z-library.ec", switched.domain)
        assertEquals(session.cookies, switched.cookies)
        assertEquals(session.remixUserKey, switched.remixUserKey)
    }
}
