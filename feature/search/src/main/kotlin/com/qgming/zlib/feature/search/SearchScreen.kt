package com.qgming.zlib.feature.search

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
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
import com.qgming.zlib.core.designsystem.ZlibRecentSearches
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.fullWidthItem
import com.qgming.zlib.core.designsystem.ZlibAutoPagination
import com.qgming.zlib.core.designsystem.ZlibMultiSelectPreference
import com.qgming.zlib.core.designsystem.ZlibSelectionOption
import com.qgming.zlib.core.designsystem.ZlibPreferenceMenu
import com.qgming.zlib.core.designsystem.ZlibPreferenceGroup
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
    ZlibToastHost(viewModel.messages)
    val listState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    var showFilters by remember { mutableStateOf(false) }
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
        title = stringResource(TextR.string.text_search),
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
                    ZlibRecentSearches(
                        queries = history.queries,
                        onSearch = { query -> focusManager.clearFocus(); viewModel.searchRecent(query) },
                        onRemove = viewModel::removeRecent,
                        onClear = viewModel::clearRecent,
                    )
                }
                state.loading -> fullWidthItem("search-loading") { ZlibLoading() }
                state.error != null -> fullWidthItem("search-error") {
                    ZlibStateCard(
                        title = stringResource(TextR.string.text_cannot_search),
                        message = state.error.orEmpty(),
                        onAction = if (state.searched) submitSearch else null,
                    )
                }

                state.searched && state.books.isEmpty() -> fullWidthItem("search-empty") {
                    ZlibStateCard(title = stringResource(TextR.string.text_no_books_found), message = stringResource(TextR.string.text_try_other_keywords_or_fewer_filters))
                }

                else -> items(state.books, key = { "result-${it.ref}" }) { book ->
                    ZlibBookTile(book = book, onClick = onOpenBook)
                }
            }

            when {
                state.loadingMore -> fullWidthItem("search-more-loading") { ZlibLoading() }
                state.appendError != null -> fullWidthItem("search-more-error") {
                    ZlibStateCard(
                        title = stringResource(TextR.string.text_cannot_load_more_books),
                        message = state.appendError.orEmpty(),
                        onAction = viewModel::loadMore,
                    )
                }

                state.searched && !state.hasMore && state.books.isNotEmpty() && !state.loading -> fullWidthItem("search-end") {
                    Text(stringResource(TextR.string.text_all_results_shown), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        ZlibGlassBottomSheet(show = showFilters, title = stringResource(TextR.string.text_search_filters), onDismiss = dismissFilters) {
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
        if (loadingOptions) Text(stringResource(TextR.string.text_updating_filter_options), style = MiuixTheme.textStyles.footnote1)
        optionsError?.let { TextButton(text = stringResource(TextR.string.text_could_not_update_filter_options_tap_to_retry), onClick = onRetryOptions) }
        val languages = remember(options.languages) {
            options.languages.sortedWith(compareBy<com.qgming.zlib.core.model.SearchLanguage> {
                when (it.code) { "chinese" -> 0; "english" -> 1; else -> 2 }
            }.thenBy { it.name }).map { ZlibSelectionOption(it.code, it.name, "${it.name} ${it.code}") }
        }
        ZlibPreferenceGroup {
            ZlibMultiSelectPreference(
                title = stringResource(TextR.string.text_language), allLabel = stringResource(TextR.string.text_all_languages), options = languages,
                selected = filters.languages, searchable = true,
                onChange = { onChange(filters.copy(languages = it)) },
            )
            ZlibMultiSelectPreference(
                title = stringResource(TextR.string.text_format), allLabel = stringResource(TextR.string.text_all_formats),
                options = options.extensions.map { ZlibSelectionOption(it, it.uppercase()) },
                selected = filters.extensions, onChange = { onChange(filters.copy(extensions = it)) },
            )
            ZlibPreferenceMenu(
                title = stringResource(TextR.string.text_sort_by), options = SearchSort.entries.map { it.title },
                selectedIndex = SearchSort.entries.indexOfFirst { it.wireValue == filters.order }.coerceAtLeast(0),
                onSelect = { onChange(filters.copy(order = SearchSort.entries[it].wireValue)) },
            )
        }
        Text(stringResource(TextR.string.text_publication_year), style = MiuixTheme.textStyles.body2)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextField(
                value = filters.yearFrom,
                onValueChange = { onChange(filters.withYearFrom(it)) },
                label = stringResource(TextR.string.text_from_year),
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
            TextField(
                value = filters.yearTo,
                onValueChange = { onChange(filters.withYearTo(it)) },
                label = stringResource(TextR.string.text_to_year),
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
        }
        if (!filters.yearRangeValid) {
            Text(stringResource(TextR.string.text_the_start_year_cannot_be_later_than_the_end_year), style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(text = stringResource(TextR.string.text_reset), onClick = { onChange(SearchFilters()) }, modifier = Modifier.weight(1f))
            Button(
                onClick = onApply,
                enabled = filters.yearRangeValid,
                modifier = Modifier.weight(2f),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) { Text(stringResource(TextR.string.text_apply_filters)) }
        }
    }
}
