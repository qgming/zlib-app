package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

@OptIn(ExperimentalCoroutinesApi::class)
class DomainRepositoryTest {
    @Test
    fun `remembered host lists defaults custom and active host before any probes`() = runTest {
        fixture(AppSettings(currentHost = "zh.z-lib.by", customHosts = setOf("mirror.example.org"))) {
            assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS + listOf("mirror.example.org", "zh.z-lib.by"), repository.checks.value.map { it.host })
            assertTrue(repository.checks.value.all { it.status == DomainStatus.UNKNOWN })
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `manual selection keeps credentials and updates disabled auto switch across restart`() = runTest {
        fixture {
            val original = Session("123", "test-key", "z-lib.gd", mapOf("remix_userid" to "123", "remix_userkey" to "test-key"))
            sessions.add(original)
            assertIs<AppResult.Success<Unit>>(repository.setAutoSwitchEnabled(false))
            assertIs<AppResult.Success<String>>(repository.setCurrentHost("https://z-lib.sk"))
            assertEquals("z-lib.sk", store.value.value.lockedHost)
            assertEquals(original.withDomain("z-lib.sk"), sessions.current())

            val restarted = newRepository()
            restarted.initialize()
            assertEquals("z-lib.sk", restarted.currentHostOrNull())
            assertEquals("z-lib.sk", restarted.settings.value.lockedHost)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `batch respects disabled auto switch and enabled auto switch chooses healthy fallback`() = runTest {
        fixture {
            unhealthy += "z-lib.gd"
            repository.setAutoSwitchEnabled(false)
            repository.probeAll()
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals(DomainStatus.HTTP_ERROR, repository.checks.value.first { it.host == "z-lib.gd" }.status)

            assertIs<AppResult.Success<Unit>>(repository.setAutoSwitchEnabled(true))
            assertNull(repository.settings.value.lockedHost)
            repository.probeAll()
            assertEquals("zlib.bz", repository.currentHostOrNull())
            assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS.size * 2, server.requestCount)
        }
    }

    @Test
    fun `single probe changes only selected status without changing current host`() = runTest {
        fixture {
            val result = repository.probeHost("z-lib.sk")
            assertTrue(result.healthy)
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals(DomainStatus.UNKNOWN, repository.checks.value.first { it.host == "z-lib.gd" }.status)
            assertEquals(DomainStatus.HEALTHY, repository.checks.value.first { it.host == "z-lib.sk" }.status)
            assertFalse(repository.probing.value)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/z-lib.sk/eapi/info/domains", request.path)
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("remix-userkey"))
        }
    }

    @Test
    fun `verified redirect target is discovered and used without credentials`() = runTest {
        fixture {
            redirects["zlib.re"] = "zh.mirror.test"
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS - "zlib.re"

            repository.probeAll()

            val redirector = repository.checks.value.first { it.host == "zlib.re" }
            assertTrue(redirector.healthy)
            assertEquals("zh.mirror.test", redirector.redirectTarget)
            assertTrue(repository.checks.value.any { it.host == "zh.mirror.test" && it.healthy })
            assertTrue(registry.isAllowed("zh.mirror.test"))
            assertTrue(store.value.value.discoveredHosts.contains("zh.mirror.test"))
            assertEquals("zh.mirror.test", repository.currentHostOrNull())

            repeat(server.requestCount) { request ->
                val recorded = server.takeRequest()
                assertNull(recorded.getHeader("Cookie"), "no cookie during probing")
                assertNull(recorded.getHeader("remix-userid"), "no credentials during probing")
            }
        }
    }

    @Test
    fun `discovered redirect target is re-verified after a restart`() = runTest {
        fixture {
            redirects["zlib.re"] = "zh.mirror.test"
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS - "zlib.re"
            repository.probeAll()
            assertEquals("zh.mirror.test", repository.currentHostOrNull())

            // Fresh process: the in-memory trust list starts empty again.
            val freshRegistry = AllowedHostRegistry()
            val restarted = newRepository(freshRegistry)
            restarted.initialize()

            assertTrue(freshRegistry.isAllowed("zh.mirror.test"))
            assertEquals("zh.mirror.test", restarted.currentHostOrNull())
        }
    }

    @Test
    fun `unverified redirect target never becomes usable`() = runTest {
        fixture {
            redirects["zlib.re"] = "phish.test"
            unverified += "phish.test"
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS - "zlib.re"

            repository.probeAll()

            val redirector = repository.checks.value.first { it.host == "zlib.re" }
            assertFalse(redirector.healthy)
            assertNull(redirector.redirectTarget)
            assertFalse(registry.isAllowed("phish.test"))
            assertEquals("z-lib.gd", repository.currentHostOrNull())
        }
    }

    @Test
    fun `manual selection uses the requested unchecked host and invalid targets never make requests`() = runTest {
        fixture {
            repository.setCurrentHost("z-library.im")
            assertEquals("z-library.im", repository.currentHostOrNull())
            assertEquals(DomainStatus.UNKNOWN, repository.checks.value.first { it.host == "z-library.im" }.status)
            listOf("http://z-lib.sk", "z-lib.sk:443", "z-lib.sk/path", "127.0.0.1", "unknown.example.org").forEach { host ->
                assertIs<AppResult.Failure>(repository.setCurrentHost(host))
                assertEquals(DomainStatus.INVALID, repository.probeHost(host).status)
            }
            assertEquals("z-library.im", repository.currentHostOrNull())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `adding custom domain trusts exact host and keeps manual domain while auto switch disabled`() = runTest {
        fixture {
            repository.setAutoSwitchEnabled(false)
            assertIs<AppResult.Failure>(repository.addCustomHost("https://mirror.example.org/path"))
            assertEquals(0, server.requestCount)
            assertTrue(store.value.value.customHosts.isEmpty())

            assertIs<AppResult.Success<String>>(repository.addCustomHost("https://mirror.example.org"))
            assertTrue(registry.isAllowed("mirror.example.org"))
            assertFalse(registry.isAllowed("sub.mirror.example.org"))
            assertEquals(DomainStatus.HEALTHY, repository.checks.value.last().status)
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals("z-lib.gd", repository.settings.value.lockedHost)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `locked redirector keeps the lock while using its verified target`() = runTest {
        fixture {
            sessions.add(Session("1", "k", "z-lib.gd", emptyMap()))
            redirects["zlib.re"] = "zh.mirror.test"
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS - "zlib.re"

            repository.setAutoSwitchEnabled(false)
            val result = assertIs<AppResult.Success<String>>(repository.setCurrentHost("zlib.re"))
            assertEquals("zlib.re", repository.settings.value.lockedHost)
            assertEquals("zlib.re", result.value)

            repository.probeAll()

            assertEquals("zh.mirror.test", repository.currentHostOrNull())
            assertEquals("zlib.re", repository.settings.value.lockedHost)
            assertEquals("zh.mirror.test", sessions.current()?.domain)
        }
    }

    @Test
    fun `failed re-verification of a stored discovery removes it from the allowlist`() = runTest {
        fixture(AppSettings(currentHost = "z-lib.gd", discoveredHosts = setOf("stale.test"))) {
            unverified += "stale.test"

            repository.probeAll()

            val stale = repository.checks.value.first { it.host == "stale.test" }
            assertFalse(stale.healthy)
            assertFalse(registry.isAllowed("stale.test"))
            assertTrue(store.value.value.discoveredHosts.isEmpty())
        }
    }

    private suspend fun TestScope.fixture(
        initial: AppSettings = AppSettings(currentHost = "z-lib.gd"),
        block: suspend Fixture.() -> Unit,
    ) {
        val fixture = Fixture(this, initial)
        try {
            fixture.repository.initialize()
            fixture.block()
        } finally {
            fixture.server.shutdown()
        }
    }

    private class Fixture(scope: TestScope, initial: AppSettings) {
        val unhealthy = ConcurrentHashMap.newKeySet<String>()
        val redirects = ConcurrentHashMap<String, String>()
        val unverified = ConcurrentHashMap.newKeySet<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val host = request.path.orEmpty().removePrefix("/").substringBefore('/')
                    if (host in unhealthy) return MockResponse().setResponseCode(503).setBody("unavailable")
                    redirects[host]?.let { target ->
                        return MockResponse().setResponseCode(302).setHeader("Location", "https://$target/")
                    }
                    val domains = if (host in unverified) {
                        """{"domain":"$host"}"""
                    } else {
                        """{"domain":"z-library.sk"}"""
                    }
                    return MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"success":1,"domains":[$domains]}""")
                }
            }
            start()
        }
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val dispatchers = object : AppDispatchers {
            override val main = dispatcher
            override val io = dispatcher
            override val default = dispatcher
        }
        val store = MemorySettings(initial)
        private val sessionStore = object : SessionStore {
            var value = SessionVault()
            override suspend fun readVault() = value
            override suspend fun saveVault(vault: SessionVault) { value = vault }
            override suspend fun clear() { value = SessionVault() }
        }
        val sessions = SessionManager(sessionStore, dispatchers)
        val registry = AllowedHostRegistry()
        val repository = newRepository()

        fun newRepository(registry: AllowedHostRegistry = this.registry) = DomainRepository(
            DomainProbe(
                OkHttpClient(), dispatchers, isAllowedHost = registry::isProbeAllowed,
                isTrustedHost = registry::isAllowed,
                baseUrlFor = { server.url("/$it").toString() }, requireHttps = false,
            ), store, sessions, registry, dispatchers,
        )
    }

    private class MemorySettings(initial: AppSettings) : SettingsStore {
        val value = MutableStateFlow(initial)
        override val settings = value
        override suspend fun read() = value.value
        override suspend fun setCurrentHost(host: String?) { value.value = value.value.copy(currentHost = host) }
        override suspend fun setCustomHosts(hosts: Set<String>) { value.value = value.value.copy(customHosts = hosts) }
        override suspend fun setDiscoveredHosts(hosts: Set<String>) { value.value = value.value.copy(discoveredHosts = hosts) }
        override suspend fun setLockedHost(host: String?) { value.value = value.value.copy(lockedHost = host) }
        override suspend fun setThemeMode(mode: ThemeMode) { value.value = value.value.copy(themeMode = mode) }
        override suspend fun setBookGridColumns(columns: BookGridColumns) { value.value = value.value.copy(bookGridColumns = columns) }
        override suspend fun setDownloadTreeUri(uri: String?) { value.value = value.value.copy(downloadTreeUri = uri) }
    }
}
