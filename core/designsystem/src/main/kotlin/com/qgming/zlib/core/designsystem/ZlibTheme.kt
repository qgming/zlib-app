package com.qgming.zlib.core.designsystem

import android.app.Activity
import android.app.ActivityManager
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.model.BookGridColumns
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

internal val LocalZlibGlass = staticCompositionLocalOf { false }
internal val LocalBookGridColumns = staticCompositionLocalOf { BookGridColumns.AUTO }

@Composable
@ReadOnlyComposable
fun zlibSurfaceColor(): Color = MiuixTheme.colorScheme.surface

@Composable
@ReadOnlyComposable
internal fun zlibIsDark() = MiuixTheme.colorScheme.background.luminance() < 0.5f

@Composable
@ReadOnlyComposable
internal fun zlibGlassStroke() = if (zlibIsDark()) GlassEdgeStrokes.MiddleDark else GlassEdgeStrokes.MiddleLight

private val LightPage = Color(0xFFF5F5F5)
private val DarkCard = Color(0xFF202022)

private val LightColors = lightColorScheme(background = LightPage, surface = LightPage)

private val DarkColors = darkColorScheme(
    background = Color.Black,
    surfaceVariant = DarkCard,
    surfaceContainer = DarkCard,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceContainer = Color.White,
)

@Composable
fun ZlibTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    bookGridColumns: BookGridColumns = BookGridColumns.AUTO,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val supportsGlass = remember(context) {
        isRuntimeShaderSupported() && context.getSystemService(ActivityManager::class.java)?.isLowRamDevice != true
    }
    val useGlass = supportsGlass && !LocalInspectionMode.current
    val darkTheme = mode.isDark(isSystemInDarkTheme())
    val view = LocalView.current
    val activity = remember(view) {
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
    }
    SideEffect {
        if (activity != null && !view.isInEditMode) {
            val insets = WindowCompat.getInsetsController(activity.window, view)
            insets.isAppearanceLightStatusBars = !darkTheme
            insets.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    val controller = remember(darkTheme) {
        ThemeController(
            colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
            lightColors = LightColors,
            darkColors = DarkColors,
        )
    }
    MiuixTheme(controller = controller) {
        CompositionLocalProvider(
            LocalZlibGlass provides useGlass,
            LocalBookGridColumns provides bookGridColumns,
            content = content,
        )
    }
}
