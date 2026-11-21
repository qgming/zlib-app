package com.qgming.zlib.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.AppViewModel
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.designsystem.ZlibAppShell
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibLoadingIndicator
import com.qgming.zlib.core.designsystem.ZlibTab
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.feature.account.AccountScreen
import com.qgming.zlib.feature.account.LoginScreen
import com.qgming.zlib.feature.detail.DetailScreen
import com.qgming.zlib.feature.downloads.DownloadsScreen
import com.qgming.zlib.feature.library.LibraryScreen
import com.qgming.zlib.feature.library.LibrarySection
import com.qgming.zlib.feature.library.LibraryTabs
import com.qgming.zlib.feature.search.PopularScreen
import com.qgming.zlib.feature.search.SearchScreen
import com.qgming.zlib.feature.settings.DomainSettingsScreen
import com.qgming.zlib.feature.settings.SettingsScreen
import kotlinx.serialization.Serializable
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
    data object Account : Route

    @Serializable
    data object DomainSettings : Route

    @Serializable
    data class BookDetail(val id: String, val hash: String) : Route
}

@Composable
fun ZlibRoot(viewModel: AppViewModel = hiltViewModel()) {
    val startup by viewModel.startupState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    ZlibTheme(mode = settings.themeMode) {
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
    val openBook: (Book) -> Unit = { book -> nav.push(Route.BookDetail(book.id, book.hash)) }
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
            DownloadsScreen(onBack = { nav.pop() })
        }
        entry<Route.Account> {
            AccountScreen(onBack = { nav.pop() })
        }
        entry<Route.DomainSettings> {
            DomainSettingsScreen(onBack = { nav.pop() })
        }
        entry<Route.BookDetail> { route ->
            DetailScreen(
                id = route.id,
                hash = route.hash,
                onBack = { nav.pop() },
            )
        }
    }
}

@Composable
private fun HomeTabs(
    onOpenBook: (Book) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenDomains: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(ZlibTab.Popular.name) }
    val selectedTab = ZlibTab.entries.firstOrNull { it.name == tabName } ?: ZlibTab.Popular
    var librarySection by rememberSaveable { mutableStateOf(LibrarySection.Downloaded) }
    val tabStateHolder = rememberSaveableStateHolder()

    ZlibAppShell(
        selectedTab = selectedTab,
        onTabSelected = { tabName = it.name },
        onOpenSearch = onOpenSearch,
        onOpenDownloads = onOpenDownloads,
        topBarContent = {
            if (selectedTab == ZlibTab.Library) {
                LibraryTabs(selected = librarySection, onSelect = { librarySection = it })
            }
        },
    ) { padding ->
        tabStateHolder.SaveableStateProvider(selectedTab.name) {
            when (selectedTab) {
                ZlibTab.Popular -> PopularScreen(padding = padding, onOpenBook = onOpenBook)
                ZlibTab.Library -> LibraryScreen(padding = padding, section = librarySection, onOpenBook = onOpenBook)
                ZlibTab.Settings -> SettingsScreen(padding = padding, onOpenAccount = onOpenAccount, onOpenDomains = onOpenDomains)
            }
        }
    }
}
