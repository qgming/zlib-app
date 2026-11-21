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
