package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ZlibShareButton(enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    TopBarButton(
        icon = MiuixIcons.Glass.Normal.Share,
        description = stringResource(TextR.string.text_share_action),
        onClick = onClick,
        glass = LocalZlibGlass.current,
        modifier = Modifier.size(48.dp),
        enabled = enabled,
        tint = if (enabled) colors.onSurface else colors.onSurfaceVariantSummary,
    )
}

/** Displays the exported bitmap; the caller supplies the page's safe content padding. */
@Composable
fun ZlibShareImageContent(
    image: ImageBitmap?,
    generating: Boolean,
    working: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val scroll = rememberScrollState()
    val actionsEnabled = image != null && !generating && !working && error == null
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                generating -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ZlibLoadingIndicator(Modifier.size(32.dp))
                    Text(
                        stringResource(TextR.string.text_generating_share_image),
                        style = MiuixTheme.textStyles.body2,
                        color = colors.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                    )
                }
                error != null || image == null -> Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(TextR.string.text_share_image_failed),
                        style = MiuixTheme.textStyles.title4,
                        color = colors.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    if (!error.isNullOrBlank()) {
                        Text(
                            error,
                            style = MiuixTheme.textStyles.footnote1,
                            color = colors.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Button(
                        onClick = onRetry,
                        enabled = !working,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(TextR.string.text_retry), textAlign = TextAlign.Center) }
                }
                else -> Image(
                    bitmap = image,
                    contentDescription = stringResource(TextR.string.text_share_image_title),
                    modifier = Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onSave,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(
                    stringResource(TextR.string.text_save_image),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Button(
                onClick = onShare,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(
                    stringResource(TextR.string.text_share_action),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
