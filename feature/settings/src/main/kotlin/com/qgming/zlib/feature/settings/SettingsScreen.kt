package com.qgming.zlib.feature.settings

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibAboutGroup
import com.qgming.zlib.core.designsystem.ZlibPreferenceGroup
import com.qgming.zlib.core.designsystem.ZlibPreferenceLink
import com.qgming.zlib.core.designsystem.ZlibPreferenceMenu
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.common.AppLanguage
import com.qgming.zlib.core.common.ApplicationLanguages

@Composable
private fun themeOptions() = listOf(
    ThemeMode.SYSTEM to stringResource(TextR.string.text_follow_system),
    ThemeMode.LIGHT to stringResource(TextR.string.text_light),
    ThemeMode.DARK to stringResource(TextR.string.text_dark),
)
@Composable
private fun gridOptions() = listOf(
    BookGridColumns.AUTO to stringResource(TextR.string.text_automatic),
    BookGridColumns.TWO to stringResource(TextR.string.text_2_columns),
    BookGridColumns.THREE to stringResource(TextR.string.text_3_columns),
    BookGridColumns.FOUR to stringResource(TextR.string.text_4_columns),
    BookGridColumns.FIVE to stringResource(TextR.string.text_5_columns),
)

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenAccount: () -> Unit,
    onOpenMyBooklists: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeOptions = themeOptions()
    val gridOptions = gridOptions()
    val context = LocalContext.current
    val languageManager = remember(context) { ApplicationLanguages(context) }
    val configuration = LocalConfiguration.current
    val language = remember(configuration) { languageManager.selected }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::setDownloadDirectory)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { notice = it }
    }

    ZlibCardList(padding) {
        notice?.let { message ->
            item(key = "notice") {
                ZlibStateCard(appText(TextR.string.text_settings_notice), message, actionLabel = appText(TextR.string.text_ok), onAction = { notice = null })
            }
        }
        item(key = "account") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = state.account?.name?.takeIf(String::isNotBlank)
                        ?: if (state.account == null) stringResource(TextR.string.text_z_library_account) else stringResource(TextR.string.text_z_library_user),
                    summary = state.account?.email?.takeIf(String::isNotBlank)
                        ?: if (state.account == null) stringResource(TextR.string.text_view_accounts_and_download_quota) else stringResource(TextR.string.text_no_email_provided),
                    onClick = onOpenAccount,
                )
            }
        }
        item(key = "my-booklists") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = stringResource(TextR.string.text_my_booklists),
                    onClick = onOpenMyBooklists,
                )
            }
        }
        item(key = "appearance") {
            ZlibPreferenceGroup {
                ZlibPreferenceMenu(
                    title = stringResource(TextR.string.text_theme_mode),
                    options = themeOptions.map { it.second },
                    selectedIndex = themeOptions.indexOfFirst { it.first == state.themeMode },
                    onSelect = { index -> viewModel.setThemeMode(themeOptions[index].first) },
                )
                ZlibPreferenceMenu(
                    title = stringResource(TextR.string.text_app_language),
                    options = listOf(stringResource(TextR.string.text_follow_system), stringResource(TextR.string.text_simplified_chinese), stringResource(TextR.string.text_english)),
                    selectedIndex = AppLanguage.entries.indexOf(language),
                    onSelect = { index -> languageManager.select(AppLanguage.entries[index]) },
                )
                ZlibPreferenceMenu(
                    title = stringResource(TextR.string.text_grid_columns),
                    options = gridOptions.map { it.second },
                    selectedIndex = gridOptions.indexOfFirst { it.first == state.bookGridColumns },
                    onSelect = { index -> viewModel.setBookGridColumns(gridOptions[index].first) },
                )
            }
        }
        item(key = "downloads") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = stringResource(TextR.string.text_download_folder),
                    summary = state.directory ?: stringResource(TextR.string.text_choose_a_folder_for_your_books),
                    onClick = { directoryPicker.launch(null) },
                )
            }
        }
        item(key = "domains") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = stringResource(TextR.string.text_domain_management),
                    summary = state.currentHost ?: stringResource(TextR.string.text_check_and_manage_mirrors),
                    onClick = onOpenDomains,
                )
            }
        }
        item(key = "about") {
            ZlibAboutGroup {
                ZlibPreferenceLink(
                    title = stringResource(R.string.about_title),
                    onClick = onOpenAbout,
                )
            }
        }
    }
}
