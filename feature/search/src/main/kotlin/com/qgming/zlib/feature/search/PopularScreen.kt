package com.qgming.zlib.feature.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.fullWidthItem

@Composable
fun PopularScreen(
    padding: PaddingValues,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
    viewModel: PopularViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ZlibBookGrid(padding = padding, state = gridState) {
        when {
            state.loading -> fullWidthItem("popular-loading") { ZlibLoading() }
            state.error != null -> fullWidthItem("popular-error") {
                ZlibStateCard(
                    title = "无法加载热门图书",
                    message = state.error.orEmpty(),
                    onAction = viewModel::loadPopular,
                )
            }

            state.popular.isEmpty() -> fullWidthItem("popular-empty") {
                ZlibStateCard(
                    title = "暂无热门图书",
                    message = "可以重试，或点右上角搜索想看的书。",
                    onAction = viewModel::loadPopular,
                )
            }

            else -> items(state.popular, key = { "popular-${it.ref}" }) { book ->
                ZlibBookTile(book = book, onClick = onOpenBook)
            }
        }

        if (state.recommended.isNotEmpty()) {
            fullWidthItem("recommendations") { ZlibSectionTitle("为你推荐") }
            items(state.recommended, key = { "recommendation-${it.ref}" }) { book ->
                ZlibBookTile(book = book, onClick = onOpenBook)
            }
        }
    }
}
