package com.qgming.zlib.feature.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibCurrentDomainCard
import com.qgming.zlib.core.designsystem.ZlibDomainPicker
import com.qgming.zlib.core.designsystem.ZlibLoginForm
import com.qgming.zlib.core.designsystem.ZlibPage

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ZlibPage(title = stringResource(com.qgming.zlib.core.common.R.string.zlib_login_title)) { padding ->
        ZlibCardList(padding) {
            item("domain") {
                ZlibCurrentDomainCard(state.domain, enabled = !state.loading, onChange = viewModel::showDomains)
            }
            item("form") {
                ZlibCard(cornerRadius = 24.dp) {
                    ZlibLoginForm(
                        useCookie = state.showCookieImport,
                        email = state.email, password = state.password, cookie = state.cookieText,
                        busy = state.loading, error = state.error,
                        onEmailChange = viewModel::onEmailChange, onPasswordChange = viewModel::onPasswordChange,
                        onCookieChange = viewModel::onCookieTextChange,
                        onSubmit = { if (state.showCookieImport) viewModel.importCookieText() else viewModel.login() },
                        onToggleCookie = viewModel::toggleCookieImport,
                    )
                }
            }
        }
        ZlibDomainPicker(
            show = state.showDomainPicker, checks = state.checks, currentHost = state.domain,
            busy = state.switchingDomain, error = state.domainError,
            onSelect = viewModel::selectDomain, onDismiss = viewModel::dismissDomains,
        )
    }
}
