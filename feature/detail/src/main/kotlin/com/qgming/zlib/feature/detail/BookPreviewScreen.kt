package com.qgming.zlib.feature.detail

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPreviewPage
import com.qgming.zlib.core.designsystem.ZlibPreviewProgress
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibToastHost
import com.qgming.zlib.core.designsystem.rememberZlibToastHostState
import com.qgming.zlib.core.model.OnlineBookPreview
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch

private const val PREVIEW_UA =
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

@Composable
fun BookPreviewScreen(id: String, hash: String, onBack: () -> Unit) {
    BookPreviewContent(id, hash, onBack, hiltViewModel<BookPreviewViewModel>(key = "$id:$hash:online-preview"))
}

@Composable
private fun BookPreviewContent(id: String, hash: String, onBack: () -> Unit, viewModel: BookPreviewViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state as? BookPreviewState.Ready
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val toast = rememberZlibToastHostState()
    val linkLabel = stringResource(TextR.string.text_copy_book_link)
    val noBrowser = stringResource(TextR.string.text_no_browser_available)
    val refresh = { viewModel.refresh(id, hash) }

    LaunchedEffect(id, hash) { viewModel.open(id, hash) }
    DisposableEffect(Unit) {
        onDispose {
            clearPreviewCookies()
        }
    }

    ZlibPreviewPage(
        onBack = onBack,
        onRefresh = refresh.takeUnless { state == BookPreviewState.Loading },
        onCopy = if (ready?.preview?.publicBookUrl != null) {
            {
                scope.launch {
                    viewModel.currentPreview()?.publicBookUrl?.let { url ->
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(linkLabel, url)))
                    }
                }
            }
        } else null,
        onOpenBrowser = if (ready != null) {
            {
                viewModel.currentPreview()?.let { preview ->
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, preview.readerUrl.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (_: ActivityNotFoundException) {
                        toast.showMessage(noBrowser)
                    } catch (_: SecurityException) {
                        toast.showMessage(noBrowser)
                    }
                }
            }
        } else null,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val current = state) {
                BookPreviewState.Loading -> ZlibLoading()
                BookPreviewState.Unavailable -> PreviewMessage(
                    TextR.string.text_preview_unavailable,
                    TextR.string.text_preview_link_unavailable,
                    refresh,
                )
                BookPreviewState.Failed -> PreviewMessage(
                    TextR.string.text_preview_load_failed,
                    TextR.string.text_preview_retry,
                    refresh,
                )
                is BookPreviewState.Ready -> key(current.preview) {
                    ReaderWebView(current.preview, refresh)
                }
            }
        }
    }
    ZlibToastHost(toast)
}

@Composable
private fun PreviewMessage(title: Int, message: Int, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 480.dp)) {
            ZlibStateCard(stringResource(title), stringResource(message), onAction = onRefresh)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ReaderWebView(preview: OnlineBookPreview, onRefresh: () -> Unit) {
    var progress by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val released = remember { AtomicBoolean(false) }
    if (failed) {
        PreviewMessage(TextR.string.text_preview_load_failed, TextR.string.text_preview_retry_or_open_browser, onRefresh)
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    isSaveEnabled = false
                    settings.apply {
                        javaScriptEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        cacheMode = WebSettings.LOAD_NO_CACHE
                        safeBrowsingEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = PREVIEW_UA
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) failed = true
                        }

                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                            if (request.isForMainFrame) failed = true
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            failed = true
                            return true
                        }
                    }
                    clearPreviewCookies {
                        if (!released.get()) loadUrl(preview.readerUrl)
                    }
                }
            },
            onRelease = { view ->
                released.set(true)
                view.stopLoading()
                view.webChromeClient = null
                view.webViewClient = WebViewClient()
                view.clearHistory()
                view.destroy()
            },
        )
        if (progress < 100) ZlibPreviewProgress(progress, Modifier.align(Alignment.TopCenter))
    }
}

private fun clearPreviewCookies(onCleared: (() -> Unit)? = null) {
    val manager = CookieManager.getInstance()
    manager.removeAllCookies {
        manager.flush()
        onCleared?.invoke()
    }
}
