package com.qgming.zlib.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookCard
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibExactMatchPreference
import com.qgming.zlib.core.designsystem.ZlibFilterButton
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSearchBar
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(SearchFilters()) }
    val submitSearch: () -> Unit = {
        focusManager.clearFocus()
        viewModel.search()
    }
    val dismissFilters: () -> Unit = {
        focusManager.clearFocus()
        showFilters = false
    }
    LaunchedEffect(state.loading) {
        if (state.loading) listState.scrollToItem(0)
    }

    ZlibPage(
        title = "搜索",
        onBack = onBack,
        actions = {
            ZlibFilterButton(active = state.filters.isActive) {
                focusManager.clearFocus()
                draft = state.filters
                showFilters = true
            }
        },
        topBarContent = {
            ZlibSearchBar(value = state.query, onValueChange = viewModel::onQueryChange, onSubmit = submitSearch)
        },
    ) { padding ->
        ZlibBookGrid(padding = padding, state = listState) {
            when {
                state.loading -> fullWidthItem("search-loading") { ZlibLoading() }
                state.error != null -> fullWidthItem("search-error") {
                    ZlibStateCard(
                        title = "无法搜索",
                        message = state.error.orEmpty(),
                        onAction = if (state.searched) submitSearch else null,
                    )
                }

                state.searched && state.books.isEmpty() -> fullWidthItem("search-empty") {
                    ZlibStateCard(title = "没有找到图书", message = "试试其他关键词，或减少筛选条件。")
                }

                else -> items(state.books, key = { "result-${it.ref}" }) { book ->
                    ZlibBookCard(book = book, onClick = { onOpenBook(book) })
                }
            }

            when {
                state.loadingMore -> fullWidthItem("search-more-loading") { ZlibLoading() }
                state.appendError != null -> fullWidthItem("search-more-error") {
                    ZlibStateCard(
                        title = "无法加载更多图书",
                        message = state.appendError.orEmpty(),
                        onAction = viewModel::loadMore,
                    )
                }

                state.hasMore && state.error == null -> fullWidthItem("search-more") {
                    TextButton(text = "加载更多", onClick = viewModel::loadMore, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        ZlibGlassBottomSheet(show = showFilters, title = "搜索筛选", onDismiss = dismissFilters) {
            FilterPanel(
                filters = draft,
                onChange = { draft = it },
                onApply = {
                    dismissFilters()
                    viewModel.applyFilters(draft)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    filters: SearchFilters,
    onChange: (SearchFilters) -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZlibExactMatchPreference(checked = filters.exact, onCheckedChange = { onChange(filters.copy(exact = it)) })
        Text("语言", style = MiuixTheme.textStyles.body2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip("English", "english" in filters.languages) { onChange(filters.toggleLanguage("english")) }
            FilterChip("中文", "chinese" in filters.languages) { onChange(filters.toggleLanguage("chinese")) }
            FilterChip("Deutsch", "german" in filters.languages) { onChange(filters.toggleLanguage("german")) }
        }
        Text("格式", style = MiuixTheme.textStyles.body2)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("epub", "pdf", "mobi", "azw3", "fb2").forEach { extension ->
                FilterChip(extension.uppercase(), extension in filters.extensions) {
                    onChange(filters.toggleExtension(extension))
                }
            }
        }
        Text("出版年份", style = MiuixTheme.textStyles.body2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = filters.yearFrom,
                onValueChange = { onChange(filters.withYearFrom(it)) },
                label = "起始年份",
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
            TextField(
                value = filters.yearTo,
                onValueChange = { onChange(filters.withYearTo(it)) },
                label = "结束年份",
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
        }
        if (!filters.yearRangeValid) {
            Text("起始年份不能大于结束年份", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
        Text("排序", style = MiuixTheme.textStyles.body2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip("默认", filters.order == null) { onChange(filters.copy(order = null)) }
            FilterChip("热门", filters.order == "popular") { onChange(filters.copy(order = "popular")) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(text = "重置", onClick = { onChange(SearchFilters()) }, modifier = Modifier.weight(1f))
            Button(
                onClick = onApply,
                enabled = filters.yearRangeValid,
                modifier = Modifier.weight(2f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text("应用筛选") }
        }
    }
}

@Composable
private fun FilterChip(text: String, isSelected: Boolean, onClick: () -> Unit) {
    val background = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh
    val foreground = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = Modifier.semantics { selected = isSelected },
        shape = RoundedCornerShape(50),
        color = background,
        contentColor = foreground,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = foreground,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
