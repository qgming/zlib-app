package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibLoginForm(
    useCookie: Boolean,
    email: String,
    password: String,
    cookie: String,
    busy: Boolean,
    error: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onCookieChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleCookie: () -> Unit,
    enabled: Boolean = true,
    addingAccount: Boolean = false,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (useCookie) {
            TextField(
                value = cookie, onValueChange = onCookieChange, label = stringResource(TextR.string.text_cookie_content),
                minLines = 3, maxLines = 6, enabled = !busy && enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextField(
                value = email, onValueChange = onEmailChange, label = stringResource(TextR.string.text_email),
                singleLine = true, enabled = !busy && enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = password, onValueChange = onPasswordChange, label = stringResource(TextR.string.text_password),
                singleLine = true, enabled = !busy && enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (addingAccount) {
            Text(
                stringResource(TextR.string.text_sessions_are_encrypted_on_this_device_passwords_are_never_saved),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        error?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
        if (busy) ZlibLoadingStatus(stringResource(TextR.string.text_verifying_account))
        Button(
            onClick = onSubmit,
            enabled = enabled && !busy && if (useCookie) cookie.isNotBlank() else email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (addingAccount) stringResource(TextR.string.text_sign_in_and_add) else stringResource(TextR.string.text_sign_in))
        }
        Button(onClick = onToggleCookie, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (useCookie) stringResource(TextR.string.text_use_email_and_password) else stringResource(TextR.string.text_import_cookie))
        }
    }
}
