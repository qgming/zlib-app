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
            assertEquals(DomainStatus.HTTP_ERROR, repository.checks.value.first().status)

            assertIs<AppResult.Success<Unit>>(repository.setAutoSwitchEnabled(true))
            assertNull(repository.settings.value.lockedHost)
            repository.probeAll()
            assertEquals("z-lib.sk", repository.currentHostOrNull())
            assertEquals(16, server.requestCount)
        }
    }

    @Test
    fun `single probe changes only selected status without changing current host`() = runTest {
        fixture {
            val result = repository.probeHost("z-lib.sk")
            assertTrue(result.healthy)
            assertEquals("z-lib.gd", repository.currentHostOrNull())
            assertEquals(DomainStatus.UNKNOWN, repository.checks.value.first().status)
            assertEquals(DomainStatus.HEALTHY, repository.checks.value[1].status)
            assertFalse(repository.probing.value)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/z-lib.sk/eapi/info/domains", request.path)
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("remix-userkey"))
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
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val host = request.path.orEmpty().removePrefix("/").substringBefore('/')
                    return if (host in unhealthy) MockResponse().setResponseCode(503).setBody("unavailable")
                    else MockResponse().setHeader("Content-Type", "application/json").setBody("""{"success":1,"domains":[]}""")
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

        fun newRepository() = DomainRepository(
            DomainProbe(
                OkHttpClient(), dispatchers, isAllowedHost = registry::isAllowed,
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
        override suspend fun setLockedHost(host: String?) { value.value = value.value.copy(lockedHost = host) }
        override suspend fun setThemeMode(mode: ThemeMode) { value.value = value.value.copy(themeMode = mode) }
        override suspend fun setBookGridColumns(columns: BookGridColumns) { value.value = value.value.copy(bookGridColumns = columns) }
        override suspend fun setDownloadTreeUri(uri: String?) { value.value = value.value.copy(downloadTreeUri = uri) }
    }
}
