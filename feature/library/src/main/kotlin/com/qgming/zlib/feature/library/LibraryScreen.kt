package com.qgming.zlib.feature.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    onOpenBook: (Book) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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
                    Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                    TextButton(text = "知道了", onClick = { notice = null })
                }
            }
        }

        if (books.isEmpty()) {
            item {
                ZlibCard {
                    Text("书架还是空的", style = MiuixTheme.textStyles.body2)
                    Text(
                        "下载完成的书会出现在这里，可以直接分享给阅读器。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(books, key = { it.id }) { book ->
                ZlibCard(onClick = { onOpenBook(book) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ZlibCover(
                            url = book.coverUrl,
                            modifier = Modifier.width(56.dp).height(80.dp),
                            contentDescription = book.title,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(book.title, style = MiuixTheme.textStyles.body2)
                            if (book.authors.isNotBlank()) {
                                Text(
                                    book.authors,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            Text(
                                book.extension.uppercase(),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = "分享",
                            onClick = { shareBook(context, book) },
                            enabled = book.localUri != null,
                        )
                        TextButton(text = "删除本地", onClick = { viewModel.deleteLocal(book) })
                    }
                }
            }
        }
    }
}

private fun shareBook(context: android.content.Context, book: Book) {
    val uri = book.localUri?.let(Uri::parse) ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeOf(book.extension)
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享《${book.title}》"))
}

private fun mimeOf(extension: String): String = when (extension.lowercase()) {
    "epub" -> "application/epub+zip"
    "pdf" -> "application/pdf"
    "mobi" -> "application/x-mobipocket-ebook"
    "azw3" -> "application/vnd.amazon.ebook"
    "fb2" -> "application/x-fictionbook+xml"
    else -> "application/octet-stream"
}
