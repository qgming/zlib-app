package com.qgming.zlib.feature.library

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import com.qgming.zlib.core.designsystem.ZlibSnackbarHost
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.rememberZlibSnackbarHostState
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.EbookFiles

@Composable
internal fun LocalFilesContent(padding: PaddingValues, viewModel: LocalFilesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val snackbar = rememberZlibSnackbarHostState(state.treeUri)
    val context = LocalContext.current
    LaunchedEffect(viewModel) { viewModel.refresh() }
    LaunchedEffect(viewModel, snackbar) { viewModel.events.collect { snackbar.showMessage(it) } }
    LaunchedEffect(viewModel, context, snackbar) {
        viewModel.fileIntents.collect { event ->
            try {
                launchFileIntent(context, event)
            } catch (_: ActivityNotFoundException) {
                snackbar.showMessage(if (event.share) "没有可接收此文件的应用" else "没有可打开此格式的应用，请安装阅读器")
            } catch (_: SecurityException) {
                snackbar.showMessage("无法访问文件，请在设置中重新授权下载目录")
                viewModel.refresh()
            }
        }
    }
    LaunchedEffect(state.treeUri) { viewModel.dismissActions() }

    Box(Modifier.fillMaxSize()) {
        ZlibBookGrid(padding = padding, refreshing = false, onRefresh = viewModel::refresh) {
            when {
                state.loading -> fullWidthItem("local-loading") { ZlibLoading() }
                state.treeUri == null -> fullWidthItem("local-no-directory") {
                    ZlibStateCard("尚未选择下载目录", "在设置中选择下载目录后，这里会显示其中的电子书文件。")
                }
                state.error != null -> fullWidthItem("local-error") {
                    ZlibStateCard("无法读取下载目录", state.error!!.userMessage(), onAction = viewModel::refresh)
                }
                state.files.isEmpty() -> fullWidthItem("local-empty") {
                    ZlibStateCard("暂无本机文件", "下载目录中没有支持的电子书文件。")
                }
                else -> items(state.files, key = { it.uri }) { file ->
                    ZlibLocalFileTile(file, onClick = { viewModel.open(file) }, onLongClick = { viewModel.showActions(file) })
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding())) {
            ZlibSnackbarHost(snackbar)
        }
    }
    ZlibGlassBottomSheet(
        show = action.file != null && !action.confirming, title = action.file?.name.orEmpty(),
        onDismiss = viewModel::dismissActions,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ZlibMenuAction("分享文件") { action.file?.let { viewModel.open(it, share = true) } }
            ZlibMenuAction("删除本机文件", onClick = viewModel::confirmDeletion)
        }
    }
    ZlibConfirmDialog(
        show = action.file != null && action.confirming,
        title = "删除本机文件？",
        summary = buildString {
            append("将从下载目录中删除「${action.file?.name.orEmpty()}」，不影响云端收藏或下载记录。")
            action.error?.let { append("\n\n删除失败：$it") }
        },
        confirmLabel = if (action.error == null) "删除" else "重试删除",
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
    context.startActivity(if (event.share) Intent.createChooser(intent, "分享「${file.title}」") else intent)
}
