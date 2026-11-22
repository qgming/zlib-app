package com.qgming.zlib.core.designsystem

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
                value = cookie, onValueChange = onCookieChange, label = "Cookie 内容",
                minLines = 3, maxLines = 6, enabled = !busy && enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            TextField(
                value = email, onValueChange = onEmailChange, label = "邮箱",
                singleLine = true, enabled = !busy && enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = password, onValueChange = onPasswordChange, label = "密码",
                singleLine = true, enabled = !busy && enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (addingAccount) {
            Text(
                "登录状态仅在本机加密保存，密码不会保存。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        error?.let {
            Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.error)
        }
        if (busy) ZlibLoadingStatus("正在验证账号…")
        Button(
            onClick = onSubmit,
            enabled = enabled && !busy && if (useCookie) cookie.isNotBlank() else email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (addingAccount) "登录并添加" else "登录")
        }
        Button(onClick = onToggleCookie, enabled = enabled && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (useCookie) "使用邮箱和密码登录" else "使用 Cookie 导入")
        }
    }
}
