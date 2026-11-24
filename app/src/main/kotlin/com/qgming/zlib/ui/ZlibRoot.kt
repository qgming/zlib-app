package com.qgming.zlib.ui

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.AppViewModel
import com.qgming.zlib.BuildConfig
import com.qgming.zlib.R
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.designsystem.ZlibAppShell
import com.qgming.zlib.core.designsystem.ZlibAppOverlay
import com.qgming.zlib.core.designsystem.LocalZlibBookSnapshotsEnabled
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibLoadingIndicator
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibTab
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.feature.account.AccountScreen
import com.qgming.zlib.feature.account.LoginScreen
import com.qgming.zlib.feature.downloads.DownloadsScreen
import com.qgming.zlib.feature.library.BooklistBooksScreen
import com.qgming.zlib.feature.library.BooklistViewModel
import com.qgming.zlib.feature.library.DownloadHistoryScreen
import com.qgming.zlib.feature.library.LibraryScreen
import com.qgming.zlib.feature.library.LibrarySection
import com.qgming.zlib.feature.library.LibraryTabs
import com.qgming.zlib.feature.library.MyBooklistsScreen
import com.qgming.zlib.feature.search.PopularScreen
import com.qgming.zlib.feature.search.ReadingSource
import com.qgming.zlib.feature.search.ReadingTabs
import com.qgming.zlib.feature.search.SearchScreen
import com.qgming.zlib.feature.settings.DomainSettingsScreen
import com.qgming.zlib.feature.settings.AboutAppInfo
import com.qgming.zlib.feature.settings.AboutDocumentScreen
import com.qgming.zlib.feature.settings.AboutPage
import com.qgming.zlib.feature.settings.AboutScreen
import com.qgming.zlib.feature.settings.AboutViewModel
import com.qgming.zlib.feature.settings.AppUpdateHost
import com.qgming.zlib.feature.settings.ReleaseHistoryScreen
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
    data object DownloadHistory : Route

    @Serializable
    data object MyBooklists : Route

    @Serializable
    data class Booklist(val id: String) : Route

    @Serializable
    data object Account : Route

    @Serializable
    data object DomainSettings : Route

    @Serializable
    data object About : Route

    @Serializable
    data object ReleaseHistory : Route

    @Serializable
    data class AboutDocument(val pageName: String) : Route

}

@Composable
fun ZlibRoot(viewModel: AppViewModel = hiltViewModel(), downloadRequest: Int = 0) {
    val startup by viewModel.startupState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val choosingDirectory by viewModel.choosingDirectory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(startup) {
        if (startup is StartupState.Ready && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::chooseInitialDirectory)
    }
    val updates: AboutViewModel = hiltViewModel()
    val appInfo = AboutAppInfo(stringResource(R.string.app_name), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())

    ZlibTheme(mode = settings.themeMode, bookGridColumns = settings.bookGridColumns) {
        ZlibAppOverlay {
            when (val state = startup) {
                StartupState.Loading -> SplashScreen()

                StartupState.DirectoryRequired -> DirectoryWelcome(
                    busy = choosingDirectory, error = message,
                    onChoose = { directoryPicker.launch(null) },
                )

                is StartupState.Failed -> StartupFailedScreen(
                    message = state.error.userMessage(),
                    actionError = message,
                    onRetry = viewModel::retry,
                    onAddHost = viewModel::addCustomHost,
                )

                StartupState.Ready -> if (session == null) {
                    if (accounts.entries.isEmpty()) LoginScreen() else AccountScreen(onBack = null)
                } else {
                    MainShell(appInfo, updates, downloadRequest)
                }
            }
            if (startup is StartupState.Ready) AppUpdateHost(appInfo, updates)
        }
    }
}

@Composable
private fun DirectoryWelcome(busy: Boolean, error: String?, onChoose: () -> Unit) {
    ZlibPage(title = stringResource(TextR.string.text_choose_download_folder)) { padding ->
        ZlibCardList(padding) {
            item {
                ZlibCard(cornerRadius = 24.dp) {
                    Text(stringResource(TextR.string.text_welcome_to_zlib), style = MiuixTheme.textStyles.title3)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(TextR.string.text_choose_a_folder_for_downloaded_books_and_app_updates_then_sign_in), style = MiuixTheme.textStyles.body2)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onChoose, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) stringResource(TextR.string.text_saving) else stringResource(TextR.string.text_choose_folder))
                    }
                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.footnote1)
                    }
                }
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
            Text(stringResource(R.string.app_name), style = MiuixTheme.textStyles.title1)
            Spacer(Modifier.height(16.dp))
            ZlibLoadingIndicator()
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(TextR.string.text_checking_session_and_domains),
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
            Text(stringResource(TextR.string.text_cannot_connect_to_z_library), style = MiuixTheme.textStyles.title3)
            Text(
                text = message,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(onClick = onRetry) {
                Text(stringResource(TextR.string.text_check_again))
            }
            ZlibCard {
                Text(stringResource(TextR.string.text_add_a_mirror), style = MiuixTheme.textStyles.body2)
                Text(
                    text = stringResource(TextR.string.text_if_the_built_in_domains_are_unavailable_add_a_trusted_https_mirror_host),
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
                    Text(stringResource(TextR.string.text_add_and_retry))
                }
            }
        }
    }
}

