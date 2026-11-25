package com.qgming.zlib.feature.library

import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.designsystem.ZlibAutoPagination
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBooklistCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.userMessage

@Composable
fun MyBooklistsScreen(
    onBack: () -> Unit,
    onOpenList: (String) -> Unit,
    viewModel: BooklistViewModel,
) {
    DisposableEffect(viewModel) {
        viewModel.showBooklists()
        onDispose { viewModel.hideBooklists() }
    }
    val state by viewModel.booklists.collectAsStateWithLifecycle()
    val loaded = state.lists
    val message = state.error?.userMessage()
    val title = stringResource(TextR.string.text_my_booklists)
    val listState = rememberLazyListState()
    LaunchedEffect(viewModel, listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }.toSet() }
            .collect(viewModel::showPreviews)
    }
    ZlibPage(title = title, onBack = onBack) { padding ->
        ZlibCardList(
            padding = padding,
            state = listState,
            refreshing = state.request == LibraryRequest.Refresh,
            onRefresh = viewModel::refreshBooklists,
        ) {
            when {
                loaded == null && message == null && state.request != LibraryRequest.Refresh ->
                    item("booklists-loading") { ZlibLoading() }
                loaded?.isEmpty() == true && message == null -> item("booklists-empty") {
                    ZlibStateCard(
                        stringResource(TextR.string.text_no_booklists),
                        stringResource(TextR.string.text_booklists_empty_hint),
                    )
                }
                !loaded.isNullOrEmpty() -> items(loaded, key = { it.id }) { booklist ->
                    ZlibBooklistCard(booklist) {
                        viewModel.selectList(booklist.id, onOpenList)
                    }
                }
            }
            if (message != null) item("booklists-error") {
                ZlibStateCard(
                    title = if (state.failedRequest == LibraryRequest.Refresh) {
                        stringResource(TextR.string.text_cannot_refresh, title)
                    } else {
                        stringResource(TextR.string.text_cannot_load, title)
                    },
                    message = message,
                    onAction = viewModel::retryBooklists,
                )
            }
        }
    }
}

@Composable
fun BooklistBooksScreen(
    id: String,
    onBack: () -> Unit,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    viewModel: BooklistViewModel,
) {
    DisposableEffect(viewModel, id) {
        viewModel.openList(id)
        onDispose { viewModel.closeList(id) }
    }
    val state by viewModel.books.collectAsStateWithLifecycle()
    val page = state.page
    val message = state.error?.userMessage()
    val gridState = rememberLazyGridState()
    ZlibAutoPagination(
        state = gridState,
        enabled = page?.nextPage != null && state.request == null && message == null,
        pageKey = page?.nextPage ?: 0,
        onLoadMore = viewModel::loadMore,
    )
    val pageTitle = state.booklist?.title ?: stringResource(TextR.string.text_booklists)
    ZlibPage(title = pageTitle, onBack = onBack) { padding ->
        ZlibBookGrid(
            padding = padding,
            state = gridState,
            refreshing = state.request == LibraryRequest.Refresh,
            onRefresh = if (state.sessionChanged) null else viewModel::refreshBooks,
        ) {
            when {
                state.sessionChanged -> fullWidthItem("booklist-session-changed") {
                    ZlibStateCard(
                        title = stringResource(TextR.string.text_booklist_session_changed),
                        message = stringResource(TextR.string.text_booklist_return_hint),
                        actionLabel = stringResource(TextR.string.text_back),
                        onAction = onBack,
                    )
                }
                page == null && message == null && state.request != LibraryRequest.Refresh ->
                    fullWidthItem("booklist-loading") { ZlibLoading() }
                page?.books?.isEmpty() == true && message == null -> fullWidthItem("booklist-empty") {
                    ZlibStateCard(
                        stringResource(TextR.string.text_booklist_empty),
                        stringResource(TextR.string.text_booklist_empty_hint),
                    )
                }
                page != null -> items(page.books, key = { it.ref }) { book ->
                    ZlibBookTile(book = book, onClick = { request ->
                        if (viewModel.canOpenBook(book.ref)) onOpenBook(request)
                    })
                }
            }
            if (message != null) fullWidthItem("booklist-error") {
                ZlibStateCard(
                    title = when (state.failedRequest) {
                        LibraryRequest.Append -> stringResource(TextR.string.text_cannot_load_more_books)
                        LibraryRequest.Refresh -> stringResource(TextR.string.text_cannot_refresh, pageTitle)
                        else -> stringResource(TextR.string.text_cannot_load, pageTitle)
                    },
                    message = message,
                    onAction = viewModel::retryBooks,
                )
            }
            if (state.request == LibraryRequest.Append) fullWidthItem("booklist-more") { ZlibLoading() }
        }
    }
}
