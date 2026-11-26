package com.qgming.zlib.feature.settings

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibAddDomainButton
import com.qgming.zlib.core.designsystem.ZlibAddDomainForm
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibDomainAutoSwitch
import com.qgming.zlib.core.designsystem.ZlibDomainCard
import com.qgming.zlib.core.designsystem.ZlibDomainGroupHeading
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibProbeDomainsButton
import com.qgming.zlib.core.designsystem.ZlibSheetConfirmButton
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.model.DomainGroup

@Composable
fun DomainSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    onVerifyDomain: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.domainBusy.collectAsStateWithLifecycle()
    val form by viewModel.addDomain.collectAsStateWithLifecycle()
    var removeHost by remember { mutableStateOf<String?>(null) }
    val controlsEnabled = !busy && !state.probing

    LaunchedEffect(viewModel, onVerifyDomain) { viewModel.verificationRequests.collect(onVerifyDomain) }
    ZlibToastHost(viewModel.events)

    ZlibPage(
        title = stringResource(TextR.string.text_domain_management), onBack = onBack,
        actions = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ZlibProbeDomainsButton(enabled = controlsEnabled, onClick = viewModel::probeAll)
                ZlibAddDomainButton(onClick = viewModel::showAddDomain)
            }
        },
    ) { padding ->
        ZlibCardList(padding) {
            item("auto-switch") {
                ZlibDomainAutoSwitch(
                    checked = state.lockedHost == null,
                    enabled = controlsEnabled,
                    onChange = viewModel::setAutoSwitch,
                )
            }
            DomainGroup.entries.forEach { group ->
                val groupChecks = state.checks.filter { it.group == group }
                if (groupChecks.isNotEmpty()) {
                    item("group:$group") { ZlibDomainGroupHeading(group) }
                    items(groupChecks, key = { it.host }) { check ->
                        ZlibDomainCard(
                            check = check, current = check.host == state.currentHost, enabled = controlsEnabled,
                            onSelect = { viewModel.selectHost(check.host) },
                            onProbe = { viewModel.probeHost(check.host) },
                            onLongClick = if (check.host in state.customHosts) ({ removeHost = check.host }) else null,
                            onVerify = { viewModel.selectHost(check.host) },
                        )
                    }
                }
            }
        }
        ZlibGlassBottomSheet(
            show = form.visible,
            title = stringResource(TextR.string.text_add_domain),
            onDismiss = viewModel::dismissAddDomain,
            endAction = {
                ZlibSheetConfirmButton(
                    onClick = viewModel::addCustomHost,
                    enabled = !form.busy && form.host.isNotBlank(),
                    description = stringResource(TextR.string.text_add_domain),
                )
            },
        ) {
            ZlibAddDomainForm(
                host = form.host, busy = form.busy, error = form.error,
                onHostChange = viewModel::onDomainInput, onSubmit = viewModel::addCustomHost,
                showSubmitButton = false,
            )
        }
        ZlibConfirmDialog(
            show = removeHost != null, title = stringResource(TextR.string.text_delete_domain_2), summary = removeHost.orEmpty(),
            confirmLabel = stringResource(TextR.string.text_delete), destructive = true, onDismiss = { removeHost = null },
            onConfirm = { removeHost?.let(viewModel::removeCustomHost); removeHost = null },
        )
    }
}
