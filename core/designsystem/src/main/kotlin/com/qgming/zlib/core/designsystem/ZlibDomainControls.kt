package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.model.DomainCheck
import com.qgming.zlib.core.model.DomainGroup
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.icon.glass.Replace
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibChangeDomainButton(enabled: Boolean, onClick: () -> Unit) {
    DomainIconButton(MiuixIcons.Glass.Normal.Replace, stringResource(TextR.string.text_switch_domain), enabled, onClick)
}

@Composable
fun ZlibCurrentDomainCard(host: String?, enabled: Boolean, onChange: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    ZlibCard(
        cornerRadius = 24.dp,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        onClick = if (enabled) onChange else null,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(TextR.string.text_current_domain),
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                )
                Text(
                    text = host ?: stringResource(TextR.string.text_choose_domain),
                    style = MiuixTheme.textStyles.body2.copy(fontWeight = FontWeight.Medium),
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(colors.primary.copy(alpha = if (enabled) 0.12f else 0.06f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Normal.Replace,
                        contentDescription = null,
                        tint = if (enabled) colors.primary else colors.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(TextR.string.text_switch_domain),
                        style = MiuixTheme.textStyles.footnote1.copy(fontWeight = FontWeight.Medium),
                        color = if (enabled) colors.primary else colors.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun ZlibDomainAutoSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    ZlibCard(cornerRadius = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(stringResource(TextR.string.text_switch_domains_automatically), style = MiuixTheme.textStyles.body2)
                Text(
                    stringResource(TextR.string.text_switch_to_an_available_domain_when_the_current_one_fails),
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
    onVerify: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colorScheme
    val needsVerification = check.status == DomainStatus.DIAMWALL || check.status == DomainStatus.CHALLENGED
    val canActivate = enabled && (!current || needsVerification)
    val action = if (needsVerification && onVerify != null) onVerify else onSelect
    val actionDescription = if (needsVerification) stringResource(TextR.string.text_verify_domain, check.host)
        else stringResource(TextR.string.text_switch_to, check.host)
    val interaction = if (onLongClick != null) Modifier.combinedClickable(
        enabled = enabled, role = Role.Button,
        onClickLabel = if (canActivate) actionDescription else stringResource(TextR.string.text_current_domain_2, check.host),
        onLongClickLabel = stringResource(TextR.string.text_domain_actions),
        onClick = { if (canActivate) action() },
        onLongClick = onLongClick,
    ) else Modifier
    ZlibCard(
        modifier = interaction.semantics { selected = current },
        cornerRadius = 24.dp,
        onClick = if (onLongClick == null && canActivate) action else null,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(check.host, style = MiuixTheme.textStyles.body2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val statusText = buildString {
                    if (current) append(stringResource(TextR.string.text_active_2))
                    append(check.status.label())
                    if (check.healthy && check.latencyMs != null) {
                        append(" · ")
                        append(check.latencyMs)
                        append(" ms")
                    }
                }
                Text(
                    statusText,
                    style = MiuixTheme.textStyles.footnote1,
                    color = if (current || check.healthy) colors.primary else colors.onSurfaceVariantSummary,
                )
                if (check.detail.isNotBlank()) {
                    Text(check.detail, style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            DomainIconButton(
                icon = if (current && !needsVerification) MiuixIcons.Glass.Normal.Ok else MiuixIcons.Glass.Normal.Replace,
                description = if (current && !needsVerification) stringResource(TextR.string.text_current_domain_2, check.host) else actionDescription,
                enabled = enabled && (!current || needsVerification), onClick = action,
            )
            if (onProbe != null) {
                if (check.status == DomainStatus.PROBING) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { ZlibLoadingIndicator() }
                } else {
                    DomainIconButton(MiuixIcons.Glass.Normal.Refresh, stringResource(TextR.string.text_check, check.host), enabled, onProbe)
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
    onProbe: ((String) -> Unit)? = null,
    onVerify: ((String) -> Unit)? = null,
) {
    ZlibGlassBottomSheet(show = show, title = stringResource(TextR.string.text_switch_domain), onDismiss = onDismiss) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item("error") { Text(error, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error) }
            }
            if (busy) {
                item("loading") { ZlibLoadingStatus(stringResource(TextR.string.text_switching_domain)) }
            }
            DomainGroup.entries.forEach { group ->
                val groupChecks = checks.filter { it.group == group }
                if (groupChecks.isNotEmpty()) {
                    item("group:$group") { ZlibDomainGroupHeading(group) }
                    items(groupChecks, key = { it.host }) { check ->
                        ZlibDomainCard(
                            check = check, current = check.host == currentHost, enabled = !busy,
                            onSelect = { onSelect(check.host) },
                            onProbe = onProbe?.let { probe -> { probe(check.host) } },
                            onVerify = onVerify?.let { verify -> { verify(check.host) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ZlibAddDomainForm(
    host: String,
    busy: Boolean,
    error: String?,
    onHostChange: (String) -> Unit,
    onSubmit: () -> Unit,
    showSubmitButton: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("form") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ZlibTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = stringResource(TextR.string.text_domain_e_g_mirror_example_org),
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        onDone = { if (!busy && host.isNotBlank()) onSubmit() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        stringResource(TextR.string.text_enter_a_trusted_https_hostname_without_a_path_port_or_ip_address),
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        stringResource(TextR.string.text_optional_mirror_credential_warning),
                        style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    error?.let { ZlibInlineErrorBanner(it) }
                    if (busy) ZlibLoadingStatus(stringResource(TextR.string.text_adding_and_checking))
                }
            }
        }
        if (showSubmitButton) {
            Button(
                onClick = onSubmit,
                enabled = !busy && host.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(stringResource(TextR.string.text_add_domain), color = MiuixTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
fun ZlibDomainGroupHeading(group: DomainGroup) {
    ZlibSectionTitle(stringResource(when (group) {
        DomainGroup.OFFICIAL -> TextR.string.text_official_mirrors
        DomainGroup.COMMUNITY -> TextR.string.text_community_mirrors
    }))
}

@Composable
fun ZlibDomainVerificationPage(
    title: String,
    refreshDescription: String,
    doneDescription: String,
    refreshEnabled: Boolean,
    doneEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDone: () -> Unit,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    CompositionLocalProvider(LocalZlibGlass provides false) {
        ZlibPage(
            title = title,
            onBack = onBack,
            actions = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TopBarButton(
                        icon = MiuixIcons.Glass.Normal.Refresh,
                        description = refreshDescription,
                        onClick = onRefresh,
                        glass = false,
                        enabled = refreshEnabled,
                        tint = if (refreshEnabled) colors.onSurface else colors.onSurfaceVariantSummary,
                    )
                    TopBarButton(
                        icon = MiuixIcons.Glass.Normal.Ok,
                        description = doneDescription,
                        onClick = onDone,
                        glass = false,
                        enabled = doneEnabled,
                        tint = if (doneEnabled) colors.primary else colors.onSurfaceVariantSummary,
                    )
                }
            },
            contentGutters = false,
        ) { padding ->
            Box(Modifier.fillMaxSize()) { content(padding) }
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
