package com.qgming.zlib.feature.settings

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibAboutText
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard

@Composable
fun ReleaseHistoryScreen(onBack: () -> Unit, viewModel: ReleaseHistoryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.load() }
    ZlibPage(stringResource(R.string.about_changelog), onBack = onBack) { padding ->
        ZlibCardList(padding) {
            if (state.loading) item("loading") { ZlibLoading() }
            if (state.failed) item("failed") {
                ZlibStateCard(stringResource(R.string.about_update_failed), stringResource(R.string.about_update_response_error), stringResource(R.string.about_retry), viewModel::load)
            }
            if (state.loaded && state.releases.isEmpty()) item("empty") {
                ZlibAboutText(stringResource(R.string.about_update_no_release), stringResource(R.string.about_update_no_release_body))
            }
            items(state.releases, key = { it.tag }) { release ->
                ZlibAboutText("${release.title} · ${release.tag}", release.notes.ifBlank { stringResource(R.string.about_update_no_notes) })
            }
        }
    }
}
