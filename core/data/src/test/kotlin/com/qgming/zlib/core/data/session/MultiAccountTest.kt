package com.qgming.zlib.core.data.session

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.account.AccountQuotaSwitcher
import com.qgming.zlib.core.data.crypto.SecretCipher
import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Quota
import com.qgming.zlib.core.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MultiAccountTest {
    private class MemoryStore : SessionStore {
        var value = SessionVault()
        override suspend fun readVault() = value
        override suspend fun saveVault(vault: SessionVault) { value = vault }
        override suspend fun clear() { value = SessionVault() }
    }
    private fun TestScope.manager(store: MemoryStore = MemoryStore()): SessionManager {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SessionManager(store, object : AppDispatchers {
            override val main = dispatcher; override val io = dispatcher; override val default = dispatcher
        })
    }
    private fun session(id: String) = Session(id, "key-$id", "z-lib.sk", mapOf("remix_userid" to id, "remix_userkey" to "key-$id"))
    private fun profile(id: String, remaining: Int? = 3) = Account(id, "Name $id", "$id@example.com", false, true,
        Quota(if (remaining == null) null else 10 - remaining, if (remaining == null) null else 10, remaining))

    @Test fun `multiple sessions survive reload and repeated login updates by user id`() = runTest {
        val store = MemoryStore(); val manager = manager(store)
        manager.add(session("1"), profile("1")); manager.add(session("2"), profile("2"))
        manager.add(session("1").copy(remixUserKey = "replacement"), profile("1"))
        assertEquals(2, manager.vault.value.entries.size)
        val reload = manager(store); reload.load()
        assertEquals("replacement", reload.current()?.remixUserKey)
        assertEquals(listOf("1", "2"), reload.vault.value.entries.map { it.session.remixUserId })
        assertEquals("2@example.com", reload.vault.value.entries.last().profile?.email)
    }

    @Test fun `old profile and expiration cannot overwrite or remove another active session`() = runTest {
        val manager = manager(); val old = session("1")
        manager.add(old, profile("1")); manager.add(session("2"), profile("2"))
        manager.expire(old)
        assertEquals("2", manager.current()?.remixUserId)
        assertTrue(manager.vault.value.entries.first().expired)
        manager.add(old.copy(remixUserKey = "new"), profile("1"))
        assertFalse(manager.updateProfile(old, profile("1", 0)))
        assertEquals(3, manager.vault.value.entries.first().profile?.quota?.remaining)
        assertFalse(manager.activate("2", expected = old))
    }

    @Test fun `logout removes only selected account and chooses another valid session`() = runTest {
        val manager = manager()
        manager.add(session("1")); manager.add(session("2")); manager.remove("2")
        assertEquals("1", manager.current()?.remixUserId)
        manager.remove("1")
        assertNull(manager.current()); assertTrue(manager.vault.value.entries.isEmpty())
    }

    @Test fun `domain update never resurrects a previously active account`() = runTest {
        val manager = manager(); val old = session("1")
        manager.add(old); manager.add(session("2"))
        manager.updateDomain(old, "z-lib.gd")
        assertEquals("2", manager.current()?.remixUserId)
        assertEquals("z-lib.sk", manager.current()?.domain)
    }

    @Test fun `vault codec encrypts all credentials and profiles and rejects corrupt state`() {
        val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
            override fun decrypt(ciphertext: ByteArray) = encrypt(ciphertext)
        }
        val codec = SessionVaultCodec(cipher)
        val vault = SessionVault(listOf(SessionEntry(session("1"), profile("1")), SessionEntry(session("2"), profile("2"), true)), "1", true)
        val encoded = codec.encode(vault)
        assertFalse(encoded.contains("key-1")); assertFalse(encoded.contains("example.com"))
        assertEquals(vault, codec.decode(encoded))
        assertEquals(SessionVault(), codec.decode("invalid"))
        assertEquals(SessionVault(), codec.decode(null))
    }

    @Test fun `auto switch defaults off and performs no profile requests`() = runTest {
        val manager = manager(); manager.add(session("1")); manager.add(session("2")); manager.activate("1")
        var calls = 0
        val switcher = AccountQuotaSwitcher(manager) { calls++; AppResult.Success(profile(it.remixUserId)) }
        assertFalse(switcher.switchAfterQuota(session("1"), mutableSetOf()))
        assertEquals(0, calls)
    }

    @Test fun `auto switch checks server quota skips invalid and empty accounts and selects available account once`() = runTest {
        val manager = manager()
        (1..4).forEach { manager.add(session("$it")) }; manager.activate("1"); manager.setAutoSwitch(true)
        val calls = mutableListOf<String>()
        val switcher = AccountQuotaSwitcher(manager) {
            calls += it.remixUserId
            if (it.remixUserId == "2") AppResult.Failure(AppError.SessionExpired)
            else AppResult.Success(profile(it.remixUserId, if (it.remixUserId == "4") 2 else 0))
        }
        val attempted = mutableSetOf<String>()
        assertTrue(switcher.switchAfterQuota(session("1"), attempted))
        assertEquals("4", manager.current()?.remixUserId)
        assertEquals(listOf("1", "2", "3", "4"), calls)
        assertTrue(manager.vault.value.entries[1].expired)
        assertFalse(switcher.switchAfterQuota(session("4"), attempted)) // Current server quota is positive.
    }

    @Test fun `unknown quota and network errors do not switch`() = runTest {
        val manager = manager(); manager.add(session("1")); manager.add(session("2")); manager.activate("1"); manager.setAutoSwitch(true)
        val unknown = AccountQuotaSwitcher(manager) { AppResult.Success(profile(it.remixUserId, null)) }
        assertFalse(unknown.switchAfterQuota(session("1"), mutableSetOf()))
        val failed = AccountQuotaSwitcher(manager) { AppResult.Failure(AppError.Network(java.io.IOException())) }
        assertFalse(failed.switchAfterQuota(session("1"), mutableSetOf()))
        assertEquals("1", manager.current()?.remixUserId)
    }

    @Test fun `manual selection during quota check wins over automatic response`() = runTest {
        val manager = manager(); manager.add(session("1")); manager.add(session("2")); manager.add(session("3")); manager.activate("1"); manager.setAutoSwitch(true)
        val switcher = AccountQuotaSwitcher(manager) {
            if (it.remixUserId == "2") manager.activate("3")
            AppResult.Success(profile(it.remixUserId, if (it.remixUserId == "1") 0 else 3))
        }
        assertFalse(switcher.switchAfterQuota(session("1"), mutableSetOf()))
        assertEquals("3", manager.current()?.remixUserId)
    }
}
