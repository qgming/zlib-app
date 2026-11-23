package com.qgming.zlib.feature.search

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibTopBarTabs
import com.qgming.zlib.core.designsystem.fullWidthItem

@Composable
fun ReadingTabs(selected: ReadingSource, onSelect: (ReadingSource) -> Unit) {
    ZlibTopBarTabs(
        tabs = ReadingSource.entries.map { it.title },
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(ReadingSource.entries[it]) },
    )
}

@Composable
fun PopularScreen(
    padding: PaddingValues,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    source: ReadingSource = ReadingSource.Popular,
    gridState: LazyGridState = rememberLazyGridState(),
    viewModel: PopularViewModel = hiltViewModel(),
) {
    val feeds by viewModel.state.collectAsStateWithLifecycle()
    val state = feeds[source] ?: ReadingFeedState(loading = true)
    val refresh = { viewModel.refresh(source) }

    key(source) {
        ZlibBookGrid(
            padding = padding,
            state = gridState,
            refreshing = state.loading && state.books.isNotEmpty(),
            onRefresh = refresh,
        ) {
            when {
                state.books.isEmpty() && state.loading -> fullWidthItem("reading-loading") { ZlibLoading() }
                state.books.isEmpty() && state.error != null -> fullWidthItem("reading-error") {
                    ZlibStateCard(
                        title = stringResource(TextR.string.text_cannot_load_books, source.title),
                        message = state.error,
                        onAction = refresh,
                    )
                }

                state.books.isEmpty() -> fullWidthItem("reading-empty") {
                    ZlibStateCard(
                        title = stringResource(TextR.string.text_no_books, source.title),
                        message = stringResource(TextR.string.text_pull_to_refresh_or_try_the_other_tab),
                        onAction = refresh,
                    )
                }

                else -> items(state.books, key = { "${source.name}-${it.ref}" }) { book ->
                    ZlibBookTile(book = book, onClick = onOpenBook)
                }
            }

            if (state.books.isNotEmpty() && state.error != null) {
                fullWidthItem("reading-refresh-error") {
                    ZlibStateCard(
                        title = stringResource(TextR.string.text_cannot_refresh_books, source.title),
                        message = state.error,
                        onAction = refresh,
                    )
                }
            }
        }
    }
}
