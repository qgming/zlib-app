package com.qgming.zlib.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppReleaseTest {
    @Test
    fun `numeric components compare numerically with no overflow`() {
        assertTrue(compareReleaseVersions("v0.10.0", "0.9.9")!! > 0)
        assertTrue(compareReleaseVersions("2.0.0", "10.0.0")!! < 0)
        assertTrue(compareReleaseVersions("999999999999999999999.0.0", "10.0.0")!! > 0)
    }

    @Test
    fun `prefix and build metadata do not change precedence`() {
        assertEquals(0, compareReleaseVersions("v0.1.0+build.7", "0.1.0+local.2"))
        assertEquals(0, compareReleaseVersions("V1.0.0", "1.0.0"))
    }

    @Test
    fun `prereleases follow SemVer order`() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        versions.zipWithNext().forEach { (older, newer) ->
            assertTrue(compareReleaseVersions(newer, older)!! > 0, "$newer > $older")
        }
    }

    @Test
    fun `ambiguous and invalid versions are not declared current`() {
        listOf("latest", "1.0", "1.00.0", "1.0.0-beta.01", "1.0.0-", "v", "", "release-1.0.0").forEach {
            assertNull(compareReleaseVersions(it, "0.1.0"), it)
        }
        assertNull(compareReleaseVersions("1.0.0", "development"))
    }

    @Test fun `APK selection respects device ABI and refuses ambiguous assets`() {
        val arm = ReleaseApk("app-arm64-v8a.apk", "", 1)
        val x86 = ReleaseApk("app-x86_64.apk", "", 1)
        val universal = ReleaseApk("app.apk", "", 1)
        val release = AppRelease("v1.0.0", "", "", apks = listOf(arm, x86, universal))
        assertEquals(arm, release.apkFor(listOf("arm64-v8a")))
        assertEquals(universal, release.apkFor(listOf("armeabi-v7a")))
        assertNull(release.copy(apks = listOf(x86)).apkFor(listOf("arm64-v8a")))
        assertNull(release.copy(apks = listOf(universal, universal.copy(name = "second.apk"))).apkFor(listOf("arm64-v8a")))
    }
}
