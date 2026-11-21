package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalZlibOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
internal fun ZlibOverlayHost(content: @Composable () -> Unit) {
    val backdrop = if (LocalZlibGlass.current) rememberLayerBackdrop() else null
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) {
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
            CompositionLocalProvider(LocalZlibOverlayBackdrop provides backdrop, content = content)
        }
    }
}

@Composable
fun ZlibGlassBottomSheet(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalZlibOverlayBackdrop.current
    GlassBottomSheet(
        show = show,
        title = title,
        backdrop = backdrop,
        stroke = if (backdrop != null) zlibGlassStroke() else null,
        containerColor = MiuixTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        content = content,
    )
}

@Composable
fun ZlibGlassDialog(
    show: Boolean,
    title: String,
    onDismiss: () -> Unit,
    summary: String? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalZlibOverlayBackdrop.current
    GlassDialog(
        show = show,
        title = title,
        summary = summary,
        backdrop = backdrop,
        stroke = if (backdrop != null) zlibGlassStroke() else null,
        containerColor = MiuixTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
        content = content,
    )
}

@Composable
fun ZlibConfirmDialog(
    show: Boolean,
    title: String,
    summary: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
) {
    ZlibGlassDialog(show = show, title = title, summary = summary, onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("取消", style = MiuixTheme.textStyles.button)
            }
            GlassDialogButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = show,
                role = if (destructive) GlassDialogButtonRole.Destructive else GlassDialogButtonRole.Primary,
            ) {
                Text(confirmLabel, style = MiuixTheme.textStyles.button)
            }
        }
    }
}
