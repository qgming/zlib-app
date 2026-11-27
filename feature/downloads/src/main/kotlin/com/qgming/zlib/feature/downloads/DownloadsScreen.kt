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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.launchLocalFile
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.DownloadState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenDomains: () -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val feedbackGeneration by viewModel.feedbackGeneration.collectAsStateWithLifecycle()
    val notices = remember(viewModel, feedbackGeneration) {
        viewModel.events.filter { it.generation == feedbackGeneration && viewModel.canShowNotice(it) }
            .onEach(viewModel::consumeNotice).map { it.message }
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val loadedQueue = queue
    var failureId by remember { mutableStateOf<String?>(null) }
    val failure = loadedQueue?.firstOrNull { it.id == failureId && it.state == DownloadState.FAILED }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDirectory)
    }
    LifecycleStartEffect(viewModel) {
        viewModel.show()
        onStopOrDispose { viewModel.hide() }
    }
    LaunchedEffect(viewModel, lifecycleOwner, context, feedbackGeneration) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.fileIntents.collect { event ->
                if (viewModel.canLaunch(event)) {
                    launchLocalFile(context, event.file)?.let { viewModel.fileLaunchFailed(it, event.generation) }
                }
            }
        }
    }
    ZlibToastHost(notices, key = feedbackGeneration)
    val (records, tasks) = loadedQueue.orEmpty().partition { it.state == DownloadState.COMPLETED }

    ZlibPage(title = stringResource(TextR.string.text_download_manager), onBack = onBack, actions = { ZlibDownloadHistoryButton(onOpenHistory) }) { padding ->
        ZlibCardList(padding) {
            if (loadedQueue == null) {
                item(key = "download-loading") { ZlibLoading() }
            } else if (loadedQueue.isEmpty()) {
                item(key = "download-empty") {
                    ZlibStateCard(title = stringResource(TextR.string.text_no_download_tasks), message = stringResource(TextR.string.text_tap_download_in_a_book_s_details_to_add_a_task_here))
                }
            }
            if (tasks.isNotEmpty()) {
                item(key = "download-tasks") { ZlibSectionTitle(stringResource(TextR.string.text_download_tasks)) }
                items(tasks, key = { it.id }) { item -> DownloadRow(item, viewModel, onShowFailure = { failureId = item.id }) }
            }
            if (records.isNotEmpty()) {
                item(key = "download-records") { ZlibSectionTitle(stringResource(TextR.string.text_completed)) }
                items(records, key = { it.id }) { item -> DownloadRow(item, viewModel, onShowFailure = { failureId = item.id }) }
            }
        }
        ZlibGlassBottomSheet(show = failure != null, title = stringResource(TextR.string.text_download_incomplete), onDismiss = { failureId = null }) {
            failure?.let { item ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(item.error.orEmpty().errorLabel(), style = MiuixTheme.textStyles.body2)
                    when (item.error) {
                        "session", "quota" -> ZlibMenuAction(stringResource(TextR.string.text_manage_accounts_and_quota)) { failureId = null; onOpenAccount() }
                        "domain" -> ZlibMenuAction(stringResource(TextR.string.text_switch_domain)) { failureId = null; onOpenDomains() }
                        "storage", "storage_permission" -> ZlibMenuAction(stringResource(TextR.string.text_choose_download_folder_again)) { failureId = null; directoryPicker.launch(null) }
                    }
                    if (item.error != "quota") ZlibMenuAction(stringResource(TextR.string.text_retry_download)) { failureId = null; viewModel.retry(item.id) }
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
    ZlibCard(
        cornerRadius = 24.dp,
        onClick = if (item.state == DownloadState.COMPLETED) ({ viewModel.openCompleted(item.id) }) else null,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title.ifBlank { item.fileName }, style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (waiting) {
                    ZlibLoadingStatus(item.state.label())
                } else {
                    Text(
                        item.state.label() +
                            if (item.state == DownloadState.FAILED || item.state == DownloadState.PAUSED) {
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
                if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.PAUSED) {
                    val total = item.totalBytes
                    if (total != null && progress != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${item.bytesWritten.sizeText()} / ${total.sizeText()}",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else if (item.bytesWritten > 0L) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.bytesWritten.sizeText(),
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
                    DownloadState.FAILED -> if (item.error == "quota") null else ZlibDownloadAction.Retry
                    DownloadState.CANCELLED -> ZlibDownloadAction.Retry
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
    "foreground_unavailable" -> appText(TextR.string.text_download_foreground_unavailable)
    "foreground_timeout" -> appText(TextR.string.text_download_foreground_timeout)
    else -> appText(TextR.string.text_please_retry)
}

private fun Long.sizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
