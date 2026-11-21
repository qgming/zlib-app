package com.qgming.zlib.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qgming.zlib.core.common.AppDispatchers
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
    val glassEnabled: Boolean = true,
    val dynamicColor: Boolean = true,
)

interface SettingsStore {
    val settings: Flow<AppSettings>
    suspend fun read(): AppSettings
    suspend fun setCurrentHost(host: String?)
    suspend fun setCustomHosts(hosts: Set<String>)
    suspend fun setLockedHost(host: String?)
    suspend fun setGlassEnabled(enabled: Boolean)
    suspend fun setDynamicColor(enabled: Boolean)
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

    override suspend fun setGlassEnabled(enabled: Boolean) = edit { prefs ->
        prefs[GLASS_ENABLED] = enabled
    }

    override suspend fun setDynamicColor(enabled: Boolean) = edit { prefs ->
        prefs[DYNAMIC_COLOR] = enabled
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(dispatchers.io) { dataStore.edit(block) }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        customHosts = this[CUSTOM_HOSTS].orEmpty(),
        currentHost = this[CURRENT_HOST]?.takeIf { it.isNotBlank() },
        lockedHost = this[LOCKED_HOST]?.takeIf { it.isNotBlank() },
        glassEnabled = this[GLASS_ENABLED] ?: true,
        dynamicColor = this[DYNAMIC_COLOR] ?: true,
    )

    companion object {
        private val CUSTOM_HOSTS = stringSetPreferencesKey("custom_hosts")
        private val CURRENT_HOST = stringPreferencesKey("current_host")
        private val LOCKED_HOST = stringPreferencesKey("locked_host")
        private val GLASS_ENABLED = booleanPreferencesKey("glass_enabled")
        private val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }
}
