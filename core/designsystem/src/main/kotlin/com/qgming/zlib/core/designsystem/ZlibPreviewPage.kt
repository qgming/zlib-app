package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.glass.GlassIconDropdownMenu
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.glass.glassPopupAnchor
import top.yukonga.miuix.kmp.glass.glassPopupAnchorContent
import top.yukonga.miuix.kmp.glass.rememberGlassPopupAnchor
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.More
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibPreviewPage(
    onBack: () -> Unit,
    onRefresh: (() -> Unit)?,
    onCopy: (() -> Unit)?,
    onOpenBrowser: (() -> Unit)?,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val anchor = rememberGlassPopupAnchor()
    val moreLabel = stringResource(TextR.string.text_preview_more)
    val moreIcon = MiuixIcons.Glass.Normal.More
    var showMenu by remember { mutableStateOf(false) }
    CompositionLocalProvider(LocalZlibGlass provides false) {
        Box(Modifier.fillMaxSize()) {
            ZlibPage(
                title = stringResource(TextR.string.text_book_preview),
                onBack = onBack,
                actions = {
                    TopBarButton(
                        icon = moreIcon,
                        description = moreLabel,
                        onClick = { showMenu = true },
                        glass = false,
                        modifier = Modifier.glassPopupAnchor(anchor, GlassTopAppBarDefaults.ButtonSize / 2, floating = true),
                        iconModifier = Modifier.glassPopupAnchorContent(anchor),
                    )
                },
                contentGutters = false,
            ) { padding ->
                Box(Modifier.fillMaxSize()) { content(padding) }
            }
            GlassIconDropdownMenu(
                show = showMenu,
                onDismissRequest = { showMenu = false },
                anchor = anchor,
                anchorContent = { Icon(moreIcon, contentDescription = null, modifier = Modifier.size(28.dp)) },
                entries = listOf(DropdownEntry(listOf(
                    DropdownItem(stringResource(TextR.string.text_refresh_preview), enabled = onRefresh != null, onClick = onRefresh),
                    DropdownItem(stringResource(TextR.string.text_copy_book_link), enabled = onCopy != null, onClick = onCopy),
                    DropdownItem(stringResource(TextR.string.text_open_in_browser), enabled = onOpenBrowser != null, onClick = onOpenBrowser),
                ))),
                backdrop = null,
                sizing = GlassPopupSizing(minWidth = 188.dp, maxWidth = 248.dp),
                visuals = GlassPopupDefaults.visuals(
                    style = GlassDefaults.style(zlibIsDark()),
                    stroke = null,
                    containerColor = MiuixTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    }
}

@Composable
fun ZlibPreviewProgress(progress: Int, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = modifier.fillMaxWidth())
}
