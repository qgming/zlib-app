package com.qgming.zlib.feature.settings

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

private val ThemeOptions = listOf(
    ThemeMode.SYSTEM to "跟随系统",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)
private val ThemeOptionLabels = ThemeOptions.map { it.second }
private val GridOptions = listOf(
    BookGridColumns.AUTO to "自动",
    BookGridColumns.TWO to "2 列",
    BookGridColumns.THREE to "3 列",
    BookGridColumns.FOUR to "4 列",
    BookGridColumns.FIVE to "5 列",
)
private val GridOptionLabels = GridOptions.map { it.second }

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenAccount: () -> Unit,
    onOpenDomains: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
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
                ZlibStateCard("设置提示", message, actionLabel = "知道了", onAction = { notice = null })
            }
        }
        item(key = "account") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = state.account?.name?.takeIf(String::isNotBlank)
                        ?: if (state.account == null) "Z-Library 账号" else "Z-Library 用户",
                    summary = state.account?.email?.takeIf(String::isNotBlank)
                        ?: if (state.account == null) "查看账号与下载额度" else "未提供邮箱",
                    onClick = onOpenAccount,
                )
            }
        }
        item(key = "appearance") {
            ZlibPreferenceGroup {
                ZlibPreferenceMenu(
                    title = "外观模式",
                    options = ThemeOptionLabels,
                    selectedIndex = ThemeOptions.indexOfFirst { it.first == state.themeMode },
                    onSelect = { index -> viewModel.setThemeMode(ThemeOptions[index].first) },
                )
                ZlibPreferenceMenu(
                    title = "网络格数",
                    options = GridOptionLabels,
                    selectedIndex = GridOptions.indexOfFirst { it.first == state.bookGridColumns },
                    onSelect = { index -> viewModel.setBookGridColumns(GridOptions[index].first) },
                )
            }
        }
        item(key = "downloads") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = "下载目录",
                    summary = state.directory ?: "选择保存图书的文件夹",
                    onClick = { directoryPicker.launch(null) },
                )
            }
        }
        item(key = "domains") {
            ZlibPreferenceGroup {
                ZlibPreferenceLink(
                    title = "域名管理",
                    summary = state.currentHost ?: "检测和管理镜像",
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
