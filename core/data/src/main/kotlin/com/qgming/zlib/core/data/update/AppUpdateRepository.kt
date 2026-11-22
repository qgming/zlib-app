package com.qgming.zlib.core.data.update

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.map
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.UpdateCheckResult
import com.qgming.zlib.core.model.compareReleaseVersions
import com.qgming.zlib.core.network.GitHubReleaseClient
import javax.inject.Inject

class AppUpdateRepository internal constructor(
    private val latest: suspend () -> AppResult<AppRelease?>,
    private val history: suspend () -> AppResult<List<AppRelease>>,
) {
    internal constructor(latest: suspend () -> AppResult<AppRelease?>) : this(latest, { AppResult.Success(emptyList()) })
    @Inject constructor(client: GitHubReleaseClient) : this(client::latestRelease, client::releaseHistory)

    suspend fun releases(): AppResult<List<AppRelease>> = history()

    suspend fun check(installedVersion: String): AppResult<UpdateCheckResult> = latest().map { release ->
        when {
            release == null -> UpdateCheckResult.NoRelease
            else -> when (val comparison = compareReleaseVersions(release.tag, installedVersion)) {
                null -> UpdateCheckResult.Uncomparable(release)
                else -> if (comparison > 0) UpdateCheckResult.Available(release) else UpdateCheckResult.Current(release)
            }
        }
    }
}
