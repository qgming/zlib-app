package com.qgming.zlib.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
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

    ZlibPage(title = "图书详情", onBack = onBack) { padding ->
        val book = state.book
        val error = state.error
        ZlibCardList(padding) {
            if (book == null) {
                item {
                    if (error != null) {
                        ZlibStateCard(title = "无法加载图书详情", message = error, onAction = viewModel::retry)
                    } else {
                        ZlibLoading()
                    }
                }
            } else {
                item { BookHeader(book) }
                item { DownloadCard(message = state.message, onDownload = viewModel::download) }
                item { MetadataCard(book) }
                if (book.description.isNotBlank()) {
                    item { DescriptionCard(book.description) }
                }
            }
        }
    }
}

@Composable
private fun BookHeader(book: Book) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ZlibCover(
            url = book.coverUrl,
            modifier = Modifier.size(width = 160.dp, height = 240.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            text = book.title,
            modifier = Modifier.padding(top = 10.dp).widthIn(max = 520.dp),
            style = MiuixTheme.textStyles.title3,
            textAlign = TextAlign.Center,
        )
        if (book.subtitle.isNotBlank()) {
            Text(
                text = book.subtitle,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
        if (book.authors.isNotBlank()) {
            Text(book.authors, style = MiuixTheme.textStyles.body2, textAlign = TextAlign.Center)
        }
        val rating = book.rating?.let { "★ ${"%.1f".format(it)}" }.orEmpty()
        val summary = listOf(book.extension.uppercase(), book.language, book.year, rating)
            .filter(String::isNotBlank).joinToString(" · ")
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DownloadCard(message: String?, onDownload: () -> Unit) {
    ZlibCard {
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Text("下载图书")
        }
        Text(
            text = message ?: "首次下载前，请在「设置 → 下载目录」选择保存文件夹。",
            modifier = Modifier.padding(top = 8.dp),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MetadataCard(book: Book) {
    ZlibCard {
        Text("出版与文件信息", style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(8.dp))
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

@Composable
private fun DescriptionCard(description: String) {
    ZlibCard {
        Text("简介", style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(8.dp))
        Text(description, style = MiuixTheme.textStyles.body2)
    }
}

@Composable
private fun MetaRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(value, modifier = Modifier.weight(2f), style = MiuixTheme.textStyles.footnote1, textAlign = TextAlign.End)
    }
}

private fun Long.sizeText(): String = when {
    this >= 1024L * 1024L -> "%.1f MB".format(this / 1024.0 / 1024.0)
    this >= 1024L -> "%.0f KB".format(this / 1024.0)
    else -> "$this B"
}
