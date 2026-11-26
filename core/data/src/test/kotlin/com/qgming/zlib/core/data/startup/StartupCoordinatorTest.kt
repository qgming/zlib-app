package com.qgming.zlib.core.data.startup

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.account.AccountRepository
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
import com.qgming.zlib.core.network.EapiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class StartupCoordinatorTest {
    @Test fun `missing directory gates login before any network request`() = runTest {
        MockWebServer().use { server ->
            val coordinator = fixture(server, AppSettings(currentHost = "z-lib.sk"))
            coordinator.start()
            assertIs<StartupState.DirectoryRequired>(coordinator.state.value)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `remembered local settings and session become ready without profile network`() = runTest {
        MockWebServer().use { server ->
            val coordinator = fixture(server, AppSettings(currentHost = "z-lib.sk", downloadTreeUri = "content://test/tree/books"))
            coordinator.start()
            assertIs<StartupState.Ready>(coordinator.state.value)
            assertEquals(0, server.requestCount)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(server: MockWebServer, initial: AppSettings): StartupCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : AppDispatchers {
            override val io = dispatcher; override val default = dispatcher; override val main = dispatcher
        }
        val store = object : SettingsStore {
            override val settings = MutableStateFlow(initial)
            override suspend fun read() = settings.value
            override suspend fun setCurrentHost(host: String?) { settings.value = settings.value.copy(currentHost = host) }
            override suspend fun setCustomHosts(hosts: Set<String>) { settings.value = settings.value.copy(customHosts = hosts) }
            override suspend fun setDiscoveredHosts(hosts: Set<String>) { settings.value = settings.value.copy(discoveredHosts = hosts) }
            override suspend fun setLockedHost(host: String?) { settings.value = settings.value.copy(lockedHost = host) }
            override suspend fun setThemeMode(mode: ThemeMode) { settings.value = settings.value.copy(themeMode = mode) }
            override suspend fun setBookGridColumns(columns: BookGridColumns) { settings.value = settings.value.copy(bookGridColumns = columns) }
            override suspend fun setDownloadTreeUri(uri: String?) { settings.value = settings.value.copy(downloadTreeUri = uri) }
        }
        val sessions = SessionManager(object : SessionStore {
            override suspend fun readVault() = SessionVault(
                entries = listOf(com.qgming.zlib.core.data.session.SessionEntry(Session("7", "test", "z-lib.sk"))), activeId = "7",
            )
            override suspend fun saveVault(vault: SessionVault) { }
            override suspend fun clear() { }
        }, dispatchers)
        val registry = AllowedHostRegistry()
        val domains = DomainRepository(DomainProbe(OkHttpClient(), dispatchers), store, sessions, registry, dispatchers)
        val api = EapiClient(OkHttpClient(), { server.url("/").toString() }, sessions::current,
            dispatchers = dispatchers, baseUrlForHost = { server.url("/").toString() }, maxAttempts = 1,
            isAllowedUrl = { it.host == server.hostName && it.port == server.port })
        return StartupCoordinator(sessions, domains, AccountRepository(api, sessions, domains, dispatchers), dispatchers, store)
    }
}
