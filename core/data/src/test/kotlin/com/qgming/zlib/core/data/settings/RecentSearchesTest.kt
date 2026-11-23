package com.qgming.zlib.core.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
