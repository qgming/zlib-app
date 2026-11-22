package com.qgming.zlib.feature.account

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
import com.qgming.zlib.core.designsystem.ZlibSnackbarHost
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.rememberZlibSnackbarHostState

@Composable
fun AccountScreen(onBack: (() -> Unit)?, viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val busy by viewModel.busyId.collectAsStateWithLifecycle()
    var actionId by remember { mutableStateOf<String?>(null) }
    var logoutId by remember { mutableStateOf<String?>(null) }
    var confirmAutoSwitch by remember { mutableStateOf(false) }
    val snackbar = rememberZlibSnackbarHostState()
    LaunchedEffect(viewModel, snackbar) { viewModel.events.collect { snackbar.showMessage(it) } }

    ZlibPage(title = "账号", onBack = onBack, actions = { ZlibAddAccountButton(viewModel::showAdd) }, snackbarHost = { ZlibSnackbarHost(snackbar) }) { padding ->
        ZlibCardList(padding) {
            if (state.accounts.isEmpty()) item("empty") {
                ZlibStateCard("暂无已登录账号", "点击右上角添加账号。", "添加账号", viewModel::showAdd)
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
        ZlibGlassBottomSheet(show = actionId != null, title = "账号操作", onDismiss = { actionId = null }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ZlibMenuAction("刷新账号", enabled = busy == null) { actionId?.let(viewModel::refresh); actionId = null }
                ZlibMenuAction("退出登录", enabled = busy == null) { logoutId = actionId; actionId = null }
            }
        }
        ZlibConfirmDialog(
            show = logoutId != null, title = "退出该账号？",
            summary = "清除该账号的登录会话。本机图书和下载任务保留，当前下载会暂停。",
            confirmLabel = "退出登录", destructive = true, onDismiss = { logoutId = null },
            onConfirm = { logoutId?.let(viewModel::logout); logoutId = null },
        )
        ZlibConfirmDialog(
            show = confirmAutoSwitch, title = "开启额度耗尽自动切换？",
            summary = "仅在服务器确认额度已耗尽时，尝试切换至仍有额度的已登录账号。平台可能限制多账号使用，无法保证不会触发验证或封禁。",
            confirmLabel = "开启", onDismiss = { confirmAutoSwitch = false },
            onConfirm = { viewModel.setAutoSwitch(true); confirmAutoSwitch = false },
        )
        ZlibGlassBottomSheet(show = form.visible, title = "添加账号", onDismiss = viewModel::dismissAdd) {
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
