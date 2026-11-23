package com.qgming.zlib.feature.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibAboutText
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibExpandableText
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard

@Composable
fun AboutDocumentScreen(page: AboutPage, onBack: () -> Unit, viewModel: AboutDocumentsViewModel = hiltViewModel()) {
    val licenses by viewModel.state.collectAsStateWithLifecycle()
    var expandedIds by rememberSaveable(page) { mutableStateOf(emptyList<String>()) }
    val title = stringResource(when (page) {
        AboutPage.Privacy -> R.string.about_privacy
        AboutPage.Licenses -> R.string.about_licenses
    })

    LaunchedEffect(page, viewModel) { if (page == AboutPage.Licenses) viewModel.loadLicenses() }

    ZlibPage(title = title, onBack = onBack) { padding ->
        ZlibCardList(padding) {
            when (page) {
                AboutPage.Privacy -> {
                    document("terms", R.string.about_terms_heading, R.string.about_terms_body)
                    document("privacy", R.string.about_privacy_heading, R.string.about_privacy_body)
                }
                AboutPage.Licenses -> {
                    if (licenses.loading) item("loading") { ZlibLoadingStatus(stringResource(R.string.about_license_loading)) }
                    if (licenses.failed) item("error") {
                        ZlibStateCard(
                            stringResource(R.string.about_license_error_title), stringResource(R.string.about_license_error_body),
                            stringResource(R.string.about_retry), viewModel::loadLicenses,
                        )
                    }
                    items(licenses.entries, key = { it.id }) { entry ->
                        ZlibExpandableText(
                            title = when (entry.id) {
                                "project" -> stringResource(R.string.about_license_project_title)
                                else -> entry.title
                            },
                            summary = entry.license.takeIf(String::isNotBlank),
                            body = entry.body,
                            expanded = entry.id in expandedIds,
                            onToggle = { expandedIds = if (entry.id in expandedIds) expandedIds - entry.id else expandedIds + entry.id },
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.document(key: String, title: Int, body: Int) {
    item(key) { ZlibAboutText(stringResource(title), stringResource(body)) }
}
