package com.qgming.zlib.feature.account

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.model.Quota
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AccountScreen(
    onBack: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }

    ZlibPage(title = "账号", onBack = onBack) { padding ->
        ZlibCardList(padding) {
            state.account?.let { account ->
                item(key = "profile") {
                    ZlibCard {
                        Text(account.name.ifBlank { "Z-Library 用户" }, style = MiuixTheme.textStyles.title3)
                        if (account.email.isNotBlank()) {
                            Text(
                                text = account.email,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(if (account.isPremium) "Premium 会员" else "标准账号", style = MiuixTheme.textStyles.footnote1)
                    }
                }
                item(key = "quota") {
                    ZlibCard {
                        Text("下载额度", style = MiuixTheme.textStyles.title4)
                        Spacer(Modifier.height(8.dp))
                        Text(account.quota.displayText(), style = MiuixTheme.textStyles.body2)
                        Spacer(Modifier.height(8.dp))
                        if (state.loading) {
                            ZlibLoadingStatus("正在刷新额度…", Modifier.padding(vertical = 8.dp))
                        } else {
                            TextButton(text = "刷新额度", onClick = viewModel::refresh)
                        }
                    }
                }
            }
            if (state.account == null && state.error == null) {
                item(key = "loading") { ZlibLoading() }
            }
            state.error?.let { message ->
                item(key = "error") {
                    ZlibStateCard("无法刷新账号", message, onAction = viewModel::refresh)
                }
            }
            item(key = "logout") {
                ZlibCard {
                    ZlibSectionTitle("登录状态")
                    TextButton(text = "退出登录", onClick = { confirmLogout = true })
                }
            }
        }

        ZlibConfirmDialog(
            show = confirmLogout,
            title = "退出登录",
            summary = "将清除登录会话和 WebView Cookie，不影响本地已下载的图书。",
            confirmLabel = "退出登录",
            onDismiss = { confirmLogout = false },
            onConfirm = {
                confirmLogout = false
                viewModel.logout()
            },
            destructive = true,
        )
    }
}

private fun Quota.displayText(): String = if (known) {
    "今日已下载 ${amount ?: 0} / $allowed 本，剩余 ${remaining ?: 0} 本"
} else {
    "服务器未提供今日下载额度"
}
