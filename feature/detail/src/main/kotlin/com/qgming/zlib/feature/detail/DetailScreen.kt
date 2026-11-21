package com.qgming.zlib.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DetailScreen(
    id: String,
    hash: String,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(key = "$id:$hash"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(id, hash) { viewModel.load(id, hash) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "图书详情",
                expandable = false,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.Back, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading && state.book == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
            }

            state.error != null && state.book == null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ZlibCard {
                    Text(state.error!!, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    TextButton(text = "重试", onClick = viewModel::retry)
                }
            }

            state.book != null -> DetailContent(
                book = state.book!!,
                message = state.message,
                onDownload = viewModel::download,
                padding = padding,
            )
        }
    }
}

@Composable
private fun DetailContent(
    book: Book,
    message: String?,
    onDownload: () -> Unit,
    padding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row {
                ZlibCover(
                    url = book.coverUrl,
                    modifier = Modifier.width(112.dp).height(160.dp),
                    contentDescription = book.title,
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(book.title, style = MiuixTheme.textStyles.title3)
                    if (book.subtitle.isNotBlank()) {
                        Text(
                            book.subtitle,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (book.authors.isNotBlank()) {
                        Text(book.authors, style = MiuixTheme.textStyles.body2)
                    }
                    book.rating?.let {
                        Text("★ ${"%.1f".format(it)}", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        listOf(book.extension.uppercase(), book.language, book.year)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        item {
            ZlibCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload) { Text("下载") }
                    TextButton(text = "分享（下载后）", onClick = {}, enabled = false)
                }
                message?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "首次使用请先在「下载」页选择 SAF 目录；收藏写入在后续里程碑接入。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

        item {
            ZlibCard {
                Text("元数据", style = MiuixTheme.textStyles.body2)
                Spacer(Modifier.height(6.dp))
                MetaRow("出版社", book.publisher)
                MetaRow("出版年份", book.year)
                MetaRow("语言", book.language)
                MetaRow("版次", book.edition)
                MetaRow("页数", book.pages?.toString().orEmpty())
                MetaRow("文件格式", book.extension.uppercase())
                MetaRow("文件大小", book.sizeBytes?.sizeText().orEmpty())
                MetaRow("ISBN-10", book.isbn10)
                MetaRow("ISBN-13", book.isbn13)
                MetaRow("分类", book.categories.joinToString(" / "))
            }
        }

        if (book.description.isNotBlank()) {
            item {
                ZlibCard {
                    Text("简介", style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        book.description,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Text(value, style = MiuixTheme.textStyles.footnote1)
    }
}

private fun Long.sizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
