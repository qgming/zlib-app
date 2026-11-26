package com.qgming.zlib.core.designsystem

import androidx.compose.ui.res.stringResource
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassBottomSheet
import top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults
import top.yukonga.miuix.kmp.glass.GlassDialog
import top.yukonga.miuix.kmp.glass.GlassDialogButton
import top.yukonga.miuix.kmp.glass.GlassDialogButtonRole
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Close
import top.yukonga.miuix.kmp.icon.glass.Ok
import top.yukonga.miuix.kmp.icon.glass.Reset
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal val LocalZlibOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun ZlibAppOverlay(content: @Composable () -> Unit) = ZlibOverlayHost(content = content)

/** Bottom hints are toasts (ADR-067); this host only provides the glass backdrop and insets. */
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
    startAction: (@Composable () -> Unit)? = { ZlibSheetCloseButton(onClick = onDismiss) },
    endAction: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalZlibOverlayBackdrop.current
    GlassBottomSheet(
        show = show,
        title = title,
        startAction = startAction,
        endAction = endAction,
        backdrop = backdrop,
        stroke = if (backdrop != null) zlibGlassStroke() else null,
        containerColor = MiuixTheme.colorScheme.surfaceContainer,
        onDismissRequest = onDismiss,
    ) { headerPadding ->
        // Respect GlassBottomSheet's floating header padding so content never overlaps the header,
        // and reserve horizontal/bottom system-bar/cutout insets plus 16dp breathing room (ADR-047).
        Column(
            Modifier.fillMaxWidth()
                .padding(top = headerPadding.calculateTopPadding() + 8.dp)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                )
                .padding(bottom = 16.dp),
        ) {
            content()
        }
    }
}

@Composable
fun ZlibSheetCloseButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = stringResource(TextR.string.text_close),
) {
    SheetHeaderActionButton(
        icon = MiuixIcons.Glass.Normal.Close,
        description = description,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
fun ZlibSheetBackButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = stringResource(TextR.string.text_back),
) {
    SheetHeaderActionButton(
        icon = MiuixIcons.Glass.Normal.ChevronBackward,
        description = description,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
fun ZlibSheetResetButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = stringResource(TextR.string.text_reset),
) {
    SheetHeaderActionButton(
        icon = MiuixIcons.Glass.Normal.Reset,
        description = description,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
fun ZlibSheetAddButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String = stringResource(TextR.string.text_create_booklist),
) {
    SheetHeaderActionButton(
        icon = MiuixIcons.Glass.Normal.Add,
        description = description,
        onClick = onClick,
        enabled = enabled,
    )
}

@Composable
fun ZlibSheetConfirmButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    description: String,
    accent: Boolean = true,
) {
    val colors = MiuixTheme.colorScheme
    SheetHeaderActionButton(
        icon = MiuixIcons.Glass.Normal.Ok,
        description = description,
        onClick = onClick,
        enabled = enabled,
        tint = when {
            !enabled -> colors.onSurfaceVariantSummary
            accent -> colors.primary
            else -> colors.onSurface
        },
    )
}

@Composable
private fun SheetHeaderActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
) {
    val iconContent: @Composable () -> Unit = {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
    if (LocalZlibGlass.current) {
        GlassIconButton(
            onClick = { if (enabled) onClick() },
            modifier = Modifier.semantics {
                role = Role.Button
                if (!enabled) disabled()
            },
            content = iconContent,
        )
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(GlassBottomSheetDefaults.ButtonSize),
            content = iconContent,
        )
    }
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
    busy: Boolean = false,
) {
    ZlibGlassDialog(show = show, title = title, summary = summary, onDismiss = { if (!busy) onDismiss() }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GlassDialogButton(onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !busy) {
                Text(stringResource(TextR.string.text_cancel), style = MiuixTheme.textStyles.button)
            }
            GlassDialogButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = show && !busy,
                role = if (destructive) GlassDialogButtonRole.Destructive else GlassDialogButtonRole.Primary,
            ) {
                Text(if (busy) stringResource(TextR.string.text_deleting) else confirmLabel, style = MiuixTheme.textStyles.button)
            }
        }
    }
}

@Composable
fun ZlibMenuAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text = text, modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick)
}
