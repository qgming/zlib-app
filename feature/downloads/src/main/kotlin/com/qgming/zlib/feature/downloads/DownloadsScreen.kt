package com.qgming.zlib.feature.downloads

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.model.DownloadItem
import com.qgming.zlib.core.model.DownloadState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DownloadsScreen(
    padding: PaddingValues,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDirectory)
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { notice = it }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        notice?.let { message ->
            item {
                ZlibCard {
                    Text(
                        message,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                    TextButton(text = "知道了", onClick = { notice = null })
                }
            }
        }

        item {
            ZlibCard {
                Text("下载目录", style = MiuixTheme.textStyles.body2)
                Text(
                    "选择一个 SAF 目录，Zlib 会在这里创建 .part 并原子重命名。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { picker.launch(null) }) {
                    Text("选择下载目录")
                }
            }
        }

        if (queue.isEmpty()) {
            item {
                ZlibCard {
                    Text("下载队列为空", style = MiuixTheme.textStyles.body2)
                    Text(
                        "在图书详情页点击“下载”，任务会出现在这里。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(queue, key = { it.id }) { item ->
                DownloadRow(
                    item = item,
                    onPause = { viewModel.pause(item.id) },
                    onResume = { viewModel.resume(item.id) },
                    onRetry = { viewModel.retry(item.id) },
                    onCancel = { viewModel.cancel(item.id) },
                    onRemove = { viewModel.remove(item.id) },
                )
            }
        }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    ZlibCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZlibCover(
                url = item.coverUrl,
                modifier = Modifier.width(48.dp).height(68.dp),
                contentDescription = item.title,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title.ifBlank { item.fileName }, style = MiuixTheme.textStyles.body2)
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

        item.totalBytes?.let { total ->
            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.QUEUED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = item.progress ?: 0f, modifier = Modifier.fillMaxWidth())
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
                    TextButton(text = "暂停", onClick = onPause)

                DownloadState.PAUSED -> TextButton(text = "继续", onClick = onResume)
                DownloadState.FAILED -> TextButton(text = "重试", onClick = onRetry)
                DownloadState.CANCELLED -> TextButton(text = "重新下载", onClick = onRetry)
                DownloadState.COMPLETED -> TextButton(text = "移除记录", onClick = onRemove)
            }
            if (item.state != DownloadState.COMPLETED) {
                TextButton(text = "取消", onClick = onCancel)
            }
        }
    }
}

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
