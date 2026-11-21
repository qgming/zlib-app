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
    val currentHost: String? = null,
    val lockedHost: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val downloadTreeUri: String? = null,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun read(): AppSettings
    suspend fun setCurrentHost(host: String?)
    suspend fun setCustomHosts(hosts: Set<String>)
    suspend fun setLockedHost(host: String?)
    suspend fun setThemeMode(mode: ThemeMode)
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

    override suspend fun setLockedHost(host: String?) = edit { prefs ->
        if (host.isNullOrBlank()) prefs.remove(LOCKED_HOST) else prefs[LOCKED_HOST] = host
    }

    override suspend fun setThemeMode(mode: ThemeMode) = edit { prefs ->
        prefs[THEME_MODE] = mode.name
    }

    override suspend fun setDownloadTreeUri(uri: String?) = edit { prefs ->
        if (uri.isNullOrBlank()) prefs.remove(DOWNLOAD_TREE) else prefs[DOWNLOAD_TREE] = uri
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { dataStore.edit(block) }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        customHosts = this[CUSTOM_HOSTS].orEmpty(),
        currentHost = this[CURRENT_HOST]?.takeIf { it.isNotBlank() },
        lockedHost = this[LOCKED_HOST]?.takeIf { it.isNotBlank() },
        themeMode = ThemeMode.fromPreference(this[THEME_MODE]),
        downloadTreeUri = this[DOWNLOAD_TREE],
    )

    companion object {
        private val CUSTOM_HOSTS = stringSetPreferencesKey("custom_hosts")
        private val CURRENT_HOST = stringPreferencesKey("current_host")
        private val LOCKED_HOST = stringPreferencesKey("locked_host")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val DOWNLOAD_TREE = stringPreferencesKey("download_tree")
    }
}
