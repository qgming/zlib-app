package com.qgming.zlib.core.designsystem

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState

@Stable
class ZlibSnackbarHostState internal constructor() {
    internal val host = SnackbarHostState()

    suspend fun showMessage(message: String) {
        host.showSnackbar(
            message = message,
            actionLabel = null,
            withDismissAction = false,
            duration = SnackbarDuration.Short,
        )
    }
}

/** Changing [key] clears feedback when its account or page content is reset. */
@Composable
fun rememberZlibSnackbarHostState(key: Any? = Unit): ZlibSnackbarHostState =
    remember(key) { ZlibSnackbarHostState() }

@Composable
fun ZlibSnackbarHost(state: ZlibSnackbarHostState) {
    SnackbarHost(
        state = state.host,
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        ),
    )
}
