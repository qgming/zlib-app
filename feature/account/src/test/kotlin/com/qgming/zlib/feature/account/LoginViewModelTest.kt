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
import com.qgming.zlib.core.model.DomainGroup
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @Test
    fun `community mirror cards stay visible and selection requires confirmation`() = runTest {
        withLogin { model ->
            val community = model.state.value.checks.filter { it.group == DomainGroup.COMMUNITY }
            assertEquals(34, community.size)
            assertEquals(17, model.state.value.checks.count { it.group == DomainGroup.OFFICIAL })
            val host = community.first().host

            model.selectDomain(host)
            assertNull(model.state.value.pendingMirrorHost)

            model.showDomains()
            model.selectDomain(host)
            advanceUntilIdle()

            assertEquals(host, model.state.value.pendingMirrorHost)
            assertEquals("z-lib.gd", model.state.value.domain)
            assertFalse(model.state.value.switchingDomain)
            assertTrue(model.state.value.checks.any { it.host == host })

            model.dismissCommunityMirror()
            assertNull(model.state.value.pendingMirrorHost)
            assertTrue(model.state.value.showDomainPicker)
            model.confirmCommunityMirror()
            advanceUntilIdle()
            assertEquals("z-lib.gd", model.state.value.domain)

            model.selectDomain(host)
            model.dismissDomains()
            assertNull(model.state.value.pendingMirrorHost)
            assertFalse(model.state.value.showDomainPicker)
            assertTrue(model.state.value.checks.any { it.host == host })
        }
    }

    @Test
    fun `confirming a community mirror approves and selects it after one anonymous check`() = runTest {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("""{"success":1,"domains":[{"domain":"z-lib.gd"}]}""".toResponseBody())
                .build()
        }.build()
        withLogin(client = client) { model ->
            val host = model.state.value.checks.first { it.group == DomainGroup.COMMUNITY }.host
            model.showDomains()
            model.selectDomain(host)
            advanceUntilIdle()
            assertTrue(requests.isEmpty())

            model.confirmCommunityMirror()
            model.confirmCommunityMirror()
            advanceUntilIdle()

            val request = requests.single()
            assertEquals("https://$host/eapi/info/domains", request.url.toString())
            assertNull(request.header("Cookie"))
            assertNull(request.header("remix-userid"))
            assertNull(request.header("remix-userkey"))
            assertEquals(host, model.state.value.domain)
            assertNull(model.state.value.pendingMirrorHost)
            assertFalse(model.state.value.showDomainPicker)
            assertFalse(model.state.value.switchingDomain)
            assertTrue(model.state.value.checks.any { it.host == host && it.healthy })
        }
    }

    @Test
    fun `selecting a challenged official mirror requests verification without switching`() = runTest {
        val client = challengeClient()
        withLogin(client = client) { model ->
            val verification = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.verificationRequests.collect { verification += it }
            }
            model.showDomains()
            model.probeDomain("z-library.im")
            advanceUntilIdle()
            assertEquals(DomainStatus.DIAMWALL, model.state.value.checks.first { it.host == "z-library.im" }.status)

            model.selectDomain("z-library.im")
            advanceUntilIdle()

            assertEquals(listOf("z-library.im"), verification)
            assertEquals("z-lib.gd", model.state.value.domain)
            assertFalse(model.state.value.showDomainPicker)
            assertFalse(model.state.value.switchingDomain)
        }
    }

    @Test
    fun `anonymous community challenge does not authorize and confirmation only starts verification`() = runTest {
        val requests = mutableListOf<Request>()
        withLogin(client = challengeClient(requests)) { model ->
            val verification = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.verificationRequests.collect { verification += it }
            }
            val host = model.state.value.checks.first { it.group == DomainGroup.COMMUNITY }.host
            model.showDomains()
            model.probeDomain(host)
            advanceUntilIdle()
            assertTrue(verification.isEmpty())
            assertEquals("z-lib.gd", model.state.value.domain)

            model.selectDomain(host)
            assertEquals(host, model.state.value.pendingMirrorHost)
            model.confirmCommunityMirror()
            model.confirmCommunityMirror()
            advanceUntilIdle()

            assertEquals(listOf(host), verification)
            assertEquals("z-lib.gd", model.state.value.domain)
            assertNull(model.state.value.pendingMirrorHost)
            assertFalse(model.state.value.switchingDomain)
            assertFalse(model.state.value.showDomainPicker)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun `newly discovered challenge after community consent opens verification without switching`() = runTest {
        withLogin(client = challengeClient()) { model ->
            val verification = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.verificationRequests.collect { verification += it }
            }
            val host = model.state.value.checks.first { it.group == DomainGroup.COMMUNITY }.host
            model.showDomains()
            model.selectDomain(host)
            model.confirmCommunityMirror()
            advanceUntilIdle()

            assertEquals(listOf(host), verification)
            assertEquals("z-lib.gd", model.state.value.domain)
            assertNull(model.state.value.domainError)
            assertFalse(model.state.value.showDomainPicker)
        }
    }

    @Test
    fun `unapproved discovered challenge keeps the picker open without requesting verification`() = runTest {
        val host = "discovered.example.org"
        withLogin(client = challengeClient(), discoveredHosts = setOf(host)) { model ->
            val verification = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                model.verificationRequests.collect { verification += it }
            }
            model.showDomains()
            model.probeDomain(host)
            advanceUntilIdle()
            assertEquals(DomainStatus.DIAMWALL, model.state.value.checks.first { it.host == host }.status)

            model.selectDomain(host)
            advanceUntilIdle()

            assertTrue(verification.isEmpty())
            assertEquals("z-lib.gd", model.state.value.domain)
            assertTrue(model.state.value.showDomainPicker)
            assertNotNull(model.state.value.domainError)
            assertNull(model.state.value.pendingMirrorHost)
        }
    }

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

    private fun challengeClient(requests: MutableList<Request> = mutableListOf()): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        assertNull(request.header("Cookie"))
        assertNull(request.header("remix-userid"))
        assertNull(request.header("remix-userkey"))
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(307)
            .message("Temporary Redirect")
            .header("Server", "DiamWall")
            .header("Location", request.url.toString())
            .body("".toResponseBody())
            .build()
    }.build()

    private suspend fun TestScope.withLogin(
        login: suspend (String, String) -> AppResult<Session> = { _, _ -> error("Unexpected password login") },
        importCookie: suspend (String) -> AppResult<Session> = { error("Unexpected Cookie import") },
        client: OkHttpClient = OkHttpClient(),
        discoveredHosts: Set<String> = emptySet(),
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
            val value = MutableStateFlow(AppSettings(currentHost = "z-lib.gd", discoveredHosts = discoveredHosts))
            override val settings = value
            override suspend fun read() = value.value
            override suspend fun setCurrentHost(host: String?) { value.value = value.value.copy(currentHost = host) }
            override suspend fun setCustomHosts(hosts: Set<String>) { value.value = value.value.copy(customHosts = hosts) }
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
        val registry = AllowedHostRegistry()
        val probe = DomainProbe(client, dispatchers, isAllowedHost = registry::isProbeAllowed, isTrustedHost = registry::isAllowed)
        val domains = DomainRepository(probe, settings, SessionManager(sessionStore, dispatchers), registry, dispatchers)
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
