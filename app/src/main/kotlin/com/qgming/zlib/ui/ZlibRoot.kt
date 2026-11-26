package com.qgming.zlib.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.AppViewModel
import com.qgming.zlib.BuildConfig
import com.qgming.zlib.R
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.data.startup.StartupState
import com.qgming.zlib.core.designsystem.LocalZlibBookSnapshotsEnabled
import com.qgming.zlib.core.designsystem.ZlibAppOverlay
import com.qgming.zlib.core.designsystem.ZlibAppShell
import com.qgming.zlib.core.designsystem.ZlibBookOpenRequest
import com.qgming.zlib.core.designsystem.ZlibDirectoryWelcomeScreen
import com.qgming.zlib.core.designsystem.ZlibSplashScreen
import com.qgming.zlib.core.designsystem.ZlibStartupFailedScreen
import com.qgming.zlib.core.designsystem.ZlibTab
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.userMessage
import com.qgming.zlib.core.designsystem.zlibSurfaceColor
import com.qgming.zlib.domain.DomainVerificationActivity
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
import com.qgming.zlib.feature.settings.AboutAppInfo
import com.qgming.zlib.feature.settings.AboutDocumentScreen
import com.qgming.zlib.feature.settings.AboutPage
import com.qgming.zlib.feature.settings.AboutScreen
import com.qgming.zlib.feature.settings.AboutViewModel
import com.qgming.zlib.feature.settings.AppUpdateHost
import com.qgming.zlib.feature.settings.DomainSettingsScreen
import com.qgming.zlib.feature.settings.ReleaseHistoryScreen
import com.qgming.zlib.feature.settings.SettingsScreen
import com.qgming.zlib.navigation.BookDetailLauncher
import com.qgming.zlib.share.ShareImageActivity
import com.qgming.zlib.share.ShareImagePayload
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavController

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
    val domainChecks by viewModel.domainChecks.collectAsStateWithLifecycle()
    val domainProbing by viewModel.domainProbing.collectAsStateWithLifecycle()
    val recoveryBusy by viewModel.recoveryBusy.collectAsStateWithLifecycle()
    val choosingDirectory by viewModel.choosingDirectory.collectAsStateWithLifecycle()
    val currentThemeMode by rememberUpdatedState(settings.themeMode)
    val context = LocalContext.current
    val verificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.completeDomainVerification(
            host = result.data?.getStringExtra(DomainVerificationActivity.EXTRA_HOST),
            rawCookies = result.data?.getStringExtra(DomainVerificationActivity.EXTRA_COOKIE),
            completed = result.resultCode == Activity.RESULT_OK,
            verifiedNetwork = result.data?.getLongExtra(DomainVerificationActivity.EXTRA_NETWORK_HANDLE, -1L)?.takeIf { it >= 0 },
            userAgent = result.data?.getStringExtra(DomainVerificationActivity.EXTRA_USER_AGENT),
        )
    }
    LaunchedEffect(viewModel, verificationLauncher) {
        viewModel.verificationRequests.collect { host ->
            try {
                verificationLauncher.launch(DomainVerificationActivity.createIntent(context, host, currentThemeMode))
            } catch (_: android.content.ActivityNotFoundException) {
                viewModel.verificationOpenFailed()
            } catch (_: SecurityException) {
                viewModel.verificationOpenFailed()
            } catch (_: IllegalArgumentException) {
                viewModel.verificationOpenFailed()
            }
        }
    }
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
    val appLogo = painterResource(R.drawable.ic_zhishi_foreground)

    ZlibTheme(mode = settings.themeMode, bookGridColumns = settings.bookGridColumns) {
        ZlibAppOverlay {
            when (val state = startup) {
                StartupState.Loading -> ZlibSplashScreen(
                    appName = appInfo.name,
                    logo = appLogo,
                    statusText = stringResource(TextR.string.text_checking_available_domain),
                )

                StartupState.DirectoryRequired -> ZlibDirectoryWelcomeScreen(
                    logo = appLogo,
                    busy = choosingDirectory,
                    error = message,
                    onChoose = { directoryPicker.launch(null) },
                )

                is StartupState.Failed -> ZlibStartupFailedScreen(
                    logo = appLogo,
                    message = state.error.userMessage(),
                    actionError = message,
                    onRetry = viewModel::retry,
                    onAddHost = viewModel::addCustomHost,
                    lockedHost = settings.lockedHost,
                    checks = domainChecks,
                    busy = recoveryBusy || domainProbing,
                    onSelect = viewModel::chooseRecoveryHost,
                    onProbe = viewModel::probeRecoveryHost,
                    onVerify = viewModel::chooseRecoveryHost,
                )

                StartupState.Ready -> if (session == null) {
                    if (accounts.entries.isEmpty()) LoginScreen(
                        onVerifyDomain = viewModel::requestDomainVerification,
                        logo = appLogo,
                    )
                    else AccountScreen(onBack = null, onVerifyDomain = viewModel::requestDomainVerification)
                } else {
                    MainShell(appInfo, updates, downloadRequest, viewModel::requestDomainVerification)
                }
            }
            ZlibToastHost(viewModel.events, key = session?.remixUserId)
            if (startup is StartupState.Ready) AppUpdateHost(appInfo, updates)
        }
    }
}

@Composable
private fun MainShell(appInfo: AboutAppInfo, updates: AboutViewModel, downloadRequest: Int, onVerifyDomain: (String) -> Unit) {
    val context = LocalContext.current
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
                backdropColor = zlibSurfaceColor(),
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
                    onOpenAccount = { if (nav.backStack.lastOrNull() == Route.Downloads) nav.push(Route.Account) },
                    onOpenDomains = { if (nav.backStack.lastOrNull() == Route.Downloads) nav.push(Route.DomainSettings) },
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
                    onShare = { booklist, books, hasMore ->
                        ShareImageActivity.launch(context, ShareImagePayload.booklist(booklist, books, hasMore))
                    },
                    viewModel = booklists,
                )
            }
            entry<Route.Account> {
                AccountScreen(onBack = { nav.pop() }, onVerifyDomain = onVerifyDomain)
            }
            entry<Route.DomainSettings> {
                DomainSettingsScreen(onBack = { nav.pop() }, onVerifyDomain = onVerifyDomain)
            }
            entry<Route.About> {
                val updateState by updates.update.collectAsStateWithLifecycle()
                AboutScreen(
                    info = appInfo, icon = painterResource(R.drawable.ic_zhishi_foreground), onBack = { nav.pop() },
                    onOpenPage = { page -> if (nav.backStack.lastOrNull() == Route.About) nav.push(Route.AboutDocument(page.name)) },
                    onOpenChangelog = { if (nav.backStack.lastOrNull() == Route.About) nav.push(Route.ReleaseHistory) },
                    onCheckUpdate = { updates.checkUpdate(appInfo.versionName) },
                    checkingUpdate = updateState.checking,
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
