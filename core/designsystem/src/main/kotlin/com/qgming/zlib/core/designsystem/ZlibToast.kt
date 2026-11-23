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
import kotlinx.coroutines.flow.Flow

/** One-shot system feedback shared by features; a page/account change cancels its last toast. */
@Composable
fun ZlibToastHost(messages: Flow<String>, key: Any? = Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember(key) { ToastHolder() }
    DisposableEffect(holder) { onDispose { holder.toast?.cancel() } }
    LaunchedEffect(messages, key, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            messages.collect { message ->
                holder.toast?.cancel()
                holder.toast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
            }
        }
    }
}

private class ToastHolder(var toast: Toast? = null)
