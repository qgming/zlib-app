package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

data class ZlibTab(
    val label: String,
    val icon: ImageVector,
)

@Composable
fun ZlibAppShell(
    title: String,
    tabs: List<ZlibTab>,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = title)
        },
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
