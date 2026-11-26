package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Hide
import top.yukonga.miuix.kmp.icon.glass.LockOutline
import top.yukonga.miuix.kmp.icon.glass.Show
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val PasswordMaskTransformation = OutputTransformation {
    for (i in 0 until length) {
        replace(i, i + 1, "•")
    }
}

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
    val colors = MiuixTheme.colorScheme
    val canEdit = enabled && !busy
    val canSubmit = canEdit && if (useCookie) cookie.isNotBlank() else email.isNotBlank() && password.isNotBlank()
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (useCookie) {
            ZlibTextField(
                value = cookie,
                onValueChange = onCookieChange,
                label = stringResource(TextR.string.text_cookie_content),
                singleLine = false,
                minLines = 4,
                maxLines = 7,
                enabled = canEdit,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ZlibTextField(
                value = email,
                onValueChange = onEmailChange,
                label = stringResource(TextR.string.text_email),
                singleLine = true,
                enabled = canEdit,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            ZlibTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = stringResource(TextR.string.text_password),
                singleLine = true,
                enabled = canEdit,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                onDone = { if (canSubmit) onSubmit() },
                outputTransformation = if (passwordVisible) null else PasswordMaskTransformation,
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = canEdit,
                        modifier = Modifier.padding(end = 4.dp).size(40.dp),
                        backgroundColor = Color.Transparent,
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) MiuixIcons.Glass.Normal.Hide else MiuixIcons.Glass.Normal.Show,
                            contentDescription = null,
                            tint = colors.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = MiuixIcons.Glass.Normal.LockOutline,
                contentDescription = null,
                tint = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 1.dp).size(15.dp),
            )
            Text(
                text = stringResource(TextR.string.text_sessions_are_encrypted_on_this_device_passwords_are_never_saved),
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
        }

        error?.let { ZlibInlineErrorBanner(it) }

        if (busy) ZlibLoadingStatus(stringResource(TextR.string.text_verifying_account))

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onSubmit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    text = if (addingAccount) stringResource(TextR.string.text_sign_in_and_add) else stringResource(TextR.string.text_sign_in),
                    color = colors.onPrimary,
                )
            }
            Button(
                onClick = onToggleCookie,
                enabled = canEdit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (useCookie) stringResource(TextR.string.text_use_email_and_password) else stringResource(TextR.string.text_import_cookie))
            }
        }
    }
}