@Composable
private fun MainShell(appInfo: AboutAppInfo, updates: AboutViewModel, downloadRequest: Int) {
    val nav = key("main-routes-v2") { rememberNavController<Route>(Route.Home) }
    var handledDownload by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(downloadRequest) {
        if (downloadRequest > handledDownload) {
            handledDownload = downloadRequest
            if (nav.backStack.lastOrNull() != Route.Downloads) nav.push(Route.Downloads)
        }
    }
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val supportsSharedView = remember { BookDetailLauncher.isSupported() }
    val booklists: BooklistViewModel = hiltViewModel(key = "booklists")
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
                    onOpenMyBooklists = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.MyBooklists) },
                    onOpenDomains = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.DomainSettings) },
                    onOpenAbout = { if (nav.backStack.lastOrNull() == Route.Home) nav.push(Route.About) },
                )
            }
            entry<Route.Search> {
                SearchScreen(onBack = { nav.pop() }, onOpenBook = openBook)
            }
            entry<Route.Downloads> {
                DownloadsScreen(
                    onBack = { nav.pop() },
                    onOpenHistory = { if (nav.backStack.lastOrNull() == Route.Downloads) nav.push(Route.DownloadHistory) },
                    onOpenAccount = { nav.push(Route.Account) },
                    onOpenDomains = { nav.push(Route.DomainSettings) },
                )
            }
            entry<Route.DownloadHistory> {
                DownloadHistoryScreen(onBack = { nav.pop() }, onOpenBook = openBook)
            }
            entry<Route.MyBooklists> {
                MyBooklistsScreen(
                    onBack = { nav.pop() },
                    onOpenList = { id ->
                        if (nav.backStack.lastOrNull() == Route.MyBooklists) nav.push(Route.Booklist(id))
                    },
                    viewModel = booklists,
                )
            }
            entry<Route.Booklist> { route ->
                BooklistBooksScreen(
                    id = route.id,
                    onBack = { nav.pop() },
                    onOpenBook = openBook,
                    viewModel = booklists,
                )
            }
            entry<Route.Account> {
                AccountScreen(onBack = { nav.pop() })
            }
            entry<Route.DomainSettings> {
                DomainSettingsScreen(onBack = { nav.pop() })
            }
            entry<Route.About> {
                AboutScreen(
                    info = appInfo, icon = painterResource(R.drawable.ic_zhishi_foreground), onBack = { nav.pop() },
                    onOpenPage = { page -> if (nav.backStack.lastOrNull() == Route.About) nav.push(Route.AboutDocument(page.name)) },
                    onOpenChangelog = { if (nav.backStack.lastOrNull() == Route.About) nav.push(Route.ReleaseHistory) },
                    onCheckUpdate = { updates.checkUpdate(appInfo.versionName) },
                )
            }
            entry<Route.AboutDocument> { route ->
                val page = AboutPage.entries.firstOrNull { it.name == route.pageName } ?: AboutPage.Privacy
                AboutDocumentScreen(page = page, onBack = { nav.pop() })
            }
            entry<Route.ReleaseHistory> {
                ReleaseHistoryScreen(onBack = { nav.pop() })
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
    onOpenMyBooklists: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(ZlibTab.Popular.name) }
    val selectedTab = ZlibTab.entries.firstOrNull { it.name == tabName } ?: ZlibTab.Popular
    var librarySectionName by rememberSaveable { mutableStateOf(LibrarySection.Local.name) }
    val librarySection = LibrarySection.entries.firstOrNull { it.name == librarySectionName } ?: LibrarySection.Local
    var readingSourceName by rememberSaveable { mutableStateOf(ReadingSource.Popular.name) }
    val readingSource = ReadingSource.entries.firstOrNull { it.name == readingSourceName } ?: ReadingSource.Popular
    val tabStateHolder = rememberSaveableStateHolder()
    val popularGridState = rememberLazyGridState()
    val recommendedGridState = rememberLazyGridState()
    val readingGridState = when (readingSource) {
        ReadingSource.Popular -> popularGridState
        ReadingSource.Recommended -> recommendedGridState
    }
    val scope = rememberCoroutineScope()

    ZlibAppShell(
        selectedTab = selectedTab,
        onTabSelected = { tabName = it.name },
        onOpenSearch = onOpenSearch,
        onOpenDownloads = onOpenDownloads,
        onReadingDoubleTap = { scope.launch { readingGridState.animateScrollToItem(0) } },
        topBarContent = { tab ->
            when (tab) {
                ZlibTab.Popular -> ReadingTabs(selected = readingSource, onSelect = { readingSourceName = it.name })
                ZlibTab.Library -> LibraryTabs(selected = librarySection, onSelect = { librarySectionName = it.name })
                ZlibTab.Settings -> Unit
            }
        },
    ) { tab, padding ->
        tabStateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                ZlibTab.Popular -> PopularScreen(
                    padding = padding, onOpenBook = onOpenBook, source = readingSource, gridState = readingGridState,
                )
                ZlibTab.Library -> LibraryScreen(
                    padding = padding,
                    section = librarySection,
                    onOpenBook = onOpenBook,
                )
                ZlibTab.Settings -> SettingsScreen(
                    padding = padding, onOpenAccount = onOpenAccount,
                    onOpenMyBooklists = onOpenMyBooklists,
                    onOpenDomains = onOpenDomains, onOpenAbout = onOpenAbout,
                )
            }
        }
    }
}
