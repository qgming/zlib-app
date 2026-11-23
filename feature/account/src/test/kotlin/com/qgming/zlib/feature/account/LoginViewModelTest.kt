package com.qgming.zlib.feature.account

import androidx.lifecycle.ViewModelStore
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.network.DomainProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Test
    fun `switching login method clears secrets and errors while keeping email`() = runTest {
        withLogin { model ->
            model.onEmailChange("reader@example.org")
            model.onPasswordChange("memory-only")
            model.onCookieTextChange("memory-cookie")
            model.toggleCookieImport()
            assertTrue(model.state.value.showCookieImport)
            assertEquals("", model.state.value.password)
            assertEquals("", model.state.value.cookieText)
            assertEquals("reader@example.org", model.state.value.email)

            model.importCookieText()
            assertNotNull(model.state.value.error)
            model.toggleCookieImport()
            assertFalse(model.state.value.showCookieImport)
            assertNull(model.state.value.error)
        }
    }

    @Test
    fun `picker switches to selected unchecked host and keeps invalid selection errors inside panel`() = runTest {
        withLogin { model ->
            model.showDomains()
            assertTrue(model.state.value.showDomainPicker)
            model.selectDomain("http://z-lib.sk")
            advanceUntilIdle()
            assertTrue(model.state.value.showDomainPicker)
            assertNotNull(model.state.value.domainError)
            assertEquals("z-lib.gd", model.state.value.domain)
            assertNull(model.state.value.error)

            model.selectDomain("z-library.im")
            advanceUntilIdle()
            assertEquals("z-library.im", model.state.value.domain)
            assertTrue(model.state.value.checks.all { it.status == DomainStatus.UNKNOWN })
            assertFalse(model.state.value.showDomainPicker)
            assertFalse(model.state.value.switchingDomain)
            assertNull(model.state.value.domainError)
        }
    }

    @Test
    fun `cookie import submits once disables method changes and clears credentials after failure`() = runTest {
        val response = CompletableDeferred<AppResult<Session>>()
        val calls = mutableListOf<String>()
        withLogin(importCookie = { calls += it; response.await() }) { model ->
            model.toggleCookieImport()
            model.onCookieTextChange("test-cookie")
            model.importCookieText()
            model.importCookieText()
            model.toggleCookieImport()
            advanceUntilIdle()
            assertEquals(listOf("test-cookie"), calls)
            assertTrue(model.state.value.loading)
            assertTrue(model.state.value.showCookieImport)
            model.showDomains()
            assertFalse(model.state.value.showDomainPicker)

            response.complete(AppResult.Failure(AppError.AuthFailed("Cookie 已失效")))
            advanceUntilIdle()
            assertFalse(model.state.value.loading)
            assertEquals("", model.state.value.cookieText)
            assertNotNull(model.state.value.error)
        }
    }

    @Test
    fun `password login and storage exceptions finish loading and clear password`() = runTest {
        val calls = mutableListOf<Pair<String, String>>()
        withLogin(login = { email, password ->
            calls += email to password
            throw IllegalStateException("save failed")
        }) { model ->
            model.onEmailChange("reader@example.org")
            model.onPasswordChange("memory-only")
            model.login()
            model.login()
            advanceUntilIdle()
            assertEquals(listOf("reader@example.org" to "memory-only"), calls)
            assertFalse(model.state.value.loading)
            assertEquals("", model.state.value.password)
            assertNotNull(model.state.value.error)
        }
    }

    private suspend fun TestScope.withLogin(
        login: suspend (String, String) -> AppResult<Session> = { _, _ -> error("Unexpected password login") },
        importCookie: suspend (String) -> AppResult<Session> = { error("Unexpected Cookie import") },
        block: suspend (LoginViewModel) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val dispatchers = object : AppDispatchers {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
        }
        val settings = object : SettingsStore {
            val value = MutableStateFlow(AppSettings(currentHost = "z-lib.gd"))
            override val settings = value
            override suspend fun read() = value.value
            override suspend fun setCurrentHost(host: String?) { value.value = value.value.copy(currentHost = host) }
            override suspend fun setCustomHosts(hosts: Set<String>) = Unit
            override suspend fun setDiscoveredHosts(hosts: Set<String>) = Unit
            override suspend fun setLockedHost(host: String?) = Unit
            override suspend fun setThemeMode(mode: ThemeMode) = Unit
            override suspend fun setBookGridColumns(columns: BookGridColumns) = Unit
            override suspend fun setDownloadTreeUri(uri: String?) = Unit
        }
        val sessionStore = object : SessionStore {
            override suspend fun readVault() = SessionVault()
            override suspend fun saveVault(vault: SessionVault) = Unit
            override suspend fun clear() = Unit
        }
        val domains = DomainRepository(
            DomainProbe(OkHttpClient(), dispatchers), settings,
            SessionManager(sessionStore, dispatchers), AllowedHostRegistry(), dispatchers,
        )
        val store = ViewModelStore()
        try {
            domains.initialize()
            val model = LoginViewModel(domains, login, importCookie)
            store.put("login", model)
            advanceUntilIdle()
            block(model)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
