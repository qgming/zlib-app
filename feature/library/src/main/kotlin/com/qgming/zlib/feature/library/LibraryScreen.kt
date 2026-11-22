package com.qgming.zlib.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibTopBarTabs
import com.qgming.zlib.core.designsystem.fullWidthItem
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
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    booklistsContent: @Composable (PaddingValues) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val stateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(section.name) {
        when (section) {
            LibrarySection.Saved -> RemoteBooks(padding, CloudLibrarySource.Saved, onOpenBook, viewModel)
            LibrarySection.Booklists -> booklistsContent(padding)
        }
    }
}

@Composable
fun DownloadHistoryScreen(
    onBack: () -> Unit,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    ZlibPage(title = "云端下载记录", onBack = onBack) { padding ->
        RemoteBooks(padding, CloudLibrarySource.DownloadHistory, onOpenBook, viewModel)
    }
}

@Composable
private fun RemoteBooks(
    padding: PaddingValues,
    source: CloudLibrarySource,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    viewModel: LibraryViewModel,
) {
    LaunchedEffect(viewModel, source) { viewModel.onSourceShown(source) }
    val states by viewModel.remote.collectAsStateWithLifecycle()
    val state = states[source] ?: RemoteLibraryState()
    val page = state.page
    val onRefresh = { viewModel.refresh(source) }
    ZlibBookGrid(padding = padding, refreshing = state.request == LibraryRequest.Refresh, onRefresh = onRefresh) {
        when {
            page == null && state.error == null -> {
                if (state.request != LibraryRequest.Refresh) fullWidthItem("remote-loading") { ZlibLoading() }
            }
            page == null -> fullWidthItem("remote-error") {
                ZlibStateCard("无法加载${source.title}", state.error.orEmpty(), onAction = onRefresh)
            }

            page.books.isEmpty() -> fullWidthItem("remote-empty") {
                ZlibStateCard(title = "暂无${source.title}", message = source.emptyMessage)
            }

            else -> items(page.books, key = { it.ref }) { book ->
                LaunchedEffect(viewModel, book.ref, book.coverUrl) { viewModel.loadCover(book) }
                ZlibBookTile(book = book, onClick = onOpenBook)
            }
        }
        if (page != null && state.error != null) {
            fullWidthItem("remote-update-error") {
                ZlibStateCard("未能更新${source.title}", state.error, onAction = onRefresh)
            }
        }
        if (state.request == LibraryRequest.Append) {
            fullWidthItem("remote-more-loading") { ZlibLoading() }
        } else if (page?.hasNext == true) {
            fullWidthItem("remote-more") {
                TextButton(
                    text = "加载更多",
                    onClick = { viewModel.loadMore(source) },
                    enabled = state.request == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
