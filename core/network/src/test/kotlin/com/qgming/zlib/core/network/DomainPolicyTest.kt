package com.qgming.zlib.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DomainPolicyTest {

    @Test
    fun `built-in suffixes and their subdomains are allowed`() {
        assertTrue(DomainPolicy.isAllowed("z-lib.gd"))
        assertTrue(DomainPolicy.isAllowed("de1.z-lib.gd"))
        assertTrue(DomainPolicy.isAllowed("Z-LIB.GD"))
        assertFalse(DomainPolicy.isAllowed("z-lib.gd.evil.com"))
        assertFalse(DomainPolicy.isAllowed("evilz-lib.gd"))
    }

    @Test
    fun `directly usable hosts are probed first`() {
        assertEquals(listOf("zlib.bz", "zlib.re", "z101.by"), DomainPolicy.DEFAULT_PROBE_HOSTS.take(3))
        assertEquals(DomainPolicy.DEFAULT_PROBE_HOSTS.distinct(), DomainPolicy.DEFAULT_PROBE_HOSTS)
        // DiamWall-protected hosts and the expired-certificate host come last (ADR-066).
        assertEquals(
            listOf("z-library.im", "z-lib.sk", "z-library.sk", "1lib.sk", "z-lib.fm"),
            DomainPolicy.DEFAULT_PROBE_HOSTS.takeLast(5),
        )
        assertTrue(DomainPolicy.isBuiltIn("zlib.re"))
        assertTrue(DomainPolicy.isBuiltIn("z101.by"))
        assertTrue(DomainPolicy.isBuiltIn("z-library.gy"))
        assertTrue(DomainPolicy.isAllowed("www.zlib.re"))
        assertTrue(DomainPolicy.isAllowed("zh.z101.by"))
        assertFalse(DomainPolicy.isAllowed("zlib.re.evil.test"))
        assertFalse(DomainPolicy.isAllowed("z101.by.evil.test"))
        assertTrue(DomainPolicy.DEFAULT_PROBE_HOSTS.all { DomainPolicy.isBuiltIn(it) })
        assertEquals(DomainPolicy.BUILT_IN_SUFFIXES.toSet(), DomainPolicy.DEFAULT_PROBE_HOSTS.toSet())
    }

    @Test
    fun `community catalog entries allow exact anonymous discovery only`() {
        val community = DomainPolicy.COMMUNITY_MIRROR_HOSTS
        assertEquals(34, community.size)
        assertEquals(community.distinct(), community)
        assertTrue(community.all { it == DomainPolicy.normalizeHostInput(it) })
        assertTrue(community.all(DomainPolicy::isCatalogHost))
        assertTrue(community.none(DomainPolicy::isBuiltIn))
        assertTrue(community.none { DomainPolicy.isAllowed(it) })
        assertTrue(DomainPolicy.isCatalogHost("z-library.gy"))
        assertFalse(DomainPolicy.isCatalogHost("zh.z-library.gy"))
        assertTrue(DomainPolicy.isCommunityMirror("libb.la"))
        assertFalse(DomainPolicy.isCommunityMirror("sub.libb.la"))
        assertFalse(DomainPolicy.isCatalogHost("sub.libb.la"))
        assertFalse(DomainPolicy.isCatalogHost("libb.la.example.test"))
        assertTrue(DomainPolicy.isAllowed("libb.la", setOf("libb.la")))
        assertFalse(DomainPolicy.isAllowed("sub.libb.la", setOf("libb.la")))
    }

    @Test
    fun `known domain families are recognised for redirect verification`() {
        assertTrue(DomainPolicy.isKnownDomainFamily("z-library.sk"))
        assertTrue(DomainPolicy.isKnownDomainFamily("www.z-lib.gd"))
        assertFalse(DomainPolicy.isKnownDomainFamily("phish.test"))
        assertFalse(DomainPolicy.isKnownDomainFamily("z-library.sk.evil.test"))
    }

    @Test
    fun `custom mirrors trust the exact host only`() {
        val custom = setOf("mirror.example.com")
        assertTrue(DomainPolicy.isAllowed("mirror.example.com", custom))
        assertFalse(DomainPolicy.isAllowed("sub.mirror.example.com", custom))
        assertFalse(DomainPolicy.isAllowed("mirror.example.com.evil.test", custom))
    }

    @Test
    fun `host input is normalised`() {
        assertEquals("z-lib.gd", DomainPolicy.normalizeHostInput("z-lib.gd"))
        assertEquals("z-lib.gd", DomainPolicy.normalizeHostInput("https://z-lib.gd/"))
        assertEquals("z-lib.gd", DomainPolicy.normalizeHostInput(" https://Z-LIB.gd "))
        assertNull(DomainPolicy.normalizeHostInput("http://z-lib.gd"))
        assertNull(DomainPolicy.normalizeHostInput("https://z-lib.gd:8443"))
        assertNull(DomainPolicy.normalizeHostInput("https://z-lib.gd/path"))
        assertNull(DomainPolicy.normalizeHostInput("https://user:pass@z-lib.gd"))
        assertNull(DomainPolicy.normalizeHostInput("1.2.3.4"))
        assertNull(DomainPolicy.normalizeHostInput("localhost"))
        assertNull(DomainPolicy.normalizeHostInput("https://singlelabel"))
        assertNull(DomainPolicy.normalizeHostInput("ftp://z-lib.gd"))
        assertNull(DomainPolicy.normalizeHostInput(""))
    }

    @Test
    fun `custom host validation rejects built-in and malformed input`() {
        assertTrue(DomainPolicy.validateCustomHost("mirror.example.org") is com.qgming.zlib.core.common.AppResult.Success)
        assertTrue(DomainPolicy.validateCustomHost("z-lib.gd") is com.qgming.zlib.core.common.AppResult.Failure)
        assertTrue(DomainPolicy.validateCustomHost("http://mirror.example.org") is com.qgming.zlib.core.common.AppResult.Failure)
    }
}
