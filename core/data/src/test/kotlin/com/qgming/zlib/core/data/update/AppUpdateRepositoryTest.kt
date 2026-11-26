package com.qgming.zlib.core.data.update

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.ReleaseApk
import com.qgming.zlib.core.model.UpdateCheckResult
import com.qgming.zlib.core.network.GitHubReleaseClient
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class AppUpdateRepositoryTest {
    private fun repository(tag: String?) = AppUpdateRepository {
        AppResult.Success(tag?.let { AppRelease(it, "", "https://github.com/qgming/zlib-app/releases") })
    }

    @Test fun `checks distinguish update current no release and unknown tag`() = runTest {
        assertIs<UpdateCheckResult.Available>(assertIs<AppResult.Success<UpdateCheckResult>>(repository("v0.10.0").check("0.9.0")).value)
        for (tag in listOf("v0.1.0", "v0.0.9")) {
            assertIs<UpdateCheckResult.Current>(assertIs<AppResult.Success<UpdateCheckResult>>(repository(tag).check("0.1.0")).value)
        }
        assertEquals(UpdateCheckResult.NoRelease, assertIs<AppResult.Success<UpdateCheckResult>>(repository(null).check("0.1.0")).value)
        assertIs<UpdateCheckResult.Uncomparable>(assertIs<AppResult.Success<UpdateCheckResult>>(repository("nightly").check("0.1.0")).value)
    }

    @Test fun `transport failure is preserved`() = runTest {
        val error = AppResult.Failure(AppError.Network(IOException("Offline")))
        assertEquals(error, AppUpdateRepository { error }.check("0.1.0"))
    }

    @Test fun `only a live response can report latest or offer an update`() = runTest {
        val release = AppRelease("v1.2.0", "Notes", GitHubReleaseClient.RELEASES_URL)
        val failure = AppError.Server(429, "Limited", 1_790_000_000_000L)
        var latest: AppResult<AppRelease?> = AppResult.Success(release)
        val cache = MemoryReleaseCache()
        val repository = AppUpdateRepository({ latest }, { AppResult.Success(emptyList()) }, cache)
        assertIs<UpdateCheckResult.Available>(assertIs<AppResult.Success<UpdateCheckResult>>(repository.check("1.1.0")).value)
        latest = AppResult.Failure(failure)
        for (installed in listOf("1.0.0", "1.2.0")) {
            assertEquals(UpdateCheckResult.Cached(release, failure), assertIs<AppResult.Success<UpdateCheckResult>>(repository.check(installed)).value)
        }
        latest = AppResult.Success(null)
        assertEquals(UpdateCheckResult.NoRelease, assertIs<AppResult.Success<UpdateCheckResult>>(repository.check("1.1.0")).value)
        latest = AppResult.Failure(failure)
        assertEquals(latest, repository.check("1.1.0"))
    }

    @Test fun `history cache survives repository recreation and keeps refresh failure visible`() = runTest {
        val release = AppRelease("v1.2.0", "Notes", GitHubReleaseClient.RELEASES_URL)
        val cache = MemoryReleaseCache()
        val first = AppUpdateRepository({ AppResult.Success(release) }, { AppResult.Success(listOf(release)) }, cache)
        assertEquals(ReleaseHistorySnapshot(listOf(release)), assertIs<AppResult.Success<ReleaseHistorySnapshot>>(first.releases()).value)
        val failure = AppError.Network(IOException("Offline"))
        val recreated = AppUpdateRepository({ AppResult.Failure(failure) }, { AppResult.Failure(failure) }, cache)
        assertEquals(ReleaseHistorySnapshot(listOf(release), failure), assertIs<AppResult.Success<ReleaseHistorySnapshot>>(recreated.releases()).value)
    }

    @Test fun `persisted release metadata contains no asset or response URL and remains bounded`() {
        val remote = AppRelease("v1.1.0", "两行\nnotes", "https://example.org/details", apks = listOf(ReleaseApk("test.apk", "https://example.org/apk", 1)))
        val snapshot = decodeReleaseSnapshot(encodeReleaseSnapshot(List(21) { remote }))
        assertEquals(List(20) { remote.copy(pageUrl = GitHubReleaseClient.RELEASES_URL, apks = emptyList()) }, snapshot)
    }

    private class MemoryReleaseCache : ReleaseMetadataCache {
        private val values = mutableMapOf<ReleaseCacheKind, List<AppRelease>>()
        override suspend fun read(kind: ReleaseCacheKind) = values[kind].orEmpty()
        override suspend fun write(kind: ReleaseCacheKind, releases: List<AppRelease>) { values[kind] = releases }
    }
}
