package com.qgming.zlib.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.AppViewModel
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.designsystem.ZlibAppShell
import com.qgming.zlib.core.designsystem.LocalZlibBookSnapshotsEnabled
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibLoadingIndicator
import com.qgming.zlib.core.designsystem.ZlibTab
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.feature.account.AccountScreen
import com.qgming.zlib.feature.account.LoginScreen
import com.qgming.zlib.feature.booklists.BooklistsScreen
import com.qgming.zlib.feature.downloads.DownloadsScreen
import com.qgming.zlib.feature.downloads.LocalFilesScreen
import com.qgming.zlib.feature.library.DownloadHistoryScreen
import com.qgming.zlib.feature.library.LibraryScreen
import com.qgming.zlib.feature.library.LibrarySection
import com.qgming.zlib.feature.library.LibraryTabs
import com.qgming.zlib.feature.search.PopularScreen
import com.qgming.zlib.feature.search.SearchScreen
import com.qgming.zlib.feature.settings.DomainSettingsScreen
import com.qgming.zlib.feature.settings.SettingsScreen
import com.qgming.zlib.navigation.BookDetailLauncher
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavController
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Serializable
private sealed interface Route : NavKey {
    @Serializable
    data object Home : Route

    @Serializable
    data object Search : Route

    @Serializable
    data object Downloads : Route

    @Serializable
    data object LocalFiles : Route

    @Serializable
    data object DownloadHistory : Route

    @Serializable
    data object Account : Route

    @Serializable
    data object DomainSettings : Route

}

@Composable
fun ZlibRoot(viewModel: AppViewModel = hiltViewModel()) {
    val startup by viewModel.startupState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    ZlibTheme(mode = settings.themeMode, bookGridColumns = settings.bookGridColumns) {
        when (val state = startup) {
            StartupState.Loading -> SplashScreen()

            is StartupState.Failed -> StartupFailedScreen(
                message = state.error.userMessage(),
                actionError = message,
                onRetry = viewModel::retry,
                onAddHost = viewModel::addCustomHost,
            )

            StartupState.Ready -> if (session == null) {
                LoginScreen()
            } else {
                MainShell()
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Zlib", style = MiuixTheme.textStyles.title1)
            Spacer(Modifier.height(16.dp))
            ZlibLoadingIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "正在检查会话与域名…",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun StartupFailedScreen(
    message: String,
    actionError: String?,
    onRetry: () -> Unit,
    onAddHost: (String) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("无法连接 Z-Library", style = MiuixTheme.textStyles.title3)
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(onClick = onRetry) {
                Text("重新体检")
            }
            ZlibCard {
                Text("手动添加镜像", style = MiuixTheme.textStyles.body2)
                Text(
                    text = "如果内置域名全部不可用，可添加你信任的 HTTPS 镜像主机。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "mirror.example.org",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                actionError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (host.isNotBlank()) onAddHost(host)
                    },
                    enabled = host.isNotBlank(),
                ) {
                    Text("添加并重试")
                }
            }
        }
    }
}

@Composable
private fun MainShell() {
    val nav = rememberNavController<Route>(Route.Home)
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val supportsSharedView = remember { BookDetailLauncher.isSupported() }
    val openBook: (ZlibBookOpenRequest) -> Unit = { request ->
        activity?.let { scope.launch { BookDetailLauncher.open(it, request) } }
    }
    CompositionLocalProvider(LocalZlibBookSnapshotsEnabled provides supportsSharedView) {
        NavDisplay(
            navController = nav,
            effects = NavDisplayEffects(
                backdropColor = MiuixTheme.colorScheme.surface,
                blockInputDuringTransition = true,
            ),
        ) {
            entry<Route.Home> {
                HomeTabs(
                    onOpenBook = openBook,
                    onOpenSearch = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.Search) },
                    onOpenDownloads = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.Downloads) },
                    onOpenAccount = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.Account) },
                    onOpenDomains = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.DomainSettings) },
                )
            }
            entry<Route.Search> {
                SearchScreen(onBack = { nav.pop() }, onOpenBook = openBook)
            }
            entry<Route.Downloads> {
                DownloadsScreen(
                    onBack = { nav.pop() },
                    onOpenFiles = { if (nav.backStack.lastOrNull() == Route.Downloads) nav.push(Route.LocalFiles) },
                    onOpenHistory = { if (nav.backStack.lastOrNull() == Route.Downloads) nav.push(Route.DownloadHistory) },
                )
            }
            entry<Route.LocalFiles> {
                LocalFilesScreen(onBack = { nav.pop() }, onOpenBook = openBook)
            }
            entry<Route.DownloadHistory> {
                DownloadHistoryScreen(onBack = { nav.pop() }, onOpenBook = openBook)
            }
            entry<Route.Account> {
                AccountScreen(onBack = { nav.pop() })
            }
            entry<Route.DomainSettings> {
                DomainSettingsScreen(onBack = { nav.pop() })
            }
        }
    }
}

@Composable
private fun HomeTabs(
    onOpenBook: (ZlibBookOpenRequest) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDomains: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(ZlibTab.Popular.name) }
    val selectedTab = ZlibTab.entries.firstOrNull { it.name == tabName } ?: ZlibTab.Popular
    var librarySectionName by rememberSaveable { mutableStateOf(LibrarySection.Saved.name) }
    val librarySection = LibrarySection.entries.firstOrNull { it.name == librarySectionName } ?: LibrarySection.Saved
    val tabStateHolder = rememberSaveableStateHolder()
    val readingGridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    ZlibAppShell(
        selectedTab = selectedTab,
        onTabSelected = { tabName = it.name },
        onOpenSearch = onOpenSearch,
        onOpenDownloads = onOpenDownloads,
        onReadingDoubleTap = { scope.launch { readingGridState.animateScrollToItem(0) } },
        topBarContent = { tab ->
            if (tab == ZlibTab.Library) {
                LibraryTabs(selected = librarySection, onSelect = { librarySectionName = it.name })
            }
        },
    ) { tab, padding ->
        tabStateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                ZlibTab.Popular -> PopularScreen(padding = padding, onOpenBook = onOpenBook, gridState = readingGridState)
                ZlibTab.Library -> LibraryScreen(
                    padding = padding,
                    section = librarySection,
                    onOpenBook = onOpenBook,
                    booklistsContent = { BooklistsScreen(it) },
                )
                ZlibTab.Settings -> SettingsScreen(padding = padding, onOpenAccount = onOpenAccount, onOpenDomains = onOpenDomains)
            }
        }
    }
}
