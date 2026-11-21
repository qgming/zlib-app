package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.glass.GlassDropdownPreference
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Contacts
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Link
import top.yukonga.miuix.kmp.icon.glass.Theme
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class ZlibPreferenceIcon(internal val icon: ImageVector) {
    Account(MiuixIcons.Glass.Contacts),
    Downloads(MiuixIcons.Glass.Download),
    Domain(MiuixIcons.Glass.Link),
    Color(MiuixIcons.Glass.Theme),
}

@Composable
fun ZlibPreferenceGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ZlibSectionTitle(title)
        ZlibCard(insideMargin = PaddingValues(0.dp), content = content)
    }
}

@Composable
fun ZlibPreferenceLink(title: String, summary: String, icon: ZlibPreferenceIcon, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        startAction = { PreferenceIcon(icon) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onClick = onClick,
    )
}

@Composable
fun ZlibPreferenceMenu(
    title: String,
    icon: ZlibPreferenceIcon,
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
        startAction = { PreferenceIcon(icon) },
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        onSelectedIndexChange = onSelect,
    )
}

@Composable
fun ZlibPreferenceDivider() {
    HorizontalDivider(modifier = Modifier.padding(start = 24.dp + preferenceIconSize(), end = 16.dp))
}

@Composable
private fun PreferenceIcon(icon: ZlibPreferenceIcon) {
    Icon(
        imageVector = icon.icon,
        contentDescription = null,
        tint = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.size(preferenceIconSize()),
    )
}

@Composable
private fun preferenceIconSize() = with(LocalDensity.current) {
    MiuixTheme.textStyles.headline1.fontSize.toDp() * 1.25f
}
