package com.qgming.zlib.feature.library

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibTopBarTabs
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.ZlibAutoPagination

@Composable
fun LibraryTabs(selected: LibrarySection, onSelect: (LibrarySection) -> Unit) {
    ZlibTopBarTabs(
        tabs = LibrarySection.entries.map { it.title },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(LibrarySection.entries[it]) },
    )
}

@Composable
fun LibraryScreen(
    padding: PaddingValues,
    section: LibrarySection,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val stateHolder = rememberSaveableStateHolder()
    stateHolder.SaveableStateProvider(section.name) {
        when (section) {
            LibrarySection.Local -> LocalFilesContent(padding)
            LibrarySection.Saved -> RemoteBooks(padding, CloudLibrarySource.Saved, onOpenBook, viewModel)
        }
    }
}

@Composable
fun DownloadHistoryScreen(
    onBack: () -> Unit,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val sessionGeneration by viewModel.sessionGeneration.collectAsStateWithLifecycle()
    ZlibToastHost(viewModel.events, key = sessionGeneration)
    ZlibPage(
        title = stringResource(TextR.string.text_cloud_download_history), onBack = onBack,
    ) { padding ->
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
    val action by viewModel.historyAction.collectAsStateWithLifecycle()
    val state = states[source] ?: RemoteLibraryState()
    val page = state.page
    val gridState = rememberLazyGridState()
    ZlibAutoPagination(
        state = gridState, enabled = page?.hasNext == true && state.request == null && state.error == null,
        pageKey = page?.page ?: 0, onLoadMore = { viewModel.loadMore(source) },
    )
    val onRefresh = { viewModel.refresh(source) }
    ZlibBookGrid(padding = padding, state = gridState, refreshing = state.request == LibraryRequest.Refresh, onRefresh = onRefresh) {
        when {
            page == null && state.error == null -> {
                if (state.request != LibraryRequest.Refresh) fullWidthItem("remote-loading") { ZlibLoading() }
            }
            page == null -> fullWidthItem("remote-error") {
                ZlibStateCard(stringResource(TextR.string.text_cannot_load, source.title), state.error.orEmpty(), onAction = onRefresh)
            }

            page.books.isEmpty() -> fullWidthItem("remote-empty") {
                ZlibStateCard(title = stringResource(TextR.string.text_no, source.title), message = source.emptyMessage)
            }

            else -> items(page.books, key = { it.ref }) { book ->
                LaunchedEffect(viewModel, book.ref, book.coverUrl) { viewModel.loadCover(book) }
                ZlibBookTile(
                    book = book, onClick = onOpenBook,
                    onLongClick = if (source == CloudLibrarySource.DownloadHistory) {
                        { viewModel.requestHistoryDeletion(book) }
                    } else null,
                    onLongClickLabel = stringResource(TextR.string.text_delete_record),
                )
            }
        }
        if (page != null && state.error != null) {
            fullWidthItem("remote-update-error") {
                ZlibStateCard(stringResource(TextR.string.text_cannot_refresh, source.title), state.error, onAction = {
                    viewModel.retry(source)
                })
            }
        }
        if (state.request == LibraryRequest.Append) {
            fullWidthItem("remote-more-loading") { ZlibLoading() }
        }
    }
    if (source == CloudLibrarySource.DownloadHistory) {
        ZlibConfirmDialog(
            show = action.book != null && action.confirming,
            title = stringResource(TextR.string.text_delete_cloud_download_record),
            summary = buildString {
                append(stringResource(TextR.string.text_delete_the_cloud_download_record_for_local_files_tasks_and_favorites_are, action.book?.title.orEmpty()))
                action.error?.let { append(stringResource(TextR.string.text_n_ndelete_failed, it)) }
            },
            confirmLabel = if (action.error == null) stringResource(TextR.string.text_delete) else stringResource(TextR.string.text_retry_deletion),
            onDismiss = viewModel::dismissHistoryActions, onConfirm = viewModel::confirmHistoryDeletion,
            destructive = true, busy = action.busy,
        )
    }
}
