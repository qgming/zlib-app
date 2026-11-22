package com.qgming.zlib.feature.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun LocalFilesScreen(
    onBack: () -> Unit,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val books by viewModel.files.collectAsStateWithLifecycle()
    val loadedBooks = books
    var notice by remember { mutableStateOf<String?>(null) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    var bookForActions by remember { mutableStateOf<Book?>(null) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) { viewModel.events.collect { notice = it } }
    LaunchedEffect(viewModel, context) {
        viewModel.shareEvents.collect { book ->
            try {
                shareBook(context, book)
            } catch (_: android.content.ActivityNotFoundException) {
                notice = "没有可接收此文件的应用"
            } catch (_: SecurityException) {
                notice = "无法分享此文件，请重新授权下载目录"
            }
        }
    }

    ZlibPage(title = "本机文件", onBack = onBack) { padding ->
        ZlibBookGrid(padding = padding) {
            notice?.let { message ->
                fullWidthItem("local-files-notice") {
                    ZlibStateCard("文件提示", message, actionLabel = "知道了", onAction = { notice = null })
                }
            }
            when {
                loadedBooks == null -> fullWidthItem("local-files-loading") { ZlibLoading() }
                loadedBooks.isEmpty() -> fullWidthItem("local-files-empty") {
                    ZlibStateCard(title = "暂无本机文件", message = "通过本应用下载到此设备的图书会出现在这里。")
                }
                else -> items(loadedBooks, key = { it.ref }) { book ->
                    ZlibBookTile(
                        book = book,
                        onClick = onOpenBook,
                        onLongClick = { bookForActions = book },
                    )
                }
            }
        }
    }

    ZlibGlassBottomSheet(
        show = bookForActions != null,
        title = bookForActions?.title.orEmpty(),
        onDismiss = { bookForActions = null },
    ) {
        TextButton(
            text = "分享文件",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val book = bookForActions
                bookForActions = null
                book?.let(viewModel::shareLocal)
            },
        )
        TextButton(
            text = "删除本机文件",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                bookToDelete = bookForActions
                bookForActions = null
            },
        )
    }

    ZlibConfirmDialog(
        show = bookToDelete != null,
        title = "删除本机文件？",
        summary = "仅删除此设备上的文件，不影响云端收藏或下载记录。",
        confirmLabel = "删除",
        onDismiss = { bookToDelete = null },
        onConfirm = {
            val book = bookToDelete
            bookToDelete = null
            book?.let(viewModel::deleteLocal)
        },
        destructive = true,
    )
}

private fun shareBook(context: Context, book: Book) {
    val uri = book.localUri?.let(Uri::parse) ?: return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = when (book.extension.lowercase()) {
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            "mobi" -> "application/x-mobipocket-ebook"
            "azw3" -> "application/vnd.amazon.ebook"
            "fb2" -> "application/x-fictionbook+xml"
            else -> "application/octet-stream"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享《${book.title}》"))
}
