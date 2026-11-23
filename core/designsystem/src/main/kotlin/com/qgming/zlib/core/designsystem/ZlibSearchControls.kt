package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibClearSearchHistoryButton(onClick: () -> Unit) {
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
