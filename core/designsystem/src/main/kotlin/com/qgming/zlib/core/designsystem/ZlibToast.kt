package com.qgming.zlib.core.designsystem

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * One-shot system feedback shared by features; a page/account change cancels its last toast.
 * Bottom hints are toasts: the design system no longer hosts a Snackbar (ADR-067).
 */
@Composable
fun ZlibToastHost(messages: Flow<String>, key: Any? = Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember(key) { ToastHolder() }
    DisposableEffect(holder) { onDispose { holder.toast?.cancel() } }
    LaunchedEffect(messages, key, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.collect { message -> holder.show(context, message) }
        }
    }
}

/** Imperative entry point for screens whose message is not already a [Flow]. */
class ZlibToastHostState internal constructor() {
    private val emitted = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    internal val messages: Flow<String> = emitted

    fun showMessage(message: String) {
        emitted.tryEmit(message)
    }
}

@Composable
fun rememberZlibToastHostState(key: Any? = Unit): ZlibToastHostState = remember(key) { ZlibToastHostState() }

@Composable
fun ZlibToastHost(state: ZlibToastHostState, key: Any? = Unit) = ZlibToastHost(state.messages, key)

private class ToastHolder(var toast: Toast? = null) {
    /** A new message replaces the visible one instead of queueing behind it. */
    fun show(context: android.content.Context, message: String) {
        toast?.cancel()
        toast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }
}
