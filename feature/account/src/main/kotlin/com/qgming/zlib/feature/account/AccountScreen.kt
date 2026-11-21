package com.qgming.zlib.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.label
import com.qgming.zlib.core.designsystem.ZlibCard
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AccountScreen(
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { notice = it }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            item {
                notice?.let { message ->
                    ZlibCard {
                        Text(
                            text = message,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        TextButton(text = "知道了", onClick = { notice = null })
                    }
                }
            }

            item {
                ZlibCard {
                    val account = state.account
                    if (account == null) {
                        if (state.error != null) {
                            Text(
                                text = state.error!!,
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(6.dp))
                            TextButton(text = "重试", onClick = viewModel::refresh)
                        } else {
                            Text("正在加载账号信息…", style = MiuixTheme.textStyles.body2)
                        }
                    } else {
                        Text(
                            text = account.name.ifBlank { "Z-Library 用户" },
                            style = MiuixTheme.textStyles.title3,
                        )
                        Text(
                            text = account.email.ifBlank { "邮箱未提供" },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (account.isPremium) "Premium 会员" else "标准账号",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                        )
                        Text(
                            text = account.quota.displayText(),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = viewModel::refresh,
                            enabled = !state.loading,
                        ) {
                            Text(if (state.loading) "刷新中…" else "刷新")
                        }
                        TextButton(text = "退出登录", onClick = { confirmLogout = true })
                    }
                }
            }

            item {
                ZlibCard {
                    Text("当前域名", style = MiuixTheme.textStyles.body2)
                    Text(
                        text = state.currentHost ?: "未选择",
                        style = MiuixTheme.textStyles.title4,
                    )
                    val currentCheck = state.checks.firstOrNull { it.host == state.currentHost }
                    Text(
                        text = currentCheck?.status?.label() ?: "未检测",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (currentCheck?.healthy == true) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            text = if (state.probing) "体检中…" else "立即体检",
                            onClick = viewModel::probeDomains,
                            enabled = !state.probing,
                        )
                        TextButton(text = "域名与设置", onClick = onOpenSettings)
                    }
                }
            }

            items(state.checks.filter { it.host != state.currentHost }) { check ->
                ZlibCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(check.host, style = MiuixTheme.textStyles.body2)
                            Text(
                                text = check.status.label() + if (check.detail.isBlank()) "" else " · ${check.detail}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (check.healthy) {
                            TextButton(text = "切换", onClick = { viewModel.switchDomain(check.host) })
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "会话使用 Android Keystore 加密保存；退出登录会清除会话与 WebView Cookie。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }

    OverlayDialog(
        show = confirmLogout,
        title = "退出登录",
        summary = "将清空加密会话与 WebView Cookie，本地已下载文件不受影响。",
        onDismissRequest = { confirmLogout = false },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(text = "取消", onClick = { confirmLogout = false })
            Button(onClick = {
                confirmLogout = false
                viewModel.logout()
            }) {
                Text("退出")
            }
        }
    }
}

private fun com.qgming.zlib.core.model.Quota.displayText(): String = if (!known) {
    "下载额度未知"
} else {
    "今日下载 ${amount ?: 0}/${allowed ?: 0}，剩余 ${remaining ?: 0}"
}
