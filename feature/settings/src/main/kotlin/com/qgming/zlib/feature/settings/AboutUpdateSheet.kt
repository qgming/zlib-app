package com.qgming.zlib.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.data.update.UpdateDownloadFailure
import com.qgming.zlib.core.data.update.UpdateDownloadPhase
import com.qgming.zlib.core.data.update.UpdateDownloadState
import com.qgming.zlib.core.designsystem.ZlibAboutText
import com.qgming.zlib.core.designsystem.ZlibAboutLink
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibUpdateActions
import com.qgming.zlib.core.designsystem.ZlibUpdateProgress
import com.qgming.zlib.core.model.UpdateCheckResult

@Composable
internal fun AboutUpdateSheet(
    state: AboutUpdateState,
    transfer: UpdateDownloadState,
    installed: String,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onDirectory: () -> Unit,
    onInstall: () -> Unit,
    onOpenReleases: () -> Unit,
) {
    ZlibGlassBottomSheet(state.visible, stringResource(R.string.about_check_update), onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.checking) ZlibLoadingStatus(stringResource(R.string.about_update_checking))
                if (state.failure != null) {
                    ZlibAboutText(stringResource(R.string.about_update_failed), stringResource(R.string.about_update_response_error))
                }
                when (val result = state.result) {
                    UpdateCheckResult.NoRelease -> ZlibAboutText(stringResource(R.string.about_update_no_release), stringResource(R.string.about_update_no_release_body))
                    is UpdateCheckResult.Current -> ZlibAboutText(stringResource(R.string.about_update_current), stringResource(R.string.about_update_installed, installed, result.release.tag))
                    is UpdateCheckResult.Uncomparable -> ZlibAboutText(stringResource(R.string.about_update_uncomparable), stringResource(R.string.about_update_installed, installed, result.release.tag))
                    is UpdateCheckResult.Available -> ZlibAboutText(
                        title = stringResource(R.string.about_update_available, result.release.tag),
                        body = result.release.notes.ifBlank { stringResource(R.string.about_update_no_notes) },
                        markdown = true,
                    )
                    is UpdateCheckResult.Cached -> ZlibAboutText(
                        title = stringResource(R.string.about_update_cached_release, result.release.tag),
                        body = result.release.notes.ifBlank { stringResource(R.string.about_update_no_notes) },
                        markdown = true,
                    )
                    null -> Unit
                }
                if (state.failure != null || state.result is UpdateCheckResult.Uncomparable) {
                    ZlibAboutLink(stringResource(R.string.about_open_github_releases), onClick = onOpenReleases)
                }
                transfer.failure?.let { failure ->
                    ZlibAboutText(stringResource(R.string.about_download_failed), stringResource(when (failure) {
                        UpdateDownloadFailure.Directory -> R.string.about_update_choose_directory
                        UpdateDownloadFailure.Asset -> R.string.about_update_no_apk
                        UpdateDownloadFailure.Transfer -> R.string.about_update_transfer_error
                        UpdateDownloadFailure.Interrupted -> R.string.about_update_interrupted
                        UpdateDownloadFailure.Verification -> R.string.about_update_verification_error
                        UpdateDownloadFailure.Storage -> R.string.about_update_storage_error
                    }))
                }
                if (transfer.phase == UpdateDownloadPhase.Ready) ZlibAboutText(stringResource(R.string.about_update_downloaded), stringResource(R.string.about_update_saved))
            }
            if (transfer.busy) ZlibUpdateProgress(transfer.written, transfer.total, when (transfer.phase) {
                UpdateDownloadPhase.Verifying -> stringResource(R.string.about_update_verifying)
                UpdateDownloadPhase.Saving -> stringResource(R.string.about_update_saving)
                else -> stringResource(R.string.about_update_progress, transfer.written / 1048576.0, transfer.total / 1048576.0, if (transfer.total > 0) (transfer.written * 100 / transfer.total).toInt() else 0)
            })
            val available = state.result is UpdateCheckResult.Available
            val action = when {
                transfer.phase == UpdateDownloadPhase.Ready -> stringResource(R.string.about_update_install)
                transfer.failure == UpdateDownloadFailure.Directory -> stringResource(R.string.about_update_select_directory)
                transfer.busy -> stringResource(R.string.about_update_downloading)
                state.failure != null -> stringResource(R.string.about_retry)
                transfer.failure != null -> stringResource(R.string.about_retry)
                available -> stringResource(R.string.about_update_now)
                else -> null
            }
            ZlibUpdateActions(stringResource(R.string.about_cancel), action, !transfer.busy, onCancel) {
                when {
                    transfer.phase == UpdateDownloadPhase.Ready -> onInstall()
                    transfer.failure == UpdateDownloadFailure.Directory -> onDirectory()
                    state.failure != null -> onRetry()
                    else -> onDownload()
                }
            }
        }
    }
}
