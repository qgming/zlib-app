package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val SearchHistoryChipShape = RoundedCornerShape(percent = 50)

@Composable
fun ZlibRecentSearches(
    queries: List<String>,
    onSearch: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    if (queries.isEmpty()) return
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(TextR.string.text_recent_searches), style = MiuixTheme.textStyles.title4)
            ZlibClearSearchHistoryButton(onClick = { confirmClear = true })
        }
        ZlibSearchHistoryChips(
            queries = queries,
            onClick = onSearch,
            onLongClick = onRemove,
            longClickLabel = stringResource(TextR.string.text_delete_search_history_entry),
        )
    }
    ZlibConfirmDialog(
        show = confirmClear,
        title = stringResource(TextR.string.text_clear_recent_searches_2),
        summary = stringResource(TextR.string.text_this_cannot_be_undone),
        confirmLabel = stringResource(TextR.string.text_clear),
        destructive = true,
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            onClear()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZlibSearchHistoryChips(
    queries: List<String>,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    longClickLabel: String,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        queries.forEach { query ->
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .heightIn(min = 40.dp)
                    .clip(SearchHistoryChipShape)
                    .background(MiuixTheme.colorScheme.surfaceContainer)
                    .combinedClickable(
                        role = Role.Button,
                        onClick = { onClick(query) },
                        onLongClickLabel = longClickLabel,
                        onLongClick = { onLongClick(query) },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = query,
                    style = MiuixTheme.textStyles.body2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


@Composable
private fun ZlibClearSearchHistoryButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp), backgroundColor = Color.Transparent) {
        Icon(
            MiuixIcons.Glass.Normal.Close,
            contentDescription = stringResource(TextR.string.text_clear_recent_searches),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun ZlibSearchBar(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    SearchBar(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp),
        insideMargin = DpSize.Zero,
        expanded = false,
        onExpandedChange = {},
        inputField = {
            InputField(
                query = value,
                onQueryChange = onValueChange,
                onSearch = { onSubmit() },
                expanded = false,
                onExpandedChange = {},
                label = stringResource(TextR.string.text_title_author_or_isbn),
                leadingIcon = {
                    IconButton(onClick = onSubmit, enabled = value.isNotBlank()) {
                        Icon(
                            MiuixIcons.Basic.Search,
                            contentDescription = if (value.isNotBlank()) stringResource(TextR.string.text_submit_search) else null,
                            tint = if (value.isNotBlank()) colors.onSurface else colors.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                trailingIcon = {
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { onValueChange("") }) {
                            Icon(
                                MiuixIcons.Basic.SearchCleanup,
                                contentDescription = stringResource(TextR.string.text_clear_search),
                                tint = colors.onSurfaceVariantSummary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )
        },
    ) {}
}

@Composable
fun ZlibExactMatchPreference(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchPreference(
        title = stringResource(TextR.string.text_exact_match),
        summary = stringResource(TextR.string.text_prioritize_results_that_match_your_keywords_exactly),
        checked = checked,
        onCheckedChange = onCheckedChange,
        insideMargin = PaddingValues(vertical = 8.dp),
    )
}
