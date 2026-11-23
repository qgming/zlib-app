package com.qgming.zlib.feature.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibTopBarTabs
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.ZlibAutoPagination
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
        title = "云端下载记录", onBack = onBack,
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
                ZlibStateCard("无法加载${source.title}", state.error.orEmpty(), onAction = onRefresh)
            }

            page.books.isEmpty() -> fullWidthItem("remote-empty") {
                ZlibStateCard(title = "暂无${source.title}", message = source.emptyMessage)
            }

            else -> items(page.books, key = { it.ref }) { book ->
                LaunchedEffect(viewModel, book.ref, book.coverUrl) { viewModel.loadCover(book) }
                ZlibBookTile(
                    book = book, onClick = onOpenBook,
                    onLongClick = if (source == CloudLibrarySource.DownloadHistory) {
                        { viewModel.showHistoryActions(book) }
                    } else null,
                    onLongClickLabel = "更多操作",
                )
            }
        }
        if (page != null && state.error != null) {
            fullWidthItem("remote-update-error") {
                ZlibStateCard("未能更新${source.title}", state.error, onAction = {
                    viewModel.retry(source)
                })
            }
        }
        if (state.request == LibraryRequest.Append) {
            fullWidthItem("remote-more-loading") { ZlibLoading() }
        }
    }
    if (source == CloudLibrarySource.DownloadHistory) {
        ZlibGlassBottomSheet(
            show = action.book != null && !action.confirming,
            title = action.book?.title.orEmpty(), onDismiss = viewModel::dismissHistoryActions,
        ) {
            ZlibMenuAction(text = "删除记录", onClick = viewModel::requestHistoryDeletion)
        }
        ZlibConfirmDialog(
            show = action.book != null && action.confirming,
            title = "删除云端下载记录？",
            summary = buildString {
                append("删除《${action.book?.title.orEmpty()}》的云端下载记录，不影响本机文件、下载任务或收藏。")
                action.error?.let { append("\n\n删除失败：$it") }
            },
            confirmLabel = if (action.error == null) "删除" else "重试删除",
            onDismiss = viewModel::dismissHistoryActions, onConfirm = viewModel::confirmHistoryDeletion,
            destructive = true, busy = action.busy,
        )
    }
}
