package com.qgming.zlib.feature.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchFiltersTest {

    @Test
    fun `query maps chips and year range`() {
        val filters = SearchFilters(
            languages = setOf("english", "chinese"),
            extensions = setOf("epub"),
            yearFrom = "1965",
            yearTo = "2024",
            exact = true,
            order = "popular",
        )
        val query = filters.toQuery("  dune  ")

        assertEquals("dune", query.message)
        assertEquals(listOf("chinese", "english"), query.languages)
        assertEquals(listOf("epub"), query.extensions)
        assertEquals(1965, query.yearFrom)
        assertEquals(2024, query.yearTo)
        assertTrue(query.exact)
        assertEquals("popular", query.order)
    }

    @Test
    fun `invalid years are ignored and range validation catches reversal`() {
        val filters = SearchFilters(yearFrom = "20x4", yearTo = "2020")
        val query = filters.toQuery("x")
        assertEquals(null, query.yearFrom)
        assertEquals(2020, query.yearTo)
        assertTrue(filters.yearRangeValid)

        assertFalse(SearchFilters(yearFrom = "2024", yearTo = "2000").yearRangeValid)
    }
}
