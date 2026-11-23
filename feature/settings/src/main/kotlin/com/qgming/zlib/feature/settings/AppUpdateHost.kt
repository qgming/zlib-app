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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState

@Composable
fun AppUpdateHost(info: AboutAppInfo, viewModel: AboutViewModel) {
    val state by viewModel.update.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val toast = rememberZlibToastHostState()
    val installFailed = stringResource(R.string.about_update_install_failed)
    val alreadyCurrent = stringResource(R.string.about_update_current)
    LaunchedEffect(viewModel, alreadyCurrent) {
        viewModel.events.collect { event ->
            toast.showMessage(when (event) { AboutUpdateEvent.AlreadyCurrent -> alreadyCurrent })
        }
    }
    ZlibToastHost(toast)
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
            toast.showMessage(installFailed)
        } catch (_: IllegalArgumentException) {
            toast.showMessage(installFailed)
        }
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) openInstaller()
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDirectoryAndDownload)
    }
    LaunchedEffect(viewModel, info.versionName) { viewModel.checkOnStart(info.versionName) }
    AboutUpdateSheet(
        state, download, info.versionName, viewModel::dismissUpdate, viewModel::cancelUpdate,
        { viewModel.checkUpdate(info.versionName) }, viewModel::downloadUpdate, { directory.launch(null) },
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) openInstaller()
            else try {
                installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()))
            } catch (_: ActivityNotFoundException) {
                toast.showMessage(installFailed)
            }
        },
    )
}
