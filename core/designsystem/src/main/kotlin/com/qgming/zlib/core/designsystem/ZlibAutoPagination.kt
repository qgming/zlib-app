package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** An error disables this effect until the user explicitly retries. */
@Composable
fun ZlibAutoPagination(state: LazyGridState, enabled: Boolean, pageKey: Any, onLoadMore: () -> Unit) {
    val loadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(state, enabled, pageKey) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow {
            val info = state.layoutInfo
            info.visibleItemsInfo.isNotEmpty() &&
                info.visibleItemsInfo.last().index >= info.totalItemsCount - 7
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) loadMore() }
    }
}
