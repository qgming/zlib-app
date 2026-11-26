package com.qgming.zlib.feature.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.data.update.UpdateDownloadState
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.model.UpdateCheckResult
import com.qgming.zlib.core.network.GitHubReleaseClient
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
fun AppUpdateHost(info: AboutAppInfo, viewModel: AboutViewModel) {
    val state by viewModel.update.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val localMessages = remember { Channel<String>(Channel.CONFLATED) }
    val installFailed = stringResource(R.string.about_update_install_failed)
    val alreadyCurrent = stringResource(R.string.about_update_current)
    val networkEnvironment = stringResource(R.string.about_update_network_error)
    val cannotOpenLink = stringResource(R.string.about_link_failed)
    val messages = remember(viewModel, localMessages, alreadyCurrent, networkEnvironment) {
        merge(
            localMessages.receiveAsFlow(),
            viewModel.events.map { event ->
                when (event) {
                    AboutUpdateEvent.AlreadyCurrent -> alreadyCurrent
                    AboutUpdateEvent.NetworkEnvironment -> networkEnvironment
                }
            },
        )
    }
    ZlibToastHost(messages)
    fun openInstaller() {
        val file = viewModel.installerFile() ?: return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("APK", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: ActivityNotFoundException) {
            localMessages.trySend(installFailed)
        } catch (_: IllegalArgumentException) {
            localMessages.trySend(installFailed)
        }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDirectoryAndDownload)
    }
    LaunchedEffect(viewModel, info.versionName) { viewModel.checkOnStart(info.versionName) }
    val visibleRelease = (state.result as? UpdateCheckResult.Available)?.release
    val visibleDownload = download.takeIf { visibleRelease != null && it.release?.tag == visibleRelease.tag } ?: UpdateDownloadState()
    AboutUpdateSheet(
        state, visibleDownload, info.versionName, viewModel::dismissUpdate, viewModel::cancelUpdate,
        { viewModel.checkUpdate(info.versionName) }, viewModel::downloadUpdate, { directory.launch(null) },
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) openInstaller()
            else try {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            } catch (_: ActivityNotFoundException) {
                localMessages.trySend(installFailed)
            }
        },
        onOpenReleases = {
            try {
                uriHandler.openUri(GitHubReleaseClient.RELEASES_URL)
            } catch (_: IllegalArgumentException) {
                localMessages.trySend(cannotOpenLink)
            } catch (_: SecurityException) {
                localMessages.trySend(cannotOpenLink)
            }
        },
    )
}
