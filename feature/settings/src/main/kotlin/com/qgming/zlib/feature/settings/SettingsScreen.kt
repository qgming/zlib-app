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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibPreferenceDivider
import com.qgming.zlib.core.designsystem.ZlibPreferenceGroup
import com.qgming.zlib.core.designsystem.ZlibPreferenceIcon
import com.qgming.zlib.core.designsystem.ZlibPreferenceLink
import com.qgming.zlib.core.designsystem.ZlibPreferenceMenu
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.model.ThemeMode

private val ThemeOptions = listOf(
    ThemeMode.SYSTEM to "跟随系统",
    ThemeMode.LIGHT to "浅色",
    ThemeMode.DARK to "深色",
)
private val ThemeOptionLabels = ThemeOptions.map { it.second }

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    onOpenAccount: () -> Unit,
    onOpenDomains: () -> Unit,
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
            ZlibPreferenceGroup("账号") {
                ZlibPreferenceLink(
                    title = "Z-Library 账号",
                    summary = state.account?.name?.takeIf(String::isNotBlank) ?: "查看账号与下载额度",
                    icon = ZlibPreferenceIcon.Account,
                    onClick = onOpenAccount,
                )
            }
        }
        item(key = "downloads-network") {
            ZlibPreferenceGroup("下载与网络") {
                ZlibPreferenceLink(
                    title = "下载目录",
                    summary = state.directory ?: "选择保存图书的文件夹",
                    icon = ZlibPreferenceIcon.Downloads,
                    onClick = { directoryPicker.launch(null) },
                )
                ZlibPreferenceDivider()
                ZlibPreferenceLink(
                    title = "域名与网络",
                    summary = state.currentHost ?: "检测和管理镜像",
                    icon = ZlibPreferenceIcon.Domain,
                    onClick = onOpenDomains,
                )
            }
        }
        item(key = "appearance") {
            ZlibPreferenceGroup("外观") {
                ZlibPreferenceMenu(
                    title = "外观模式",
                    icon = ZlibPreferenceIcon.Color,
                    options = ThemeOptionLabels,
                    selectedIndex = ThemeOptions.indexOfFirst { it.first == state.themeMode },
                    onSelect = { index -> viewModel.setThemeMode(ThemeOptions[index].first) },
                )
            }
        }
    }
}
