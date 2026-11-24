package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior

internal val LocalZlibPageScroll = staticCompositionLocalOf<ZlibPageScroll?> { null }

internal class ZlibPageScroll(val behavior: ScrollBehavior) {
    var content by mutableStateOf<ScrollableState?>(null)
}

@Composable
private fun attachPageScroll(state: ScrollableState): ScrollBehavior? {
    val page = LocalZlibPageScroll.current ?: return null
    DisposableEffect(page, state) {
        page.content = state
        onDispose { if (page.content === state) page.content = null }
    }
    return page.behavior
}

private fun Modifier.withPageScroll(behavior: ScrollBehavior?): Modifier =
    if (behavior == null) this else nestedScroll(behavior.nestedScrollConnection)

internal class ZlibContentPadding(private val insets: PaddingValues) : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        insets.calculateLeftPadding(layoutDirection) + 16.dp

    override fun calculateTopPadding(): Dp = insets.calculateTopPadding() + 12.dp

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        insets.calculateRightPadding(layoutDirection) + 16.dp

    override fun calculateBottomPadding(): Dp = insets.calculateBottomPadding() + 16.dp
}

@Composable
fun ZlibCardList(
    padding: PaddingValues,
    state: LazyListState = rememberLazyListState(),
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = attachPageScroll(state)
    ZlibRefreshableContent(padding, refreshing, onRefresh, scrollBehavior) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            state = state,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun ZlibBookGrid(
    padding: PaddingValues,
    state: LazyGridState = rememberLazyGridState(),
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    content: LazyGridScope.() -> Unit,
) {
    val scrollBehavior = attachPageScroll(state)
    val columns = LocalBookGridColumns.current
    ZlibRefreshableContent(padding, refreshing, onRefresh, scrollBehavior) { modifier ->
        BoxWithConstraints(modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns.countForWidth(maxWidth.value.toInt())),
                modifier = Modifier.fillMaxSize(),
                state = state,
                contentPadding = padding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ZlibRefreshableContent(
    padding: PaddingValues,
    refreshing: Boolean,
    onRefresh: (() -> Unit)?,
    scrollBehavior: ScrollBehavior?,
    content: @Composable (Modifier) -> Unit,
) {
    if (onRefresh == null) {
        content(Modifier.withPageScroll(scrollBehavior))
    } else {
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            topAppBarScrollBehavior = scrollBehavior,
            refreshTexts = listOf(stringResource(TextR.string.text_pull_to_refresh), stringResource(TextR.string.text_release_to_refresh), stringResource(TextR.string.text_refreshing), stringResource(TextR.string.text_refresh_complete)),
        ) {
            content(Modifier)
        }
    }
}

fun LazyGridScope.fullWidthItem(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
