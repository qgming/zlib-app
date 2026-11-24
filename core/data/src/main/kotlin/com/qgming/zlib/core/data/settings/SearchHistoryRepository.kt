package com.qgming.zlib.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

private val Context.searchHistoryDataStore by preferencesDataStore(name = "zlib_search_history")

data class SearchHistory(val queries: List<String> = emptyList())

internal object RecentSearches {
    fun add(queries: List<String>, query: String): List<String> {
        val normalized = query.trim().take(200)
        return if (normalized.isBlank()) queries else (listOf(normalized) + queries.filterNot {
            it.equals(normalized, ignoreCase = true)
        }).take(10)
    }
}

@Singleton
class SearchHistoryRepository internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.searchHistoryDataStore)

    val history = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map { prefs ->
        SearchHistory(decode(prefs[QUERIES]))
    }

    suspend fun record(query: String) {
        store.edit { prefs ->
            prefs[QUERIES] = Json.encodeToString(RecentSearches.add(decode(prefs[QUERIES]), query))
        }
    }

    suspend fun remove(query: String) { store.edit { it[QUERIES] = Json.encodeToString(decode(it[QUERIES]).filterNot { value -> value == query }) } }
    suspend fun clear() { store.edit { it.remove(QUERIES) } }

    private fun decode(value: String?): List<String> = value?.let {
        runCatching { Json.decodeFromString<List<String>>(it).filter(String::isNotBlank).take(10) }.getOrNull()
    }.orEmpty()

    private companion object {
        val QUERIES = stringPreferencesKey("queries")
    }
}
