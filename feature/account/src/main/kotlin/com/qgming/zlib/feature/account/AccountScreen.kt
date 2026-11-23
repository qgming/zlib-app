package com.qgming.zlib.feature.account

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibAccountCard
import com.qgming.zlib.core.designsystem.ZlibAddAccountButton
import com.qgming.zlib.core.designsystem.ZlibAutoSwitchPreference
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibConfirmDialog
import com.qgming.zlib.core.designsystem.ZlibGlassBottomSheet
import com.qgming.zlib.core.designsystem.ZlibLoginForm
import com.qgming.zlib.core.designsystem.ZlibMenuAction
import com.qgming.zlib.core.designsystem.ZlibPage
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState

@Composable
fun AccountScreen(onBack: (() -> Unit)?, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val busy by viewModel.busyId.collectAsStateWithLifecycle()
    var actionId by remember { mutableStateOf<String?>(null) }
    var logoutId by remember { mutableStateOf<String?>(null) }
    var confirmAutoSwitch by remember { mutableStateOf(false) }
    val toast = rememberZlibToastHostState()
    LaunchedEffect(viewModel, toast) { viewModel.events.collect { toast.showMessage(it) } }
    ZlibToastHost(toast)

    ZlibPage(title = stringResource(TextR.string.text_account), onBack = onBack, actions = { ZlibAddAccountButton(viewModel::showAdd) }) { padding ->
        ZlibCardList(padding) {
            if (state.accounts.isEmpty()) item("empty") {
                ZlibStateCard(stringResource(TextR.string.text_no_signed_in_accounts), stringResource(TextR.string.text_tap_the_button_at_the_top_right_to_add_an_account), stringResource(TextR.string.text_add_account), viewModel::showAdd)
            }
            items(state.accounts, key = { it.id }) { entry ->
                ZlibAccountCard(entry, busy != null,
                    onClick = { if (!entry.current || entry.expired) viewModel.switch(entry.id) },
                    onLongClick = { actionId = entry.id },
                )
            }
            if (state.accounts.isNotEmpty()) item("auto-switch") {
                ZlibAutoSwitchPreference(state.autoSwitch) { enabled ->
                    if (enabled) confirmAutoSwitch = true else viewModel.setAutoSwitch(false)
                }
            }
        }
        ZlibGlassBottomSheet(show = actionId != null, title = stringResource(TextR.string.text_account_actions), onDismiss = { actionId = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZlibMenuAction(stringResource(TextR.string.text_refresh_account), enabled = busy == null) { actionId?.let(viewModel::refresh); actionId = null }
                ZlibMenuAction(stringResource(TextR.string.text_sign_out), enabled = busy == null) { logoutId = actionId; actionId = null }
            }
        }
        ZlibConfirmDialog(
            show = logoutId != null, title = stringResource(TextR.string.text_sign_out_of_this_account),
            summary = stringResource(TextR.string.text_remove_this_account_s_session_local_books_and_download_tasks_are_kept_ac),
            confirmLabel = stringResource(TextR.string.text_sign_out), destructive = true, onDismiss = { logoutId = null },
            onConfirm = { logoutId?.let(viewModel::logout); logoutId = null },
        )
        ZlibConfirmDialog(
            show = confirmAutoSwitch, title = stringResource(TextR.string.text_switch_accounts_when_quota_is_exhausted),
            summary = stringResource(TextR.string.text_only_switch_after_the_server_confirms_an_exhausted_quota_and_only_to_sig),
            confirmLabel = stringResource(TextR.string.text_enable), onDismiss = { confirmAutoSwitch = false },
            onConfirm = { viewModel.setAutoSwitch(true); confirmAutoSwitch = false },
        )
        ZlibGlassBottomSheet(show = form.visible, title = stringResource(TextR.string.text_add_account), onDismiss = viewModel::dismissAdd) {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZlibLoginForm(
                    useCookie = form.useCookie, email = form.email, password = form.password,
                    cookie = form.cookie, busy = form.busy, error = form.error,
                    enabled = busy == null, addingAccount = true,
                    onEmailChange = viewModel::email, onPasswordChange = viewModel::password,
                    onCookieChange = viewModel::cookie, onSubmit = viewModel::add,
                    onToggleCookie = viewModel::toggleCookie,
                )
            }
        }
    }
}
