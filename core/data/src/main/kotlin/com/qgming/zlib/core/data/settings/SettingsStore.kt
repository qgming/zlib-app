package com.qgming.zlib.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "zlib_settings")

data class AppSettings(
    val customHosts: Set<String> = emptySet(),
    /** Redirect targets accepted by the credential-free handshake; re-verified every probe (ADR-065). */
    val discoveredHosts: Set<String> = emptySet(),
    val currentHost: String? = null,
    val lockedHost: String? = null,
    /** Persisted host -> last measured healthy latency (ms) used to prioritize future probes. */
    val hostLatenciesMs: Map<String, Long> = emptyMap(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val bookGridColumns: BookGridColumns = BookGridColumns.AUTO,
    val downloadTreeUri: String? = null,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun read(): AppSettings
    suspend fun setCurrentHost(host: String?)
    suspend fun setCustomHosts(hosts: Set<String>)
    suspend fun setDiscoveredHosts(hosts: Set<String>)
    suspend fun setLockedHost(host: String?)
    suspend fun setHostLatenciesMs(latencies: Map<String, Long>) {}
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setBookGridColumns(columns: BookGridColumns)
    suspend fun setDownloadTreeUri(uri: String?)
}

@Singleton
class DataStoreSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: AppDispatchers,
) : SettingsStore {

    private val dataStore = context.settingsDataStore

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { preferences -> preferences.toSettings() }

    override suspend fun read(): AppSettings = withContext(dispatchers.io) {
        dataStore.data
            .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
            .first()
            .toSettings()
    }

    override suspend fun setCurrentHost(host: String?) = edit { prefs ->
        if (host.isNullOrBlank()) prefs.remove(CURRENT_HOST) else prefs[CURRENT_HOST] = host
    }

    override suspend fun setCustomHosts(hosts: Set<String>) = edit { prefs ->
        prefs[CUSTOM_HOSTS] = hosts
    }

    override suspend fun setDiscoveredHosts(hosts: Set<String>) = edit { prefs ->
        if (hosts.isEmpty()) prefs.remove(DISCOVERED_HOSTS) else prefs[DISCOVERED_HOSTS] = hosts
    }

    override suspend fun setLockedHost(host: String?) = edit { prefs ->
        if (host.isNullOrBlank()) prefs.remove(LOCKED_HOST) else prefs[LOCKED_HOST] = host
    }

    override suspend fun setHostLatenciesMs(latencies: Map<String, Long>) = edit { prefs ->
        val encoded = latencies.entries
            .mapNotNull { (host, ms) -> host.takeIf { it.isNotBlank() && ms > 0L }?.let { "$it=$ms" } }
            .toSet()
        if (encoded.isEmpty()) prefs.remove(HOST_LATENCIES) else prefs[HOST_LATENCIES] = encoded
    }

    override suspend fun setThemeMode(mode: ThemeMode) = edit { prefs ->
        prefs[THEME_MODE] = mode.name
    }

    override suspend fun setBookGridColumns(columns: BookGridColumns) = edit { prefs ->
        prefs[BOOK_GRID_COLUMNS] = columns.name
    }

    override suspend fun setDownloadTreeUri(uri: String?) = edit { prefs ->
        if (uri.isNullOrBlank()) prefs.remove(DOWNLOAD_TREE) else prefs[DOWNLOAD_TREE] = uri
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { dataStore.edit(block) }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        customHosts = this[CUSTOM_HOSTS].orEmpty(),
        discoveredHosts = this[DISCOVERED_HOSTS].orEmpty(),
        currentHost = this[CURRENT_HOST]?.takeIf { it.isNotBlank() },
        lockedHost = this[LOCKED_HOST]?.takeIf { it.isNotBlank() },
        hostLatenciesMs = decodeHostLatencies(this[HOST_LATENCIES].orEmpty()),
        themeMode = ThemeMode.fromPreference(this[THEME_MODE]),
        bookGridColumns = BookGridColumns.fromPreference(this[BOOK_GRID_COLUMNS]),
        downloadTreeUri = this[DOWNLOAD_TREE],
    )

    private fun decodeHostLatencies(entries: Set<String>): Map<String, Long> = buildMap {
        entries.forEach { entry ->
            val sep = entry.lastIndexOf('=')
            if (sep <= 0 || sep >= entry.lastIndex) return@forEach
            val host = entry.substring(0, sep)
            val latency = entry.substring(sep + 1).toLongOrNull() ?: return@forEach
            if (latency > 0L) put(host, latency)
        }
    }

    companion object {
        private val CUSTOM_HOSTS = stringSetPreferencesKey("custom_hosts")
        private val DISCOVERED_HOSTS = stringSetPreferencesKey("discovered_hosts")
        private val CURRENT_HOST = stringPreferencesKey("current_host")
        private val LOCKED_HOST = stringPreferencesKey("locked_host")
        private val HOST_LATENCIES = stringSetPreferencesKey("host_latencies_ms")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val BOOK_GRID_COLUMNS = stringPreferencesKey("book_grid_columns")
        private val DOWNLOAD_TREE = stringPreferencesKey("download_tree")
    }
}
