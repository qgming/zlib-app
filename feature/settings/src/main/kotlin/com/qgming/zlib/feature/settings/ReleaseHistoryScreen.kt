package com.qgming.zlib.feature.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibAboutGroup
import com.qgming.zlib.core.designsystem.ZlibAboutLink
import com.qgming.zlib.core.designsystem.ZlibAboutText
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibExpandableText
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.network.GitHubReleaseClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun ReleaseHistoryScreen(onBack: () -> Unit, viewModel: ReleaseHistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val localMessages = remember { Channel<String>(Channel.CONFLATED) }
    val cannotOpenLink = stringResource(R.string.about_link_failed)
    val networkEnvironment = stringResource(R.string.about_update_network_error)
    // Null means untouched: use the newest entry once data arrives. An empty list means
    // the user explicitly closed everything and must survive scrolling and recreation.
    var expandedTags by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val expanded = expandedTags ?: state.releases.take(1).map { it.tag }
    val messages = remember(viewModel, localMessages, networkEnvironment) {
        merge(localMessages.receiveAsFlow(), viewModel.networkFailures.map { networkEnvironment })
    }
    ZlibToastHost(messages)
    LaunchedEffect(viewModel) { viewModel.load() }
    ZlibPage(stringResource(R.string.about_changelog), onBack = onBack) { padding ->
        ZlibCardList(padding) {
            if (state.loading) item("loading") { ZlibLoading() }
            val failure = state.failure
            if (failure != null) {
                item("failed") {
                    val message = if (failure == UpdateFailure.NetworkEnvironment) {
                        R.string.about_changelog_unavailable
                    } else {
                        R.string.about_update_response_error
                    }
                    ZlibStateCard(
                        stringResource(R.string.about_changelog_failed), stringResource(message),
                        stringResource(R.string.about_retry), viewModel::load,
                    )
                }
                item("github") {
                    ZlibAboutGroup {
                        ZlibAboutLink(stringResource(R.string.about_open_github_releases)) {
                            try {
                                uriHandler.openUri(GitHubReleaseClient.RELEASES_URL)
                            } catch (_: IllegalArgumentException) {
                                localMessages.trySend(cannotOpenLink)
                            } catch (_: SecurityException) {
                                localMessages.trySend(cannotOpenLink)
                            }
                        }
                    }
                }
            }
            if (state.cached) item("cached") {
                ZlibAboutText(stringResource(R.string.about_update_cached_history), stringResource(R.string.about_update_cached_history_body))
            }
            if (state.loaded && state.releases.isEmpty()) item("empty") {
                ZlibAboutText(stringResource(R.string.about_update_no_release), stringResource(R.string.about_update_no_release_body))
            }
            items(state.releases, key = { it.tag }) { release ->
                ZlibExpandableText(
                    title = release.title.ifBlank { release.tag },
                    summary = release.tag.takeUnless { it == release.title || release.title.isBlank() },
                    body = release.notes.ifBlank { stringResource(R.string.about_update_no_notes) },
                    expanded = release.tag in expanded,
                    onToggle = { expandedTags = if (release.tag in expanded) expanded - release.tag else expanded + release.tag },
                )
            }
        }
    }
}
