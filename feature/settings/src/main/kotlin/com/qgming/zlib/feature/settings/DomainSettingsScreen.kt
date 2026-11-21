package com.qgming.zlib.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.label
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibSectionTitle
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DomainSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    var customHost by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.events.collect { notice = it }
    }

    ZlibPage(title = "域名与网络", onBack = onBack) { padding ->
        ZlibCardList(padding) {
            notice?.let { message ->
                item {
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
                    Text("域名体检", style = MiuixTheme.textStyles.title4)
                    Text(
                        text = state.currentHost ?: "尚未选择域名",
                        style = MiuixTheme.textStyles.title4,
                    )
                    Text(
                        text = state.checks.firstOrNull { it.host == state.currentHost }?.status?.label() ?: "未检测",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (state.probing) {
                        ZlibLoadingStatus("正在体检域名…", Modifier.padding(vertical = 8.dp))
                    } else {
                        Button(onClick = viewModel::probeAll) { Text("重新体检可用域名") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("锁定当前域名", style = MiuixTheme.textStyles.body2)
                            Text(
                                text = "锁定时不会自动切换；域名失效需手动换。",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(
                            checked = state.lockedHost != null,
                            onCheckedChange = { viewModel.setLocked(it) },
                        )
                    }
                }
            }

            if (state.checks.isNotEmpty()) {
                item { ZlibSectionTitle("镜像列表") }
            }

            items(state.checks, key = { it.host }) { check ->
                ZlibCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = check.host + if (check.host == state.currentHost) "（当前）" else "",
                                style = MiuixTheme.textStyles.body2,
                            )
                            Text(
                                text = check.status.label() +
                                    if (check.detail.isBlank()) "" else " · ${check.detail}",
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (check.healthy) {
                                    MiuixTheme.colorScheme.primary
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (check.host != state.currentHost) {
                                TextButton(text = "切换", onClick = { viewModel.selectHost(check.host) })
                            }
                            if (check.host in state.customHosts) {
                                TextButton(text = "删除", onClick = { viewModel.removeCustomHost(check.host) })
                            }
                        }
                    }
                }
            }

            item {
                ZlibCard {
                    Text("添加自定义镜像", style = MiuixTheme.textStyles.title4)
                    Text(
                        text = "只接受 HTTPS 主机名，不能带端口、路径或 IP；只信任精确主机。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = customHost,
                        onValueChange = { customHost = it },
                        label = "例如 mirror.example.org",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.addCustomHost(customHost)
                        },
                        enabled = customHost.isNotBlank(),
                    ) {
                        Text("添加并体检")
                    }
                }
            }

            item {
                Text(
                    text = "所有请求仅发往允许列表内的 HTTPS 域名；跨主机重定向会剥离 Cookie 与 remix 凭据。",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
