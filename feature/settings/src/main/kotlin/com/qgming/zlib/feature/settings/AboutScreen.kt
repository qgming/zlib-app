package com.qgming.zlib.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.designsystem.ZlibAboutGroup
import com.qgming.zlib.core.designsystem.ZlibAboutHeader
import com.qgming.zlib.core.designsystem.ZlibAboutLink
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSnackbarHost
import com.qgming.zlib.core.designsystem.rememberZlibSnackbarHostState
import com.qgming.zlib.core.network.GitHubReleaseClient
import kotlinx.coroutines.launch

data class AboutAppInfo(val name: String, val versionName: String, val versionCode: Long)

enum class AboutPage { Privacy, Licenses }

@Composable
fun AboutScreen(
    info: AboutAppInfo,
    icon: Painter,
    onBack: () -> Unit,
    onOpenPage: (AboutPage) -> Unit,
    onOpenChangelog: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val snackbar = rememberZlibSnackbarHostState()
    val cannotOpenLink = stringResource(R.string.about_link_failed)
    val openLink: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            scope.launch { snackbar.showMessage(cannotOpenLink) }
        }
    }
    ZlibPage(
        title = stringResource(R.string.about_title), onBack = onBack, expandable = true,
        snackbarHost = { ZlibSnackbarHost(snackbar) },
    ) { padding ->
        ZlibCardList(padding) {
            item("identity") {
                ZlibAboutHeader(info.name, stringResource(R.string.about_version, info.versionName), icon)
            }
            item("support") {
                ZlibAboutGroup {
                    ZlibAboutLink(stringResource(R.string.about_check_update), onCheckUpdate)
                    ZlibAboutLink(stringResource(R.string.about_changelog), onOpenChangelog)
                }
            }
            item("project") {
                ZlibAboutGroup {
                    ZlibAboutLink(stringResource(R.string.about_privacy)) { onOpenPage(AboutPage.Privacy) }
                    ZlibAboutLink(stringResource(R.string.about_licenses)) { onOpenPage(AboutPage.Licenses) }
                }
            }
            item("author") {
                ZlibAboutGroup {
                    ZlibAboutLink(stringResource(R.string.about_author)) { openLink(GitHubReleaseClient.AUTHOR_URL) }
                }
            }
        }
    }
}
