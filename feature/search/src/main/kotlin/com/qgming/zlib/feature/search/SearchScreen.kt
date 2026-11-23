package com.qgming.zlib.feature.search

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibBookTile
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibBookGrid
import com.qgming.zlib.core.designsystem.ZlibExactMatchPreference
import com.qgming.zlib.core.designsystem.ZlibFilterButton
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSearchBar
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.ZlibAutoPagination
import com.qgming.zlib.core.designsystem.ZlibMultiSelectPreference
import com.qgming.zlib.core.designsystem.ZlibSelectionOption
import com.qgming.zlib.core.designsystem.ZlibPreferenceMenu
import com.qgming.zlib.core.designsystem.ZlibPreferenceGroup
import com.qgming.zlib.core.designsystem.ZlibClearSearchHistoryButton
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.model.SearchFilterOptions
import com.qgming.zlib.core.model.SearchSort
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    initialQuery: String? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val listState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    var draft by remember { mutableStateOf(SearchFilters()) }
    ZlibAutoPagination(
        state = listState,
        enabled = state.hasMore && !state.loading && !state.loadingMore && state.error == null && state.appendError == null,
        pageKey = state.page, onLoadMore = viewModel::loadMore,
    )
    val submitSearch: () -> Unit = {
        focusManager.clearFocus()
        viewModel.search()
    }
    val dismissFilters: () -> Unit = {
        focusManager.clearFocus()
        showFilters = false
    }
    LaunchedEffect(initialQuery) { initialQuery?.let(viewModel::initializeQuery) }
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
                viewModel.loadFilterOptions()
                showFilters = true
            }
        },
        topBarContent = {
            ZlibSearchBar(value = state.query, onValueChange = viewModel::onQueryChange, onSubmit = submitSearch)
        },
    ) { padding ->
        ZlibBookGrid(padding = padding, state = listState) {
            when {
                !state.searched && !state.loading -> fullWidthItem("search-recents") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (history.queries.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                Text("最近搜索", style = MiuixTheme.textStyles.title4)
                                ZlibClearSearchHistoryButton(onClick = { confirmClearHistory = true })
                            }
                            history.queries.forEach { query ->
                                Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).combinedClickable(
                                    role = Role.Button,
                                    onClick = { focusManager.clearFocus(); viewModel.searchRecent(query) },
                                    onLongClickLabel = "删除搜索记录",
                                    onLongClick = { viewModel.removeRecent(query) },
                                ).padding(vertical = 12.dp)) {
                                    Text(query, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
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
                    ZlibBookTile(book = book, onClick = onOpenBook)
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

                state.searched && !state.hasMore && state.books.isNotEmpty() && !state.loading -> fullWidthItem("search-end") {
                    Text("已显示全部结果", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        ZlibGlassBottomSheet(show = showFilters, title = "搜索筛选", onDismiss = dismissFilters) {
            FilterPanel(
                filters = draft,
                options = state.filterOptions,
                loadingOptions = state.loadingOptions,
                optionsError = state.optionsError,
                onRetryOptions = viewModel::loadFilterOptions,
                onChange = { draft = it },
                onApply = {
                    dismissFilters()
                    viewModel.applyFilters(draft)
                },
            )
        }
        ZlibConfirmDialog(
            show = confirmClearHistory,
            title = "清空最近搜索？",
            summary = "清空后无法恢复。",
            confirmLabel = "清空",
            destructive = true,
            onDismiss = { confirmClearHistory = false },
            onConfirm = {
                confirmClearHistory = false
                viewModel.clearRecent()
            },
        )
    }
}
@Composable
private fun FilterPanel(
    filters: SearchFilters,
    options: SearchFilterOptions,
    loadingOptions: Boolean,
    optionsError: String?,
    onRetryOptions: () -> Unit,
    onChange: (SearchFilters) -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ZlibExactMatchPreference(checked = filters.exact, onCheckedChange = { onChange(filters.copy(exact = it)) })
        if (loadingOptions) Text("正在更新筛选选项…", style = MiuixTheme.textStyles.footnote1)
        optionsError?.let { TextButton(text = "更新筛选选项失败，点击重试", onClick = onRetryOptions) }
        val languages = remember(options.languages) {
            options.languages.sortedWith(compareBy<com.qgming.zlib.core.model.SearchLanguage> {
                when (it.code) { "chinese" -> 0; "english" -> 1; else -> 2 }
            }.thenBy { it.name }).map { ZlibSelectionOption(it.code, it.name, "${it.name} ${it.code}") }
        }
        ZlibPreferenceGroup {
            ZlibMultiSelectPreference(
                title = "语言", allLabel = "全部语言", options = languages,
                selected = filters.languages, searchable = true,
                onChange = { onChange(filters.copy(languages = it)) },
            )
            ZlibMultiSelectPreference(
                title = "格式", allLabel = "全部格式",
                options = options.extensions.map { ZlibSelectionOption(it, it.uppercase()) },
                selected = filters.extensions, onChange = { onChange(filters.copy(extensions = it)) },
            )
            ZlibPreferenceMenu(
                title = "排序", options = SearchSort.entries.map { it.title },
                selectedIndex = SearchSort.entries.indexOfFirst { it.wireValue == filters.order }.coerceAtLeast(0),
                onSelect = { onChange(filters.copy(order = SearchSort.entries[it].wireValue)) },
            )
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
