package com.qgming.zlib.core.data.startup

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.data.account.AccountRepository
import com.qgming.zlib.core.data.domain.AllowedHostRegistry
import com.qgming.zlib.core.data.domain.DomainRepository
import com.qgming.zlib.core.data.session.SessionEntry
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainProbe
import com.qgming.zlib.core.network.EapiClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCoordinatorTest {
    @Test fun `missing directory gates login before any network request`() = runTest {
        withFixture(AppSettings(currentHost = "z-lib.sk")) {
            coordinator.start()
            assertIs<StartupState.DirectoryRequired>(coordinator.state.value)
            assertEquals(0, probeServer.requestCount)
            assertEquals(0, apiServer.requestCount)
        }
    }

    @Test fun `remembered healthy host is verified once and enters ready without awaiting other mirrors`() = runTest {
        withFixture(AppSettings(currentHost = "z-lib.sk", downloadTreeUri = "content://test/tree/books")) {
            coordinator.start()
            assertIs<StartupState.Ready>(coordinator.state.value)
            assertEquals("z-lib.sk", domains.currentHostOrNull())
            assertEquals(1, probeServer.requestCount)
            assertEquals("/z-lib.sk/eapi/info/domains", probeServer.takeRequest().path)
            assertEquals(0, apiServer.requestCount)
            assertTrue(domains.isHealthy("z-lib.sk"))
            assertEquals(DomainStatus.UNKNOWN, domains.checks.value.first { it.host == "zlib.bz" }.status)
        }
    }

    @Test fun `remembered unhealthy host enters ready as soon as first healthy candidate finishes`() = runBlocking {
        val gate = CountDownLatch(1)
        val fixture = Fixture(
            initial = AppSettings(currentHost = "z-lib.sk", downloadTreeUri = "content://test/tree/books"),
            useRealIo = true,
        )
        try {
            fixture.unhealthy += "z-lib.sk"
            val fastHost = "z-lib.gd"
            val slowHosts = (DomainPolicy.DEFAULT_PROBE_HOSTS - setOf("z-lib.sk", fastHost)) + DomainPolicy.COMMUNITY_MIRROR_HOSTS
            slowHosts.forEach { fixture.gates[it] = gate }

            fixture.coordinator.start()

            assertIs<StartupState.Ready>(fixture.coordinator.state.value)
            assertEquals(fastHost, fixture.domains.currentHostOrNull())
            assertEquals(fastHost, fixture.sessions.current()?.domain)
            assertTrue(fixture.domains.isHealthy(fastHost))
            assertEquals(DomainStatus.HTTP_ERROR, fixture.domains.checks.value.first { it.host == "z-lib.sk" }.status)
            assertEquals(DomainStatus.UNKNOWN, fixture.domains.checks.value.first { it.host == "zlib.bz" }.status)
        } finally {
            gate.countDown()
            fixture.close()
        }
    }

    @Test fun `locked unhealthy host does not auto switch and fails startup`() = runTest {
        withFixture(
            AppSettings(
                currentHost = "z-lib.sk",
                lockedHost = "z-lib.sk",
                downloadTreeUri = "content://test/tree/books",
            ),
        ) {
            unhealthy += "z-lib.sk"
            coordinator.start()

            val failed = assertIs<StartupState.Failed>(coordinator.state.value)
            assertEquals(AppError.NoDomain, failed.error)
            assertNull(domains.currentHostOrNull())
            assertEquals("z-lib.sk", store.value.value.lockedHost)
            assertEquals(1, probeServer.requestCount)
            assertEquals("/z-lib.sk/eapi/info/domains", probeServer.takeRequest().path)
        }
    }

    @Test fun `all candidates failing transitions startup to failed`() = runTest {
        withFixture(AppSettings(currentHost = "z-lib.sk", downloadTreeUri = "content://test/tree/books")) {
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS
            unhealthy += DomainPolicy.COMMUNITY_MIRROR_HOSTS

            coordinator.start()

            val failed = assertIs<StartupState.Failed>(coordinator.state.value)
            assertEquals(AppError.NoDomain, failed.error)
            assertNull(domains.currentHostOrNull())
        }
    }

    @Test fun `background ranking probe populates latencies without switching away from active healthy host`() = runTest {
        withFixture(AppSettings(currentHost = "z-lib.sk", downloadTreeUri = "content://test/tree/books")) {
            delaysMs["z-lib.sk"] = 40L
            coordinator.start()

            assertIs<StartupState.Ready>(coordinator.state.value)
            assertEquals("z-lib.sk", domains.currentHostOrNull())
            assertEquals(1, probeServer.requestCount)

            coordinator.probeInBackground()

            // Active healthy host is preserved even if other mirrors answered faster.
            assertEquals("z-lib.sk", domains.currentHostOrNull())
            assertEquals("z-lib.sk", sessions.current()?.domain)
            val totalCatalog = DomainPolicy.DEFAULT_PROBE_HOSTS.size + DomainPolicy.COMMUNITY_MIRROR_HOSTS.size
            assertEquals(totalCatalog, probeServer.requestCount)
            val officialHealthy = domains.checks.value.filter { it.healthy && it.host in DomainPolicy.DEFAULT_PROBE_HOSTS }
            assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS.size, officialHealthy.size)
            assertTrue(officialHealthy.all { it.latencyMs != null })
            assertEquals(
                officialHealthy.map { it.latencyMs ?: Long.MAX_VALUE }.sorted(),
                officialHealthy.map { it.latencyMs ?: Long.MAX_VALUE },
            )
            assertFalse(domains.probing.value)
        }
    }

    @Test fun `remembered healthy redirect to approved host activates final host`() = runTest {
        withFixture(AppSettings(currentHost = "zlib.re", downloadTreeUri = "content://test/tree/books"), sessionDomain = "zlib.re") {
            redirects["zlib.re"] = "zlib.bz"
            coordinator.start()

            assertIs<StartupState.Ready>(coordinator.state.value)
            assertEquals("zlib.bz", domains.currentHostOrNull())
            assertEquals("zlib.bz", sessions.current()?.domain)
            assertNotNull(domains.checks.value.first { it.host == "zlib.re" }.latencyMs)
        }
    }

    private suspend fun TestScope.withFixture(
        initial: AppSettings,
        sessionDomain: String = "z-lib.sk",
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(initial = initial, sessionDomain = sessionDomain, scope = this)
        try {
            fixture.block()
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        initial: AppSettings,
        sessionDomain: String = "z-lib.sk",
        scope: TestScope? = null,
        useRealIo: Boolean = false,
    ) {
        val unhealthy = ConcurrentHashMap.newKeySet<String>()
        val redirects = ConcurrentHashMap<String, String>()
        val delaysMs = ConcurrentHashMap<String, Long>()
        val gates = ConcurrentHashMap<String, CountDownLatch>()

        val probeServer = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val host = request.path.orEmpty().removePrefix("/").substringBefore('/')
                    gates[host]?.await(5, TimeUnit.SECONDS)
                    val delay = delaysMs[host] ?: 0L
                    if (delay > 0L) Thread.sleep(delay)
                    if (host in unhealthy) return MockResponse().setResponseCode(503).setBody("unavailable")
                    redirects[host]?.let { target ->
                        return MockResponse().setResponseCode(302).setHeader("Location", "https://$target/")
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"success":1,"domains":[{"domain":"z-library.sk"}]}""")
                }
            }
            start()
        }
        val apiServer = MockWebServer().apply { start() }

        private val dispatchers = if (useRealIo) {
            object : AppDispatchers {
                override val io = Dispatchers.IO
                override val default = Dispatchers.Default
                override val main = Dispatchers.Unconfined
            }
        } else {
            val dispatcher = StandardTestDispatcher(requireNotNull(scope).testScheduler)
            object : AppDispatchers {
                override val io = dispatcher
                override val default = dispatcher
                override val main = dispatcher
            }
        }

        val store = MemorySettings(initial)
        private val sessionStore = object : SessionStore {
            var vault = SessionVault(
                entries = listOf(SessionEntry(Session("7", "test", sessionDomain))),
                activeId = "7",
            )
            override suspend fun readVault() = vault
            override suspend fun saveVault(vault: SessionVault) { this.vault = vault }
            override suspend fun clear() { vault = SessionVault() }
        }
        val sessions = SessionManager(sessionStore, dispatchers)
        val registry = AllowedHostRegistry()
        val domains = DomainRepository(
            DomainProbe(
                OkHttpClient(),
                dispatchers,
                isAllowedHost = registry::isProbeAllowed,
                isTrustedHost = { registry.isAllowed(it) || DomainPolicy.isCatalogHost(it) },
                baseUrlFor = { probeServer.url("/$it").toString() },
                requireHttps = false,
            ),
            store,
            sessions,
            registry,
            dispatchers,
        )
        private val api = EapiClient(
            OkHttpClient(),
            { apiServer.url("/").toString() },
            sessions::current,
            dispatchers = dispatchers,
            baseUrlForHost = { apiServer.url("/").toString() },
            maxAttempts = 1,
            isAllowedUrl = { it.host == apiServer.hostName && it.port == apiServer.port },
        )
        val coordinator = StartupCoordinator(sessions, domains, AccountRepository(api, sessions, domains, dispatchers), dispatchers, store)

        fun close() {
            probeServer.shutdown()
            apiServer.shutdown()
        }
    }

    private class MemorySettings(initial: AppSettings) : SettingsStore {
        val value = MutableStateFlow(initial)
        override val settings = value
        override suspend fun read() = value.value
        override suspend fun setCurrentHost(host: String?) { value.value = value.value.copy(currentHost = host) }
        override suspend fun setCustomHosts(hosts: Set<String>) { value.value = value.value.copy(customHosts = hosts) }
        override suspend fun setDiscoveredHosts(hosts: Set<String>) { value.value = value.value.copy(discoveredHosts = hosts) }
        override suspend fun setLockedHost(host: String?) { value.value = value.value.copy(lockedHost = host) }
        override suspend fun setHostLatenciesMs(latencies: Map<String, Long>) { value.value = value.value.copy(hostLatenciesMs = latencies) }
        override suspend fun setThemeMode(mode: ThemeMode) { value.value = value.value.copy(themeMode = mode) }
        override suspend fun setBookGridColumns(columns: BookGridColumns) { value.value = value.value.copy(bookGridColumns = columns) }
        override suspend fun setDownloadTreeUri(uri: String?) { value.value = value.value.copy(downloadTreeUri = uri) }
    }
}
