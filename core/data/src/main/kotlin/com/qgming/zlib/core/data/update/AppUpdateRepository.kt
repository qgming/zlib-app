package com.qgming.zlib.core.data.update

import android.content.Context
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.UpdateCheckResult
import com.qgming.zlib.core.model.compareReleaseVersions
import com.qgming.zlib.core.network.GitHubReleaseClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ReleaseHistorySnapshot(val releases: List<AppRelease>, val cachedAfter: AppError? = null)

@Singleton
class AppUpdateRepository internal constructor(
    private val latest: suspend () -> AppResult<AppRelease?>,
    private val history: suspend () -> AppResult<List<AppRelease>>,
    private val cache: ReleaseMetadataCache? = null,
) {
    internal constructor(latest: suspend () -> AppResult<AppRelease?>) : this(latest, { AppResult.Success(emptyList()) })
    @Inject constructor(
        client: GitHubReleaseClient,
        @ApplicationContext context: Context,
        dispatchers: AppDispatchers,
    ) : this(client::latestRelease, client::releaseHistory, FileReleaseMetadataCache(context.cacheDir, dispatchers.io))

    suspend fun releases(): AppResult<ReleaseHistorySnapshot> = when (val response = history()) {
        is AppResult.Success -> {
            cache?.write(ReleaseCacheKind.History, response.value)
            AppResult.Success(ReleaseHistorySnapshot(response.value))
        }
        is AppResult.Failure -> {
            val cached = cache?.read(ReleaseCacheKind.History).orEmpty().ifEmpty {
                cache?.read(ReleaseCacheKind.Latest).orEmpty()
            }
            if (cached.isEmpty()) response else AppResult.Success(ReleaseHistorySnapshot(cached, response.error))
        }
    }

    suspend fun check(installedVersion: String): AppResult<UpdateCheckResult> = when (val response = latest()) {
        is AppResult.Success -> {
            val release = response.value
            cache?.write(ReleaseCacheKind.Latest, listOfNotNull(release))
            AppResult.Success(when {
                release == null -> UpdateCheckResult.NoRelease
                else -> when (val comparison = compareReleaseVersions(release.tag, installedVersion)) {
                    null -> UpdateCheckResult.Uncomparable(release)
                    else -> if (comparison > 0) UpdateCheckResult.Available(release) else UpdateCheckResult.Current(release)
                }
            })
        }
        is AppResult.Failure -> {
            val cached = cache?.read(ReleaseCacheKind.Latest)?.singleOrNull()
            if (cached == null) response else {
                AppResult.Success(UpdateCheckResult.Cached(cached, response.error))
            }
        }
    }
}
