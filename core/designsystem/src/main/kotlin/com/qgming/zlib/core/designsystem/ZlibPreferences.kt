package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PreferenceRowMargin = PaddingValues(horizontal = 20.dp, vertical = 7.dp)

@Composable
fun ZlibPreferenceGroup(content: @Composable ColumnScope.() -> Unit) {
    ZlibCard(insideMargin = PaddingValues(0.dp), cornerRadius = 24.dp, content = content)
}
@Composable
fun ZlibPreferenceLink(title: String, summary: String? = null, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        insideMargin = PreferenceRowMargin,
        onClick = onClick,
    )
}
@Composable
fun ZlibPreferenceMenu(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val backdrop = LocalZlibOverlayBackdrop.current
    GlassDropdownPreference(
        items = options,
        selectedIndex = selectedIndex,
        title = title,
        backdrop = backdrop,
        visuals = GlassPopupDefaults.visuals(
            style = GlassDefaults.style(zlibIsDark()),
            stroke = if (backdrop != null) zlibGlassStroke() else null,
            containerColor = MiuixTheme.colorScheme.surfaceContainer,
        ),
        insideMargin = PreferenceRowMargin,
        onSelectedIndexChange = onSelect,
    )
}
