package com.qgming.zlib.feature.library

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibLocalFileTile
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.EbookFiles

@Composable
internal fun LocalFilesContent(padding: PaddingValues, viewModel: LocalFilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    // A directory change cancels a toast that belongs to the previous tree.
    val toast = rememberZlibToastHostState(state.treeUri)
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(viewModel, toast) { viewModel.events.collect { toast.showMessage(it) } }
    LaunchedEffect(viewModel, context, toast) {
        viewModel.fileIntents.collect { event ->
            try {
                launchFileIntent(context, event)
            } catch (_: ActivityNotFoundException) {
                toast.showMessage(if (event.share) appText(TextR.string.text_no_app_can_receive_this_file) else appText(TextR.string.text_no_app_can_open_this_format_please_install_a_reader))
            } catch (_: SecurityException) {
                toast.showMessage(appText(TextR.string.text_cannot_access_the_file_grant_folder_access_again_in_settings))
                viewModel.refresh()
            }
        }
    }
    LaunchedEffect(state.treeUri, state.files) {
        val selected = action.file
        if (selected != null && (selected.treeUri != state.treeUri || (!state.loading && state.files.none { it.uri == selected.uri }))) {
            viewModel.dismissActions()
        }
    }
    ZlibToastHost(toast)

    ZlibBookGrid(padding = padding, refreshing = state.refreshing, onRefresh = { viewModel.refresh(userInitiated = true) }) {
        when {
            state.loading -> fullWidthItem("local-loading") { ZlibLoading() }
            state.treeUri == null -> fullWidthItem("local-no-directory") {
                ZlibStateCard(stringResource(TextR.string.text_no_download_folder_selected), stringResource(TextR.string.text_choose_a_download_folder_in_settings_to_see_its_e_books_here))
            }
            state.error != null -> fullWidthItem("local-error") {
                ZlibStateCard(stringResource(TextR.string.text_cannot_read_download_folder), state.error!!.userMessage(), onAction = viewModel::refresh)
            }
            state.files.isEmpty() -> fullWidthItem("local-empty") {
                ZlibStateCard(stringResource(TextR.string.text_no_local_files), stringResource(TextR.string.text_the_download_folder_contains_no_supported_e_books))
            }
            else -> items(state.files, key = { it.uri }) { file ->
                ZlibLocalFileTile(file, onClick = { viewModel.open(file) }, onLongClick = { viewModel.showActions(file) })
            }
        }
    }
    ZlibGlassBottomSheet(
        show = action.file != null && !action.confirming, title = action.file?.name.orEmpty(),
        onDismiss = viewModel::dismissActions,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ZlibMenuAction(stringResource(TextR.string.text_share_file)) { action.file?.let { viewModel.open(it, share = true) } }
            ZlibMenuAction(stringResource(TextR.string.text_delete_local_file), onClick = viewModel::confirmDeletion)
        }
    }
    ZlibConfirmDialog(
        show = action.file != null && action.confirming,
        title = stringResource(TextR.string.text_delete_local_file_2),
        summary = buildString {
            append(stringResource(TextR.string.text_delete_from_the_download_folder_cloud_favorites_and_history_are_kept, action.file?.name.orEmpty()))
            action.error?.let { append(stringResource(TextR.string.text_n_ndelete_failed, it)) }
        },
        confirmLabel = if (action.error == null) stringResource(TextR.string.text_delete) else stringResource(TextR.string.text_retry_deletion),
        onDismiss = viewModel::dismissActions, onConfirm = viewModel::delete,
        destructive = true, busy = action.busy,
    )
}

@Suppress("UseKtx") // Keep URI conversion on the Android API without a core-ktx dependency.
private fun launchFileIntent(context: Context, event: LocalFileIntent) {
    val file = event.file
    val uri = Uri.parse(file.uri)
    val intent = Intent(if (event.share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (event.share) {
            type = EbookFiles.mimeType(file.extension)
            putExtra(Intent.EXTRA_STREAM, uri)
        } else setDataAndType(uri, EbookFiles.mimeType(file.extension))
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(if (event.share) Intent.createChooser(intent, appText(TextR.string.text_share, file.title)) else intent)
}
