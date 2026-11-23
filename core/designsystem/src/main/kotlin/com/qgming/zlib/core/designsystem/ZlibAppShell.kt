package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.BookOutline
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Cloud
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.NotesOutline
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.icon.glass.Tune
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class ZlibTab(private val labelRes: Int, internal val icon: ImageVector) {
    Popular(TextR.string.text_discover, MiuixIcons.Glass.Normal.NotesOutline),
    Library(TextR.string.text_bookshelf, MiuixIcons.Glass.BookOutline),
    Settings(TextR.string.text_settings, MiuixIcons.Glass.SettingsOutline);

    val label: String get() = appText(labelRes)
}

private data class TopBarGlass(val backdrop: Backdrop, val scrolled: Boolean)

private val LocalTopBarGlass = compositionLocalOf<TopBarGlass?> { null }

@Composable
fun ZlibAppShell(
    selectedTab: ZlibTab,
    onTabSelected: (ZlibTab) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onReadingDoubleTap: () -> Unit,
    topBarContent: @Composable (ZlibTab) -> Unit = {},
    content: @Composable (ZlibTab, PaddingValues) -> Unit,
) {
    val tabStates = ZlibTab.entries.map { tab -> key(tab) { rememberTopAppBarState() } }
    val tabScrolls = ZlibTab.entries.map { tab ->
        key(tab) {
            val behavior = MiuixScrollBehavior(state = tabStates[tab.ordinal])
            remember(behavior) { ZlibPageScroll(behavior) }
        }
    }
    val doubleTapTimeout = LocalViewConfiguration.current.doubleTapTimeoutMillis
    val readingTap = remember(doubleTapTimeout) { ReadingTabDoubleTap(doubleTapTimeout) }
    val transition = updateTransition(selectedTab, label = "主导航切换")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val backdrop = if (LocalZlibGlass.current) rememberLayerBackdrop() else null
    val background = MiuixTheme.colorScheme.surface

    ZlibOverlayHost {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = background,
            contentWindowInsets = WindowInsets(0),
            popupHost = {},
            bottomBar = {
                ZlibBottomBar(
                    selectedTab,
                    onTabSelected = { tab ->
                        if (readingTap.register(tab == ZlibTab.Popular, SystemClock.uptimeMillis())) {
                            tabStates[ZlibTab.Popular.ordinal].apply {
                                heightOffset = 0f
                                contentOffset = 0f
                                overscrollOffset = 0f
                            }
                            onReadingDoubleTap()
                        }
                        onTabSelected(tab)
                    },
                    backdrop = backdrop,
                )
            },
        ) { shellPadding ->
            val changing = transition.isRunning || transition.currentState != transition.targetState
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .background(background)
                    .then(
                        if (changing) Modifier.pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                            }
                        } else Modifier,
                    ),
            ) {
                transition.AnimatedContent(
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val towards = if ((targetState.ordinal > initialState.ordinal) != rtl) SlideDirection.Left else SlideDirection.Right
                        slideIntoContainer(towards, tween(150, easing = FastOutSlowInEasing))
                            .togetherWith(slideOutOfContainer(towards, tween(150, easing = FastOutSlowInEasing)))
                            .using(null)
                    },
                ) { tab ->
                    PageFrame(
                        title = tab.label,
                        appBarState = tabStates[tab.ordinal],
                        providedPageScroll = tabScrolls[tab.ordinal],
                        expandable = true,
                        onSearch = onOpenSearch.takeIf { tab == ZlibTab.Popular },
                        onDownloads = onOpenDownloads.takeIf { tab == ZlibTab.Popular },
                        topBarContent = { topBarContent(tab) },
                        // Reserve the fixed bar's measured height, including its safe inset.
                        bottomBar = { Spacer(Modifier.fillMaxWidth().height(shellPadding.calculateBottomPadding())) },
                    ) { padding ->
                        content(tab, padding)
                    }
                }
            }
        }
    }
}

@Composable
fun ZlibPage(
    title: String,
    onBack: (() -> Unit)? = null,
    expandable: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    topBarContent: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    ZlibOverlayHost {
        PageFrame(
            title = title,
            appBarState = rememberTopAppBarState(),
            expandable = expandable,
            onBack = onBack,
            actions = actions,
            topBarContent = topBarContent,
            content = content,
        )
    }
}

