package com.qgming.zlib.feature.downloads

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibDownloadAction
import com.qgming.zlib.core.designsystem.ZlibDownloadActionButton
import com.qgming.zlib.core.designsystem.ZlibDownloadHistoryButton
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.DownloadState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenDomains: () -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val loadedQueue = queue
    var notice by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<DownloadItem?>(null) }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDirectory)
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { notice = it }
    }
    val (records, tasks) = loadedQueue.orEmpty().partition { it.state == DownloadState.COMPLETED }

    ZlibPage(title = stringResource(TextR.string.text_download_manager), onBack = onBack, actions = { ZlibDownloadHistoryButton(onOpenHistory) }) { padding ->
        ZlibCardList(padding) {
            notice?.let { message ->
                item(key = "download-notice") {
                    ZlibStateCard(appText(TextR.string.text_download_notice), message, actionLabel = appText(TextR.string.text_ok), onAction = { notice = null })
                }
            }
            if (loadedQueue == null) {
                item(key = "download-loading") { ZlibLoading() }
            } else if (loadedQueue.isEmpty()) {
                item(key = "download-empty") {
                    ZlibStateCard(title = stringResource(TextR.string.text_no_download_tasks), message = stringResource(TextR.string.text_tap_download_in_a_book_s_details_to_add_a_task_here))
                }
            }
            if (tasks.isNotEmpty()) {
                item(key = "download-tasks") { ZlibSectionTitle(stringResource(TextR.string.text_download_tasks)) }
                items(tasks, key = { it.id }) { item -> DownloadRow(item, viewModel, onShowFailure = { failure = item }) }
            }
            if (records.isNotEmpty()) {
                item(key = "download-records") { ZlibSectionTitle(stringResource(TextR.string.text_completed)) }
                items(records, key = { it.id }) { item -> DownloadRow(item, viewModel, onShowFailure = { failure = item }) }
            }
        }
        ZlibGlassBottomSheet(show = failure != null, title = stringResource(TextR.string.text_download_incomplete), onDismiss = { failure = null }) {
            failure?.let { item ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.error.orEmpty().errorLabel(), style = MiuixTheme.textStyles.body2)
                    when (item.error) {
                        "session", "quota" -> ZlibMenuAction(stringResource(TextR.string.text_manage_accounts_and_quota)) { failure = null; onOpenAccount() }
                        "domain" -> ZlibMenuAction(stringResource(TextR.string.text_switch_domain)) { failure = null; onOpenDomains() }
                        "storage", "storage_permission" -> ZlibMenuAction(stringResource(TextR.string.text_choose_download_folder_again)) { failure = null; directoryPicker.launch(null) }
                    }
                    if (item.error != "quota") ZlibMenuAction(stringResource(TextR.string.text_retry_download)) { failure = null; viewModel.retry(item.id) }
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    viewModel: DownloadsViewModel,
    onShowFailure: () -> Unit,
) {
    val progress = item.progress
    val waiting = item.state == DownloadState.RESOLVING || (item.state == DownloadState.DOWNLOADING && progress == null)
    ZlibCard(cornerRadius = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title.ifBlank { item.fileName }, style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (waiting) {
                    ZlibLoadingStatus(item.state.label())
                } else {
                    Text(
                        item.state.label() +
                            if (item.state == DownloadState.FAILED) {
                                item.error?.let { " · ${it.errorLabel()}" }.orEmpty()
                            } else {
                                ""
                            },
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (item.state == DownloadState.FAILED) {
                            MiuixTheme.colorScheme.error
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                }
                if (item.state == DownloadState.FAILED) TextButton(text = stringResource(TextR.string.text_view_details), onClick = onShowFailure)
                item.totalBytes?.let { total ->
                    if (progress != null && (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PAUSED)) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${item.bytesWritten.sizeText()} / ${total.sizeText()}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            Row {
                val primary = when (item.state) {
                    DownloadState.QUEUED, DownloadState.RESOLVING, DownloadState.DOWNLOADING -> ZlibDownloadAction.Pause
                    DownloadState.PAUSED -> ZlibDownloadAction.Resume
                    DownloadState.FAILED, DownloadState.CANCELLED -> ZlibDownloadAction.Retry
                    DownloadState.COMPLETED -> null
                }
                if (primary != null) ZlibDownloadActionButton(primary) {
                    when (primary) {
                        ZlibDownloadAction.Pause -> viewModel.pause(item.id)
                        ZlibDownloadAction.Resume -> viewModel.resume(item.id)
                        ZlibDownloadAction.Retry -> viewModel.retry(item.id)
                        ZlibDownloadAction.Remove -> viewModel.remove(item.id)
                    }
                }
                ZlibDownloadActionButton(ZlibDownloadAction.Remove) { viewModel.remove(item.id) }
            }
        }
    }
}

private fun DownloadState.label(): String = when (this) {
    DownloadState.QUEUED -> appText(TextR.string.text_queued)
    DownloadState.RESOLVING -> appText(TextR.string.text_resolving_url)
    DownloadState.DOWNLOADING -> appText(TextR.string.text_downloading)
    DownloadState.COMPLETED -> appText(TextR.string.text_completed)
    DownloadState.FAILED -> appText(TextR.string.text_failed)
    DownloadState.PAUSED -> appText(TextR.string.text_paused)
    DownloadState.CANCELLED -> appText(TextR.string.text_canceled)
}

private fun String.errorLabel(): String = when (this) {
    "quota" -> appText(TextR.string.text_daily_quota_exhausted)
    "session" -> appText(TextR.string.text_session_expired)
    "domain" -> appText(TextR.string.text_domain_blocked)
    "network" -> appText(TextR.string.text_network_error)
    "storage" -> appText(TextR.string.text_write_failed)
    "storage_permission" -> appText(TextR.string.text_download_folder_permission_expired_grant_access_again)
    "range" -> appText(TextR.string.text_invalid_resume_range_the_task_is_retained_please_retry)
    "invalid" -> appText(TextR.string.text_file_verification_failed_please_retry)
    "incomplete" -> appText(TextR.string.text_download_incomplete_progress_is_saved_retry_to_resume_2)
    "http_416" -> appText(TextR.string.text_resume_position_expired_retry_the_download)
    "http_404" -> appText(TextR.string.text_file_not_found)
    else -> appText(TextR.string.text_please_retry)
}

private fun Long.sizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
