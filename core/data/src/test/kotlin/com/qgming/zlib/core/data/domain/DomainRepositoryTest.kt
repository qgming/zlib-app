package com.qgming.zlib.core.data.domain

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.data.session.SessionManager
import com.qgming.zlib.core.data.session.SessionStore
import com.qgming.zlib.core.data.session.SessionVault
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.model.DomainGroup
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
    fun `community mirrors are listed immediately and full probes do not grant authorization`() = runTest {
        fixture {
            val host = DomainPolicy.COMMUNITY_MIRROR_HOSTS.first()
            val communityChecks = repository.checks.value.filter { it.group == DomainGroup.COMMUNITY }
            assertEquals(DomainPolicy.COMMUNITY_MIRROR_HOSTS, communityChecks.map { it.host })
            assertTrue(communityChecks.all { it.status == DomainStatus.UNKNOWN })
            assertTrue(repository.requiresApproval(host))
            assertTrue(registry.isProbeAllowed(host))
            assertFalse(registry.isProbeAllowed("sub.$host"))
            assertIs<AppResult.Failure>(repository.setCurrentHost(host))
            assertEquals(0, server.requestCount)

            repository.probeAll()

            assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS.size, server.requestCount)
            assertEquals(communityChecks, repository.checks.value.filter { it.group == DomainGroup.COMMUNITY })

            emptyDomains += host
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS
            repository.probeAll(includeCommunity = true)

            assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS.size * 2 + DomainPolicy.COMMUNITY_MIRROR_HOSTS.size, server.requestCount)
            assertTrue(repository.checks.value.first { it.host == host }.healthy)
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertFalse(repository.isApprovedHost(host))
            assertTrue(store.value.value.customHosts.isEmpty())
            repeat(server.requestCount) {
                val probe = server.takeRequest()
                assertNull(probe.getHeader("Cookie"))
                assertNull(probe.getHeader("remix-userid"))
                assertNull(probe.getHeader("remix-userkey"))
            }
        }
    }

    @Test
    fun `community consent and selection survives restart and revocation keeps the built-in card`() = runTest {
        fixture {
            val host = DomainPolicy.COMMUNITY_MIRROR_HOSTS.first()
            val otherHost = DomainPolicy.COMMUNITY_MIRROR_HOSTS[1]
            val original = Session("123", "test-key", "z-lib.gd")
            sessions.add(original)
            assertIs<AppResult.Success<Unit>>(repository.setAutoSwitchEnabled(false))

            assertEquals(AppResult.Success(host), repository.approveAndSelectHost(host))

            assertEquals(host, repository.currentHostOrNull())
            assertEquals(host, store.value.value.lockedHost)
            assertEquals(original.withDomain(host), sessions.current())
            assertEquals(setOf(host), store.value.value.customHosts)
            assertTrue(repository.isApprovedHost(host))
            assertFalse(registry.isAllowed("sub.$host"))
            assertFalse(registry.isAllowed(otherHost))
            assertFalse(repository.requiresApproval(host))
            assertEquals(1, server.requestCount)
            val probe = server.takeRequest()
            assertEquals("/$host/eapi/info/domains", probe.path)
            assertNull(probe.getHeader("Cookie"))
            assertNull(probe.getHeader("remix-userid"))
            assertNull(probe.getHeader("remix-userkey"))

            val restarted = newRepository(AllowedHostRegistry())
            restarted.initialize()
            assertEquals(host, restarted.currentHostOrNull())
            assertFalse(restarted.requiresApproval(host))
            assertEquals(1, restarted.checks.value.count { it.host == host })

            assertIs<AppResult.Success<Unit>>(repository.removeCustomHost(host))

            assertNull(store.value.value.lockedHost)
            assertFalse(registry.isAllowed(host))
            assertTrue(registry.isProbeAllowed(host))
            assertEquals(1 + DomainPolicy.DEFAULT_PROBE_HOSTS.size, server.requestCount)
            assertTrue(repository.requiresApproval(host))
            assertEquals(DomainGroup.COMMUNITY, repository.checks.value.single { it.host == host }.group)
            assertIs<AppResult.Failure>(repository.setCurrentHost(host))
        }
    }

    @Test
    fun `anonymous community host checks preserve credential consent and other cards`() = runTest {
        fixture {
            val (host, otherHost) = DomainPolicy.COMMUNITY_MIRROR_HOSTS
            assertTrue(repository.probeHost(host).healthy)
            assertTrue(repository.requiresApproval(host))
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals(DomainStatus.UNKNOWN, repository.checks.value.single { it.host == otherHost }.status)

            assertEquals(AppResult.Success(host), repository.approveCommunityHost(host))
            assertEquals(1, server.requestCount)
            assertTrue(repository.isApprovedHost(host))
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertIs<AppResult.Failure>(repository.approveCommunityHost("sub.$host"))
            assertIs<AppResult.Failure>(repository.approveCommunityHost("$host:443"))
            assertIs<AppResult.Failure>(repository.approveCommunityHost("mirror.example.org"))

            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS
            repository.probeAll()

            assertEquals(host, repository.currentHostOrNull())
            assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS.size + 2, server.requestCount)
            assertEquals(DomainStatus.UNKNOWN, repository.checks.value.single { it.host == otherHost }.status)
        }
    }

    @Test
    fun `new challenge keeps the current host after approval and successful check can select it later`() = runTest {
        fixture {
            val host = DomainPolicy.COMMUNITY_MIRROR_HOSTS.first()
            challenged += host
            val expectedFailure = AppResult.Failure(AppError.DomainBlocked(DomainStatus.DIAMWALL))
            assertEquals(expectedFailure, repository.approveAndSelectHost(host))
            assertTrue(repository.isApprovedHost(host))
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals(DomainStatus.DIAMWALL, repository.checks.value.single { it.host == host }.status)
            assertEquals(expectedFailure, repository.setCurrentHost(host))
            assertIs<AppResult.Failure>(repository.selectVerifiedHost(host))
            assertEquals("z-lib.gd", repository.currentHostOrNull())

            challenged -= host
            assertEquals(AppResult.Success(host), repository.selectVerifiedHost(host))
            assertEquals(host, repository.currentHostOrNull())
            assertEquals(DomainStatus.HEALTHY, repository.checks.value.single { it.host == host }.status)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `verification redirect to a healthy approved host leaves selected mirror unchanged`() = runTest {
        fixture {
            val host = DomainPolicy.COMMUNITY_MIRROR_HOSTS.first()
            val redirectTarget = "zlib.bz"
            val original = Session("123", "test-key", "z-lib.gd")
            sessions.add(original)
            assertIs<AppResult.Success<Unit>>(repository.setAutoSwitchEnabled(false))
            assertEquals(AppResult.Success(host), repository.approveCommunityHost(host))
            assertTrue(repository.isApprovedHost(redirectTarget))
            redirects[host] = redirectTarget

            assertIs<AppResult.Failure>(repository.selectVerifiedHost(host))

            val check = repository.checks.value.single { it.host == host }
            assertTrue(check.healthy)
            assertEquals(redirectTarget, check.finalHost)
            assertEquals(original.domain, repository.currentHostOrNull())
            assertEquals(original.domain, store.value.value.currentHost)
            assertEquals(original.domain, store.value.value.lockedHost)
            assertEquals(original, sessions.current())
            assertEquals(2, server.requestCount)
            assertEquals("/$host/eapi/info/domains", server.takeRequest().path)
            assertEquals("/$redirectTarget/eapi/info/domains", server.takeRequest().path)
        }
    }

    @Test
    fun `revoked community mirror cannot be selected after verification`() = runTest {
        fixture {
            val host = DomainPolicy.COMMUNITY_MIRROR_HOSTS.first()
            assertEquals(AppResult.Success(host), repository.approveCommunityHost(host))
            assertIs<AppResult.Success<Unit>>(repository.removeCustomHost(host))
            assertTrue(registry.isProbeAllowed(host))

            assertIs<AppResult.Failure>(repository.selectVerifiedHost(host))

            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `upgraded community entries deduplicate and revocation removes every saved alias`() = runTest {
        val host = DomainPolicy.COMMUNITY_MIRROR_HOSTS.first()
        fixture(AppSettings(currentHost = "z-lib.gd", customHosts = setOf(host.uppercase(), "$host."))) {
            assertEquals(1, repository.checks.value.count { it.host == host })
            assertTrue(repository.isApprovedHost(host))

            assertIs<AppResult.Success<Unit>>(repository.removeCustomHost(host))

            assertTrue(store.value.value.customHosts.isEmpty())
            assertTrue(repository.requiresApproval(host))
            assertEquals(1, repository.checks.value.count { it.host == host })
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `remembered host lists defaults custom and active host before any probes`() = runTest {
        fixture(AppSettings(currentHost = "zh.z-lib.by", customHosts = setOf("mirror.example.org"))) {
            assertEquals(
                DomainPolicy.DEFAULT_PROBE_HOSTS + DomainPolicy.COMMUNITY_MIRROR_HOSTS + listOf("mirror.example.org", "zh.z-lib.by"),
                repository.checks.value.map { it.host },
            )
            assertTrue(repository.checks.value.all { it.status == DomainStatus.UNKNOWN })
            assertEquals(DomainGroup.OFFICIAL, repository.checks.value.single { it.host == "zh.z-lib.by" }.group)
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
    fun `anonymous discovery never makes a mirror eligible to receive credentials`() = runTest {
        fixture {
            redirects["zlib.re"] = "zh.mirror.test"
            unhealthy += DomainPolicy.DEFAULT_PROBE_HOSTS - "zlib.re"

            repository.probeAll()

            val redirector = repository.checks.value.first { it.host == "zlib.re" }
            assertTrue(redirector.healthy)
            assertEquals("zh.mirror.test", redirector.redirectTarget)
            assertTrue(repository.checks.value.any { it.host == "zh.mirror.test" && it.healthy })
            assertFalse(registry.isAllowed("zh.mirror.test"))
            assertTrue(registry.isProbeAllowed("zh.mirror.test"))
            assertTrue(store.value.value.discoveredHosts.contains("zh.mirror.test"))
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertIs<AppResult.Failure>(repository.setCurrentHost("zh.mirror.test"))

            repeat(server.requestCount) {
                val recorded = server.takeRequest()
                assertNull(recorded.getHeader("Cookie"), "no cookie during probing")
                assertNull(recorded.getHeader("remix-userid"), "no credentials during probing")
            }
        }
    }

    @Test
    fun `legacy discovered lock is preserved without granting access on restart`() = runTest {
        fixture(AppSettings(currentHost = "mirror.example.org", lockedHost = "mirror.example.org", discoveredHosts = setOf("mirror.example.org"))) {
            assertFalse(registry.isAllowed("mirror.example.org"))
            assertNull(repository.currentHostOrNull())
            assertEquals("mirror.example.org", repository.settings.value.lockedHost)
            assertTrue(repository.checks.value.any { it.host == "mirror.example.org" })

            assertIs<AppResult.Success<String>>(repository.addCustomHost("mirror.example.org"))
            assertEquals("mirror.example.org", repository.currentHostOrNull())
            assertTrue(registry.isAllowed("mirror.example.org"))
            assertFalse(registry.isAllowed("sub.mirror.example.org"))
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
    fun `locked redirector keeps the lock while using its approved responding host`() = runTest {
        fixture {
            sessions.add(Session("1", "k", "z-lib.gd", emptyMap()))
            repository.addCustomHost("zh.mirror.test")
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
    fun `failed anonymous probe removes a stale discovery from candidates`() = runTest {
        fixture(AppSettings(currentHost = "z-lib.gd", discoveredHosts = setOf("stale.test"))) {
            unverified += "stale.test"

            repository.probeAll(includeCommunity = true)

            val stale = repository.checks.value.first { it.host == "stale.test" }
            assertFalse(stale.healthy)
            assertFalse(registry.isAllowed("stale.test"))
            assertFalse(registry.isProbeAllowed("stale.test"))
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
        val challenged = ConcurrentHashMap.newKeySet<String>()
        val emptyDomains = ConcurrentHashMap.newKeySet<String>()
        val redirects = ConcurrentHashMap<String, String>()
        val unverified = ConcurrentHashMap.newKeySet<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val host = request.path.orEmpty().removePrefix("/").substringBefore('/')
                    if (host in unhealthy) return MockResponse().setResponseCode(503).setBody("unavailable")
                    if (host in challenged) return MockResponse().setResponseCode(307).setHeader("Server", "DiamWall")
                    redirects[host]?.let { target ->
                        return MockResponse().setResponseCode(302).setHeader("Location", "https://$target/")
                    }
                    val domains = when (host) {
                        in emptyDomains -> ""
                        in unverified -> """{"domain":"$host"}"""
                        else -> """{"domain":"z-library.sk"}"""
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
                isTrustedHost = { registry.isAllowed(it) || DomainPolicy.isCatalogHost(it) },
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
