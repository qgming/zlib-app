package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun ZlibTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val controller = remember(darkTheme) {
        ThemeController(
            ColorSchemeMode.System,
            isDark = darkTheme,
        )
    }
    MiuixTheme(
        controller = controller,
        content = content,
    )
}
