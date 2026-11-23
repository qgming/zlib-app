package com.qgming.zlib.feature.settings

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
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibProbeDomainsButton
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState

@Composable
fun DomainSettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.domainBusy.collectAsStateWithLifecycle()
    val form by viewModel.addDomain.collectAsStateWithLifecycle()
    val toast = rememberZlibToastHostState()
    var actionHost by remember { mutableStateOf<String?>(null) }
    var removeHost by remember { mutableStateOf<String?>(null) }
    val controlsEnabled = !busy && !state.probing

    LaunchedEffect(viewModel, toast) { viewModel.events.collect { toast.showMessage(it) } }
    ZlibToastHost(toast)

    ZlibPage(
        title = "域名管理", onBack = onBack,
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
            items(state.checks, key = { it.host }) { check ->
                ZlibDomainCard(
                    check = check, current = check.host == state.currentHost, enabled = controlsEnabled,
                    onSelect = { viewModel.selectHost(check.host) },
                    onProbe = { viewModel.probeHost(check.host) },
                    onLongClick = if (check.host in state.customHosts) ({ actionHost = check.host }) else null,
                )
            }
        }
        ZlibGlassBottomSheet(show = form.visible, title = "添加域名", onDismiss = viewModel::dismissAddDomain) {
            ZlibAddDomainForm(
                host = form.host, busy = form.busy, error = form.error,
                onHostChange = viewModel::onDomainInput, onSubmit = viewModel::addCustomHost,
            )
        }
        ZlibGlassBottomSheet(show = actionHost != null, title = "域名操作", onDismiss = { actionHost = null }) {
            ZlibMenuAction("删除域名", enabled = controlsEnabled) { removeHost = actionHost; actionHost = null }
        }
        ZlibConfirmDialog(
            show = removeHost != null, title = "删除域名？", summary = removeHost.orEmpty(),
            confirmLabel = "删除", destructive = true, onDismiss = { removeHost = null },
            onConfirm = { removeHost?.let(viewModel::removeCustomHost); removeHost = null },
        )
    }
}
