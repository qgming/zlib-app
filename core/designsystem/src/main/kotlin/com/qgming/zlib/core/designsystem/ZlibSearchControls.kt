package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.basic.SearchCleanup
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
                label = "书名、作者或 ISBN",
                leadingIcon = {
                    IconButton(onClick = onSubmit, enabled = value.isNotBlank()) {
                        Icon(
                            MiuixIcons.Basic.Search,
                            contentDescription = if (value.isNotBlank()) "提交搜索" else null,
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
                                contentDescription = "清空搜索词",
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
        title = "精确匹配",
        summary = "按输入的关键词精确查找",
        checked = checked,
        onCheckedChange = onCheckedChange,
        insideMargin = PaddingValues(vertical = 8.dp),
    )
}
