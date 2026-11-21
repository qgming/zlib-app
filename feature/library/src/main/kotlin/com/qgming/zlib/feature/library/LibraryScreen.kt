package com.qgming.zlib.feature.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookCard
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibTopBarTabs
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.TextButton

private val SectionTitles = LibrarySection.entries.map { it.title }

@Composable
fun LibraryTabs(selected: LibrarySection, onSelect: (LibrarySection) -> Unit) {
    ZlibTopBarTabs(
        tabs = SectionTitles,
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(LibrarySection.entries[it]) },
    )
}

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    section: LibrarySection,
    onOpenBook: (Book) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val stateHolder = rememberSaveableStateHolder()
    LaunchedEffect(viewModel, section) { viewModel.onSectionShown(section) }

    stateHolder.SaveableStateProvider(section.name) {
        if (section == LibrarySection.Downloaded) {
            LocalBooks(padding, onOpenBook, viewModel)
        } else {
            val states by viewModel.remote.collectAsStateWithLifecycle()
            RemoteBooks(
                padding = padding,
                section = section,
                state = states[section] ?: RemoteLibraryState(),
                onRefresh = { viewModel.refresh(section) },
                onLoadMore = { viewModel.loadMore(section) },
                onOpenBook = onOpenBook,
            )
        }
    }
}

@Composable
private fun LocalBooks(padding: PaddingValues, onOpenBook: (Book) -> Unit, viewModel: LibraryViewModel) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val loadedBooks = books
    var notice by remember { mutableStateOf<String?>(null) }
    var bookToDelete by remember { mutableStateOf<Book?>(null) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { notice = it }
    }

    ZlibBookGrid(padding = padding) {
        notice?.let { message ->
            fullWidthItem("library-notice") {
                ZlibStateCard(
                    title = "书架提示",
                    message = message,
                    actionLabel = "知道了",
                    onAction = { notice = null },
                )
            }
        }

        when {
            loadedBooks == null -> fullWidthItem("library-loading") { ZlibLoading() }
            loadedBooks.isEmpty() -> fullWidthItem("library-empty") {
                ZlibStateCard(title = "暂无已下载图书", message = "下载到此设备的图书会出现在这里。")
            }

            else -> items(loadedBooks, key = { it.ref }) { book ->
                ZlibBookCard(book = book, onClick = { onOpenBook(book) }) {
                    TextButton(
                        text = "分享",
                        onClick = { shareBook(context, book) },
                        enabled = book.localUri != null,
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    )
                    TextButton(
                        text = "删除",
                        onClick = { bookToDelete = book },
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    ZlibConfirmDialog(
        show = bookToDelete != null,
        title = "删除本地文件？",
        summary = "仅删除此设备上的文件，不影响收藏或下载历史。",
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

@Composable
private fun RemoteBooks(
    padding: PaddingValues,
    section: LibrarySection,
    state: RemoteLibraryState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val page = state.page
    ZlibBookGrid(padding = padding, refreshing = state.request == LibraryRequest.Refresh, onRefresh = onRefresh) {
        when {
            page == null && state.error == null -> {
                if (state.request != LibraryRequest.Refresh) fullWidthItem("remote-loading") { ZlibLoading() }
            }
            page == null -> fullWidthItem("remote-error") {
                ZlibStateCard("无法加载${section.title}", state.error.orEmpty(), onAction = onRefresh)
            }

            page.books.isEmpty() -> fullWidthItem("remote-empty") {
                ZlibStateCard(
                    title = if (section == LibrarySection.Saved) "暂无收藏" else "暂无下载历史",
                    message = if (section == LibrarySection.Saved) "这里显示 Z-Library 账号中的收藏。" else "这里显示 Z-Library 账号的下载记录。",
                )
            }

            else -> items(page.books, key = { it.ref }) { book ->
                ZlibBookCard(book = book, onClick = { onOpenBook(book) }, supportingText = book.historyDate)
            }
        }
        if (page != null && state.error != null) {
            fullWidthItem("remote-update-error") { ZlibStateCard("未能更新${section.title}", state.error) }
        }
        if (state.request == LibraryRequest.Append) {
            fullWidthItem("remote-more-loading") { ZlibLoading() }
        } else if (page?.hasNext == true) {
            fullWidthItem("remote-more") {
                TextButton(
                    text = "加载更多",
                    onClick = onLoadMore,
                    enabled = state.request == null,
                    modifier = Modifier.fillMaxWidth(),
                )
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
