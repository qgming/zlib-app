package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.DomainCheck
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassCard
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.FolderFill
import top.yukonga.miuix.kmp.icon.glass.NoNetwork
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val OnboardingMaxWidth = 440.dp

@Composable
fun ZlibSplashScreen(
    appName: String,
    logo: Painter,
    statusText: String = stringResource(TextR.string.text_checking_available_domain),
) {
    val colors = MiuixTheme.colorScheme
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.surface,
        contentWindowInsets = WindowInsets(0),
        popupHost = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingMaxWidth)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Image(
                    painter = logo,
                    contentDescription = null,
                    modifier = Modifier.size(84.dp),
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = appName,
                    style = MiuixTheme.textStyles.title1.copy(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = colors.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                ZlibGlassSurface(
                    cornerRadius = 999.dp,
                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    modifier = Modifier,
                ) {
                    ZlibLoadingStatus(text = statusText)
                }
            }
        }
    }
}

@Composable
fun ZlibDirectoryWelcomeScreen(
    logo: Painter,
    busy: Boolean,
    error: String?,
    onChoose: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.surface,
        contentWindowInsets = WindowInsets(0),
        popupHost = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(
                        painter = logo,
                        contentDescription = null,
                        modifier = Modifier.size(76.dp),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(TextR.string.text_welcome_to_zlib),
                        style = MiuixTheme.textStyles.title2.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = stringResource(TextR.string.text_choose_download_folder),
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }

                ZlibOnboardingCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(colors.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Glass.Normal.FolderFill,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = stringResource(TextR.string.text_choose_download_folder),
                                style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurface,
                            )
                            Text(
                                text = stringResource(TextR.string.text_choose_a_folder_for_downloaded_books_and_app_updates_then_sign_in),
                                style = MiuixTheme.textStyles.body2,
                                color = colors.onSurfaceVariantSummary,
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.onSurface.copy(alpha = if (zlibIsDark()) 0.06f else 0.04f))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = stringResource(TextR.string.text_welcome_step_folder_hint),
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                        )
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        ZlibInlineErrorBanner(it)
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = onChoose,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = if (busy) stringResource(TextR.string.text_saving) else stringResource(TextR.string.text_choose_folder),
                            color = colors.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ZlibStartupFailedScreen(
    logo: Painter,
    message: String,
    actionError: String?,
    onRetry: () -> Unit,
    onAddHost: (String) -> Unit,
    lockedHost: String?,
    checks: List<DomainCheck>,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onProbe: (String) -> Unit,
    onVerify: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    var host by rememberSaveable { mutableStateOf("") }
    var showDomains by rememberSaveable { mutableStateOf(false) }

    ZlibDomainPicker(
        show = showDomains,
        checks = checks,
        currentHost = null,
        busy = busy,
        error = actionError,
        onSelect = onSelect,
        onProbe = onProbe,
        onVerify = onVerify,
        onDismiss = { showDomains = false },
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = colors.surface,
        contentWindowInsets = WindowInsets(0),
        popupHost = {},
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = OnboardingMaxWidth),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Image(
                        painter = logo,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Text(
                        text = stringResource(TextR.string.text_cannot_connect_to_z_library),
                        style = MiuixTheme.textStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }

                ZlibOnboardingCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.error.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Glass.Normal.NoNetwork,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = message,
                                style = MiuixTheme.textStyles.body2,
                                color = colors.onSurface,
                            )
                            if (lockedHost != null) {
                                Text(
                                    text = stringResource(TextR.string.text_locked_domain_unavailable, lockedHost),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = colors.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onRetry,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = stringResource(TextR.string.text_check_again),
                            color = colors.onPrimary,
                        )
                    }

                    if (checks.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { showDomains = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(TextR.string.text_switch_domain))
                        }
                    }
                }

                ZlibOnboardingCard {
                    Text(
                        text = stringResource(TextR.string.text_add_a_mirror),
                        style = MiuixTheme.textStyles.title4.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(TextR.string.text_if_the_built_in_domains_are_unavailable_add_a_trusted_https_mirror_host),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(TextR.string.text_optional_mirror_credential_warning),
                        style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(12.dp))
                    ZlibTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = "mirror.example.org",
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done,
                        ),
                        onDone = {
                            if (!busy && host.isNotBlank()) onAddHost(host)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    actionError?.let {
                        Spacer(Modifier.height(10.dp))
                        ZlibInlineErrorBanner(it)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { if (host.isNotBlank()) onAddHost(host) },
                        enabled = !busy && host.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(TextR.string.text_add_and_retry))
                    }
                }
            }
        }
    }
}

@Composable
fun ZlibLoginHeader(
    logo: Painter?,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 6.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (logo != null) {
            Image(
                painter = logo,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
        }
        Text(
            text = subtitle,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun ZlibInlineErrorBanner(message: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.error.copy(alpha = if (zlibIsDark()) 0.16f else 0.10f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = message,
            style = MiuixTheme.textStyles.footnote1,
            color = colors.error,
        )
    }
}

@Composable
private fun ZlibOnboardingCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = PaddingValues(20.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (LocalZlibGlass.current) {
        GlassCard(
            modifier = modifier.fillMaxWidth(),
            cornerRadius = 24.dp,
            insideMargin = insideMargin,
            content = content,
        )
    } else {
        ZlibCard(
            modifier = modifier,
            cornerRadius = 24.dp,
            insideMargin = insideMargin,
            content = content,
        )
    }
}

@Composable
private fun ZlibGlassSurface(
    cornerRadius: androidx.compose.ui.unit.Dp,
    insideMargin: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    if (LocalZlibGlass.current) {
        GlassCard(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            content = content,
        )
    } else {
        top.yukonga.miuix.kmp.basic.Card(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = insideMargin,
            content = content,
        )
    }
}
