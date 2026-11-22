package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.model.DomainCheck
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Replace
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibChangeDomainButton(enabled: Boolean, onClick: () -> Unit) {
    DomainIconButton(MiuixIcons.Glass.Normal.Replace, "更换域名", enabled, onClick)
}

@Composable
fun ZlibCurrentDomainCard(host: String?, enabled: Boolean, onChange: () -> Unit) {
    ZlibCard(cornerRadius = 24.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("当前域名", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(host ?: "选择域名", style = MiuixTheme.textStyles.body2)
            }
            ZlibChangeDomainButton(enabled, onChange)
        }
    }
}

@Composable
fun ZlibDomainAutoSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ZlibCard(cornerRadius = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("自动切换域名", style = MiuixTheme.textStyles.body2)
                Text(
                    "当前域名不可用时切换至可用域名",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
fun ZlibDomainCard(
    check: DomainCheck,
    current: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onProbe: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val interaction = if (onLongClick != null) Modifier.combinedClickable(
        enabled = enabled, role = Role.Button,
        onClickLabel = "切换到 ${check.host}", onLongClickLabel = "域名操作",
        onClick = onSelect, onLongClick = onLongClick,
    ) else Modifier
    ZlibCard(
        modifier = interaction.semantics { selected = current },
        cornerRadius = 24.dp,
        onClick = if (onLongClick == null && enabled) onSelect else null,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(check.host, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (current) "当前使用 · " else "") + check.status.label(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (current || check.healthy) colors.primary else colors.onSurfaceVariantSummary,
                )
                if (check.detail.isNotBlank()) {
                    Text(check.detail, style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            DomainIconButton(
                icon = if (current) MiuixIcons.Glass.Normal.Ok else MiuixIcons.Glass.Normal.Replace,
                description = if (current) "当前域名 ${check.host}" else "切换到 ${check.host}",
                enabled = enabled && !current, onClick = onSelect,
            )
            if (onProbe != null) {
                if (check.status == DomainStatus.PROBING) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { ZlibLoadingIndicator() }
                } else {
                    DomainIconButton(MiuixIcons.Glass.Normal.Refresh, "体检 ${check.host}", enabled, onProbe)
                }
            }
        }
    }
}

@Composable
fun ZlibDomainPicker(
    show: Boolean,
    checks: List<DomainCheck>,
    currentHost: String?,
    busy: Boolean,
    error: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ZlibGlassBottomSheet(show = show, title = "更换域名", onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
            if (busy) ZlibLoadingStatus("正在切换域名…")
            checks.forEach { check ->
                ZlibDomainCard(check, check.host == currentHost, enabled = !busy, onSelect = { onSelect(check.host) })
            }
        }
    }
}

@Composable
fun ZlibAddDomainForm(host: String, busy: Boolean, error: String?, onHostChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextField(
            value = host, onValueChange = onHostChange, label = "域名，如 mirror.example.org",
            singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "请填写你信任的 HTTPS 域名，不含路径、端口或 IP。",
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        error?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
        if (busy) ZlibLoadingStatus("正在添加并体检…")
        Button(onClick = onSubmit, enabled = !busy && host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("添加域名")
        }
    }
}

@Composable
private fun DomainIconButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp), backgroundColor = Color.Transparent) {
        Icon(icon, description, modifier = Modifier.size(24.dp),
            tint = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
