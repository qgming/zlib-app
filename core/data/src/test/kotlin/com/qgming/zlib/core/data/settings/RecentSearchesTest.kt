package com.qgming.zlib.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class RecentSearchesTest {
    @Test fun `recent searches trim deduplicate and preserve most recent order`() {
        assertEquals(listOf("Dune", "作者"), RecentSearches.add(listOf("作者", "dune"), " Dune "))
        assertEquals(listOf("Dune"), RecentSearches.add(listOf("Dune"), "  "))
    }

    @Test fun `history has a fixed upper bound`() {
        val result = (1..15).fold(emptyList<String>()) { history, item -> RecentSearches.add(history, item.toString()) }
        assertEquals(10, result.size)
        assertEquals("15", result.first())
        assertEquals("6", result.last())
    }

    @Test fun `book history still reads updates and clears the existing queries key`() = runTest {
        val key = stringPreferencesKey("queries")
        val store = object : DataStore<Preferences> {
            override val data = MutableStateFlow(preferencesOf(key to """["Existing book"]"""))

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        val repository = SearchHistoryRepository(store)
        repository.record("Another book")

        val reopened = SearchHistoryRepository(store)
        assertEquals(listOf("Another book", "Existing book"), reopened.history.first().queries)
        reopened.remove("Another book")
        assertEquals(listOf("Existing book"), reopened.history.first().queries)
        reopened.clear()
        assertEquals(emptyList(), reopened.history.first().queries)
    }
}
