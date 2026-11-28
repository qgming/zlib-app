package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qgming.zlib.core.common.R as TextR
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.ExpandList
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A single button silhouette with separate version selection and current-file download actions. */
@Composable
fun ZlibBookDownloadButton(
    enabled: Boolean,
    onVersions: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val divider = MiuixTheme.colorScheme.onPrimary.copy(alpha = if (enabled) 0.35f else 0.15f)
    val versionsLabel = stringResource(TextR.string.text_all_versions)
    val downloadLabel = stringResource(TextR.string.text_download_current_file)
    Row(
        modifier = modifier.height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(16.dp))
            .drawWithContent {
                drawContent()
                val x = size.width * 0.3f
                drawLine(divider, Offset(x, size.height * 0.25f), Offset(x, size.height * 0.75f), 1.dp.toPx())
            },
    ) {
        Button(
            onClick = onVersions,
            enabled = enabled,
            modifier = Modifier.weight(0.3f).fillMaxHeight().semantics { contentDescription = versionsLabel },
            minWidth = 0.dp,
            minHeight = 52.dp,
            cornerRadius = 0.dp,
            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) {
            Icon(MiuixIcons.Glass.Normal.ExpandList, contentDescription = null, modifier = Modifier.size(24.dp))
        }
        Button(
            onClick = onDownload,
            enabled = enabled,
            modifier = Modifier.weight(0.7f).fillMaxHeight().semantics { contentDescription = downloadLabel },
            minWidth = 0.dp,
            minHeight = 52.dp,
            cornerRadius = 0.dp,
            insideMargin = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
            colors = ButtonDefaults.buttonColorsPrimary(),
        ) { Text(stringResource(TextR.string.text_download_book)) }
    }
}