@Composable
private fun PageFrame(
    title: String,
    appBarState: TopAppBarState,
    providedPageScroll: ZlibPageScroll? = null,
    expandable: Boolean = false,
    onBack: (() -> Unit)? = null,
    onSearch: (() -> Unit)? = null,
    onDownloads: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    topBarContent: @Composable () -> Unit = {},
    bottomBar: @Composable (Backdrop?) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = if (LocalZlibGlass.current) rememberLayerBackdrop() else null
    val defaultScrollBehavior = MiuixScrollBehavior(state = appBarState)
    val pageScroll = providedPageScroll ?: remember(defaultScrollBehavior) { ZlibPageScroll(defaultScrollBehavior) }
    val scrollBehavior = pageScroll.behavior
    val scrolled by remember(pageScroll) {
        derivedStateOf { pageScroll.content?.canScrollBackward == true || appBarState.overscrollOffset < -1f }
    }
    val background = MiuixTheme.colorScheme.surface
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val navigationIcon: @Composable () -> Unit = {
        onBack?.let {
            TopBarButton(MiuixIcons.Glass.ChevronBackward, stringResource(TextR.string.text_back), it, backdrop != null)
        }
    }
    val topActions: @Composable RowScope.() -> Unit = {
        actions()
        onSearch?.let {
            TopBarButton(MiuixIcons.Glass.Search, stringResource(TextR.string.text_search), it, backdrop != null)
        }
        onDownloads?.let {
            if (onSearch != null) Spacer(Modifier.width(8.dp))
            TopBarButton(MiuixIcons.Glass.Download, stringResource(TextR.string.text_download_manager), it, backdrop != null)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = background,
        contentWindowInsets = safeInsets.union(WindowInsets.ime),
        popupHost = {},
        topBar = {
            if (backdrop != null) {
                GlassTopAppBar(
                    title = title,
                    backdrop = backdrop,
                    expandable = expandable,
                    progressiveBlur = true,
                    scrollBehavior = scrollBehavior,
                    isContentScrolled = scrolled,
                    style = GlassDefaults.style(zlibIsDark()),
                    stroke = zlibGlassStroke(),
                    navigationIcon = navigationIcon,
                    actions = topActions,
                    bottomContent = {
                        CompositionLocalProvider(LocalTopBarGlass provides TopBarGlass(backdrop, scrolled), content = topBarContent)
                    },
                )
            } else {
                TopAppBar(
                    title = title,
                    expandable = expandable,
                    scrollBehavior = scrollBehavior,
                    color = background,
                    navigationIcon = navigationIcon,
                    actions = topActions,
                    bottomContent = topBarContent,
                )
            }
        },
        bottomBar = { bottomBar(backdrop) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(background),
        ) {
            CompositionLocalProvider(LocalZlibPageScroll provides pageScroll) {
                content(remember(padding) { ZlibContentPadding(padding) })
            }
        }
    }
}

@Composable
fun ZlibTopBarTabs(tabs: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val glass = LocalTopBarGlass.current
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 8.dp)) {
        if (glass != null) {
            GlassSegmentedTabRow(
                tabs = tabs,
                selectedIndex = selectedIndex,
                onSelect = onSelect,
                backdrop = glass.backdrop,
                modifier = Modifier.fillMaxWidth(),
                style = GlassDefaults.style(zlibIsDark()),
                surfaceAlpha = if (glass.scrolled) 1f else 0f,
                stroke = zlibGlassStroke(),
            )
        } else {
            TabRowWithContour(tabs = tabs, selectedTabIndex = selectedIndex, onTabSelected = onSelect)
        }
    }
}

@Composable
fun ZlibFilterButton(active: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    TopBarButton(
        icon = MiuixIcons.Glass.Tune,
        description = stringResource(TextR.string.text_search_filters),
        onClick = onClick,
        glass = LocalZlibGlass.current,
        tint = if (active) colors.primary else colors.onSurface,
        modifier = if (active) Modifier.semantics { stateDescription = appText(TextR.string.text_filters_enabled) } else Modifier,
    )
}

@Composable
fun ZlibBookInfoButton(enabled: Boolean, onClick: () -> Unit) {
    TopBarButton(
        icon = MiuixIcons.Glass.Normal.Info,
        description = stringResource(TextR.string.text_book_information),
        onClick = onClick,
        glass = LocalZlibGlass.current,
        enabled = enabled,
        tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
fun ZlibDownloadHistoryButton(onClick: () -> Unit) {
    TopBarButton(
        icon = MiuixIcons.Glass.Normal.Cloud,
        description = stringResource(TextR.string.text_cloud_download_history), onClick = onClick, glass = LocalZlibGlass.current,
    )
}

@Composable
fun ZlibAddAccountButton(onClick: () -> Unit) {
    TopBarButton(MiuixIcons.Glass.Normal.Add, stringResource(TextR.string.text_add_account), onClick, LocalZlibGlass.current)
}

@Composable
fun ZlibAddDomainButton(onClick: () -> Unit) {
    TopBarButton(MiuixIcons.Glass.Normal.Add, stringResource(TextR.string.text_add_domain), onClick, LocalZlibGlass.current)
}

@Composable
fun ZlibProbeDomainsButton(enabled: Boolean, onClick: () -> Unit) {
    TopBarButton(
        MiuixIcons.Glass.Normal.Refresh, stringResource(TextR.string.text_check_all_domains), onClick, LocalZlibGlass.current,
        enabled = enabled,
        tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

@Composable
private fun TopBarButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    glass: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurface,
    enabled: Boolean = true,
) {
    val iconContent: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
    if (glass) {
        GlassIconButton(
            onClick = { if (enabled) onClick() },
            modifier = modifier.semantics {
                role = Role.Button
                if (!enabled) disabled()
            },
            content = iconContent,
        )
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(GlassTopAppBarDefaults.ButtonSize),
            content = iconContent,
        )
    }
}

@Composable
private fun ZlibBottomBar(
    selectedTab: ZlibTab,
    onTabSelected: (ZlibTab) -> Unit,
    backdrop: Backdrop?,
) {
    val tabs = ZlibTab.entries
    val items = tabs.map { GlassNavigationItem(icon = it.icon, label = it.label) }
    val barWidth = 64.dp * tabs.size + GlassNavigationBarDefaults.ContentPaddingHorizontal * 2

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.systemBars.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        GlassNavigationBar(
            items = items,
            selectedIndex = selectedTab.ordinal,
            onSelect = { onTabSelected(tabs[it]) },
            backdrop = backdrop,
            defaultWindowInsetsPadding = false,
            modifier = Modifier.widthIn(max = barWidth),
            style = GlassDefaults.style(zlibIsDark()),
            stroke = if (backdrop != null) zlibGlassStroke() else null,
            shadow = GlassShadows.Float.takeIf { backdrop != null },
        )
    }
}
