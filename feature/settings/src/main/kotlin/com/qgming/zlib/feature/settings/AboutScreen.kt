package com.qgming.zlib.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.designsystem.ZlibAboutGroup
import com.qgming.zlib.core.designsystem.ZlibAboutHeader
import com.qgming.zlib.core.designsystem.ZlibAboutLink
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState
import com.qgming.zlib.core.network.GitHubReleaseClient

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
    checkingUpdate: Boolean = false,
) {
    val uriHandler = LocalUriHandler.current
    val toast = rememberZlibToastHostState()
    val cannotOpenLink = stringResource(R.string.about_link_failed)
    val openLink: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: IllegalArgumentException) {
            toast.showMessage(cannotOpenLink)
        }
    }
    ZlibToastHost(toast)
    ZlibPage(
        title = stringResource(R.string.about_title), onBack = onBack, expandable = true,
    ) { padding ->
        ZlibCardList(padding) {
            item("identity") {
                ZlibAboutHeader(info.name, stringResource(R.string.about_version, info.versionName), icon)
            }
            item("support") {
                ZlibAboutGroup {
                    ZlibAboutLink(
                        title = stringResource(R.string.about_check_update),
                        endText = if (checkingUpdate) stringResource(R.string.about_update_checking) else null,
                        enabled = !checkingUpdate,
                        onClick = onCheckUpdate,
                    )
                    ZlibAboutLink(stringResource(R.string.about_changelog), onClick = onOpenChangelog)
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
