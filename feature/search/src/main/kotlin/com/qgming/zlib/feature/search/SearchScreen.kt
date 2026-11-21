package com.qgming.zlib.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCover
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.model.Book
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SearchScreen(
    padding: PaddingValues,
    onOpenBook: (Book) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                label = "书名、作者或 ISBN",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                trailingIcon = {
                    IconButton(onClick = viewModel::search) {
                        Icon(MiuixIcons.Search, contentDescription = "搜索")
                    }
                },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("精确匹配", style = MiuixTheme.textStyles.footnote1)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = state.filters.exact,
                    onCheckedChange = viewModel::toggleExact,
                )
                Spacer(Modifier.weight(1f))
                TextButton(text = if (state.showFilters) "收起筛选" else "筛选", onClick = viewModel::toggleFilters)
            }
        }

        if (state.showFilters) {
            FilterPanel(
                filters = state.filters,
                onLanguage = viewModel::toggleLanguage,
                onExtension = viewModel::toggleExtension,
                onYearFrom = viewModel::onYearFromChange,
                onYearTo = viewModel::onYearToChange,
                onOrder = viewModel::setOrder,
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state.loading -> item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null -> item {
                    ZlibCard {
                        Text(state.error!!, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                        Spacer(Modifier.height(6.dp))
                        TextButton(text = "重试", onClick = viewModel::search)
                    }
                }

                state.searched && state.books.isEmpty() -> item {
                    ZlibCard {
                        Text("没有找到结果", style = MiuixTheme.textStyles.body2)
                        Text(
                            "换关键词、去掉筛选条件，或者确认登录状态。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }

                state.searched -> items(state.books, key = { it.ref }) { book ->
                    BookRow(book = book, onClick = { onOpenBook(book) })
                }

                else -> {
                    if (state.landingLoading) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    if (state.popular.isNotEmpty()) {
                        item { ZlibSectionTitle("热门") }
                        items(state.popular.take(10), key = { "pop-${it.ref}" }) { book ->
                            BookRow(book = book, onClick = { onOpenBook(book) })
                        }
                    }
                    if (state.recommended.isNotEmpty()) {
                        item { ZlibSectionTitle("为你推荐") }
                        items(state.recommended.take(10), key = { "rec-${it.ref}" }) { book ->
                            BookRow(book = book, onClick = { onOpenBook(book) })
                        }
                    }
                    if (!state.landingLoading && state.popular.isEmpty() && state.recommended.isEmpty()) {
                        item {
                            ZlibCard {
                                Text("直接搜索书名、作者或 ISBN。", style = MiuixTheme.textStyles.body2)
                                Text(
                                    "登录后可看到热门与推荐；推荐接口不可用时会留空。",
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                }
            }

            state.appendError?.let { message ->
                item {
                    ZlibCard {
                        Text(message, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
                        TextButton(text = "重试", onClick = viewModel::loadMore)
                    }
                }
            }

            if (state.loadingMore) {
                item {
                    Text(
                        "正在加载更多…",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else if (state.hasMore) {
                item {
                    TextButton(text = "加载更多", onClick = viewModel::loadMore)
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    ZlibCard(onClick = onClick) {
        Row {
            ZlibCover(
                url = book.coverUrl,
                modifier = Modifier.width(64.dp).height(92.dp),
                contentDescription = book.title,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MiuixTheme.textStyles.title4,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.authors.isNotBlank()) {
                    Text(
                        text = book.authors,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val meta = listOf(book.year, book.language, book.extension.uppercase())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                book.rating?.let { rating ->
                    Text(
                        text = "★ ${"%.1f".format(rating)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterPanel(
    filters: SearchFilters,
    onLanguage: (String) -> Unit,
    onExtension: (String) -> Unit,
    onYearFrom: (String) -> Unit,
    onYearTo: (String) -> Unit,
    onOrder: (String?) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        ZlibCard {
            Text("语言", style = MiuixTheme.textStyles.body2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("English", "english" in filters.languages) { onLanguage("english") }
                FilterChip("中文", "chinese" in filters.languages) { onLanguage("chinese") }
                FilterChip("Deutsch", "german" in filters.languages) { onLanguage("german") }
            }
            Spacer(Modifier.height(10.dp))
            Text("格式", style = MiuixTheme.textStyles.body2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("epub", "pdf", "mobi", "azw3", "fb2").forEach { extension ->
                    FilterChip(extension.uppercase(), extension in filters.extensions) {
                        onExtension(extension)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = filters.yearFrom,
                    onValueChange = onYearFrom,
                    label = "起始年份",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextField(
                    value = filters.yearTo,
                    onValueChange = onYearTo,
                    label = "结束年份",
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("排序", style = MiuixTheme.textStyles.body2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("默认", filters.order == null) { onOrder(null) }
                FilterChip("热门", filters.order == "popular") { onOrder("popular") }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val background = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.secondary
    val foreground = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSecondary
    Surface(
        onClick = onClick,
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
