package com.qgming.zlib.core.data.update

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateApkIdentityTest {
    private val installed = ApkIdentity("com.qgming.zlib", "0.1.0", 1, setOf("fixture-certificate"))
    private val candidate = installed.copy(version = "0.2.0", versionCode = 2)

    @Test fun `only matching package signer tag and increasing version code are accepted`() {
        assertTrue(validUpdateIdentity(candidate, installed, "v0.2.0"))
        listOf(candidate.copy(packageName = "other.app"), candidate.copy(signers = setOf("wrong-signer")),
            candidate.copy(versionCode = 1), candidate.copy(versionCode = 0), candidate.copy(version = "0.3.0"),
            candidate.copy(signers = emptySet())).forEach { assertFalse(validUpdateIdentity(it, installed, "v0.2.0")) }
        assertFalse(validUpdateIdentity(candidate, installed.copy(signers = emptySet()), "v0.2.0"))
    }

    @Test fun `one point three update accepts the original signature and a code above one point two`() {
        val previous = installed.copy(version = "1.2.0", versionCode = 4)
        val next = previous.copy(version = "1.3.0", versionCode = 5)
        assertTrue(validUpdateIdentity(next, previous, "v1.3.0"))
        assertFalse(validUpdateIdentity(next.copy(versionCode = 4), previous, "v1.3.0"))
        assertFalse(validUpdateIdentity(next.copy(signers = setOf("different-certificate")), previous, "v1.3.0"))
    }
}
