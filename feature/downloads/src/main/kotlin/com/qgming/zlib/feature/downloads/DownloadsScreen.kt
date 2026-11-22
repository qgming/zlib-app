package com.qgming.zlib.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibPreferenceGroup
import com.qgming.zlib.core.designsystem.ZlibPreferenceIcon
import com.qgming.zlib.core.designsystem.ZlibPreferenceLink
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.DownloadState
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val loadedQueue = queue
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { notice = it }
    }
    val (records, tasks) = loadedQueue.orEmpty().partition { it.state == DownloadState.COMPLETED }

    ZlibPage(title = "下载管理", onBack = onBack) { padding ->
        ZlibCardList(padding) {
            item(key = "download-library-links") {
                ZlibPreferenceGroup("图书与文件") {
                    ZlibPreferenceLink("本机文件", "分享或删除已保存到此设备的文件", ZlibPreferenceIcon.Downloads, onOpenFiles)
                    ZlibPreferenceLink("云端下载记录", "查看 Z-Library 账号在各设备上的下载记录", ZlibPreferenceIcon.Account, onOpenHistory)
                }
            }
            notice?.let { message ->
                item(key = "download-notice") {
                    ZlibStateCard("下载提示", message, actionLabel = "知道了", onAction = { notice = null })
                }
            }
            if (loadedQueue == null) {
                item(key = "download-loading") { ZlibLoading() }
            } else if (loadedQueue.isEmpty()) {
                item(key = "download-empty") {
                    ZlibStateCard(title = "暂无下载任务", message = "在图书详情页点击「下载」，任务会出现在这里。")
                }
            }
            if (tasks.isNotEmpty()) {
                item(key = "download-tasks") { ZlibSectionTitle("下载任务") }
                items(tasks, key = { it.id }) { item -> DownloadRow(item, viewModel) }
            }
            if (records.isNotEmpty()) {
                item(key = "download-records") { ZlibSectionTitle("已完成的本机任务") }
                items(records, key = { it.id }) { item -> DownloadRow(item, viewModel) }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    viewModel: DownloadsViewModel,
) {
    val progress = item.progress
    val waiting = item.state == DownloadState.RESOLVING || (item.state == DownloadState.DOWNLOADING && progress == null)
    ZlibCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZlibCover(
                url = item.coverUrl,
                title = item.title.ifBlank { item.fileName },
                authors = item.authors,
                modifier = Modifier.width(56.dp).height(84.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title.ifBlank { item.fileName }, style = MiuixTheme.textStyles.body2)
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
            }
        }

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

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (item.state) {
                DownloadState.QUEUED, DownloadState.RESOLVING, DownloadState.DOWNLOADING ->
                    TextButton(text = "暂停", onClick = { viewModel.pause(item.id) })

                DownloadState.PAUSED -> TextButton(text = "继续", onClick = { viewModel.resume(item.id) })
                DownloadState.FAILED -> TextButton(text = "重试", onClick = { viewModel.retry(item.id) })
                DownloadState.CANCELLED -> TextButton(text = "重新下载", onClick = { viewModel.retry(item.id) })
                DownloadState.COMPLETED -> TextButton(text = "移除任务记录", onClick = { viewModel.remove(item.id) })
            }
            if (item.state in CancellableStates) {
                TextButton(text = "取消", onClick = { viewModel.cancel(item.id) })
            }
        }
    }
}

private val CancellableStates = setOf(
    DownloadState.QUEUED, DownloadState.RESOLVING, DownloadState.DOWNLOADING, DownloadState.PAUSED,
)

private fun DownloadState.label(): String = when (this) {
    DownloadState.QUEUED -> "排队中"
    DownloadState.RESOLVING -> "解析地址"
    DownloadState.DOWNLOADING -> "下载中"
    DownloadState.COMPLETED -> "已完成"
    DownloadState.FAILED -> "失败"
    DownloadState.PAUSED -> "已暂停"
    DownloadState.CANCELLED -> "已取消"
}

private fun String.errorLabel(): String = when (this) {
    "quota" -> "今日额度已用完"
    "session" -> "登录已失效"
    "domain" -> "域名被拦截"
    "network" -> "网络错误"
    "storage" -> "写入失败"
    "http_404" -> "文件不存在"
    else -> "请重试"
}

private fun Long.sizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
