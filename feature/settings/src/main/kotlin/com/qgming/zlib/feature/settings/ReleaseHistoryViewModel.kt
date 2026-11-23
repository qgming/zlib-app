package com.qgming.zlib.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.update.AppUpdateRepository
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.compareReleaseVersions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ReleaseHistoryState(val loading: Boolean = false, val loaded: Boolean = false, val releases: List<AppRelease> = emptyList(), val failed: Boolean = false)

@HiltViewModel
class ReleaseHistoryViewModel internal constructor(private val loadReleases: suspend () -> AppResult<List<AppRelease>>) : ViewModel() {
    @Inject constructor(repository: AppUpdateRepository) : this(repository::releases)

    private val _state = MutableStateFlow(ReleaseHistoryState())
    val state = _state.asStateFlow()
    fun load() {
        if (_state.value.loading || _state.value.loaded) return
        _state.value = ReleaseHistoryState(loading = true)
        viewModelScope.launch {
            _state.value = when (val result = loadReleases()) {
                is AppResult.Success -> ReleaseHistoryState(loaded = true, releases = orderedReleaseHistory(result.value))
                is AppResult.Failure -> ReleaseHistoryState(failed = true)
            }
        }
    }
}

/** Comparable version tags come first; dated non-version tags remain visible afterwards. */
internal fun orderedReleaseHistory(releases: List<AppRelease>): List<AppRelease> =
    releases.distinctBy { it.tag }.sortedWith { left, right ->
        val leftVersion = compareReleaseVersions(left.tag, left.tag) != null
        val rightVersion = compareReleaseVersions(right.tag, right.tag) != null
        when {
            leftVersion != rightVersion -> if (leftVersion) -1 else 1
            leftVersion -> compareReleaseVersions(right.tag, left.tag)!!.takeIf { it != 0 }
                ?: releaseTime(right).compareTo(releaseTime(left))
            else -> releaseTime(right).compareTo(releaseTime(left))
        }
    }

private fun releaseTime(release: AppRelease): Instant =
    release.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.MIN
