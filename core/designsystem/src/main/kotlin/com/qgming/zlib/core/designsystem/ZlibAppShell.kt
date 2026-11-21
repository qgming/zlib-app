package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

data class ZlibTab(
    val label: String,
    val icon: ImageVector,
)

/**
 * 4-tab shell with the latest Miuix glass bars. Content is recorded into a layer behind the
 * floating bars so the glass material has something real to sample.
 */
@Composable
fun ZlibAppShell(
    title: String,
    tabs: List<ZlibTab>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    glass: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (!glass) {
        PlainAppShell(title, tabs, selectedTab, onTabSelected, content)
        return
    }

    val backdrop = rememberLayerBackdrop()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navigationBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val contentPadding = remember(statusTop, navigationBottom) {
        PaddingValues(
            top = statusTop + TopAppBarDefaults.CollapsedHeight + 4.dp,
            bottom = navigationBottom + 76.dp,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            content(contentPadding)
        }

        GlassTopAppBar(
            title = title,
            modifier = Modifier.align(Alignment.TopCenter),
            expandable = false,
            backdrop = backdrop,
        )

        GlassNavigationBar(
            items = tabs.map { GlassNavigationItem(icon = it.icon, label = it.label) },
            selectedIndex = selectedTab,
            onSelect = onTabSelected,
            backdrop = backdrop,
            defaultWindowInsetsPadding = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

@Composable
private fun PlainAppShell(
    title: String,
    tabs: List<ZlibTab>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = title, expandable = false) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = index == selectedTab,
                        onClick = { onTabSelected(index) },
                        icon = tab.icon,
                        label = tab.label,
                    )
                }
            }
        },
        content = content,
    )
}
