package com.qgming.zlib.domain

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qgming.zlib.R
import com.qgming.zlib.core.designsystem.ZlibDomainVerificationPage
import com.qgming.zlib.core.designsystem.ZlibLoading
import com.qgming.zlib.core.designsystem.ZlibPreviewProgress
import com.qgming.zlib.core.designsystem.ZlibStateCard
import com.qgming.zlib.core.designsystem.ZlibTheme
import com.qgming.zlib.core.model.ThemeMode
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainVerificationCookies
import java.net.URI

class DomainVerificationActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: String
    private lateinit var themeMode: ThemeMode
    private lateinit var cookieManager: CookieManager
    private var webView: WebView? = null
    private var exitRequested = false
    private var cleanupComplete = false
    private var verificationNetwork: Long? = null
    @Volatile private var networkChanged = false
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var ready by mutableStateOf(false)
    private var loadProgress by mutableIntStateOf(0)
    private var loadFailed by mutableStateOf(false)
    private var activeToast: Toast? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        setResult(RESULT_CANCELED)
        val requested = intent.getStringExtra(EXTRA_HOST)
        if (requested == null || requested != DomainPolicy.normalizeHostInput(requested)) {
            super.finish()
            return
        }
        host = requested
        themeMode = ThemeMode.fromPreference(intent.getStringExtra(EXTRA_THEME_MODE))
        connectivity = getSystemService(ConnectivityManager::class.java)
        verificationNetwork = currentNetwork()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network.networkHandle != verificationNetwork) networkChanged = true
            }

            override fun onLost(network: Network) {
                if (network.networkHandle == verificationNetwork) networkChanged = true
            }
        }.also { callback ->
            runCatching { connectivity?.registerDefaultNetworkCallback(callback) }.onFailure { networkChanged = true }
        }
        enableEdgeToEdge()
        cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit(false)
        })

        setContent {
            ZlibTheme(mode = themeMode) {
                VerificationContent()
            }
        }

        val timeout = Runnable {
            if (!ready && !exitRequested) {
                loadFailed = true
                requestExit(false)
            }
        }
        handler.postDelayed(timeout, CLEANUP_TIMEOUT_MILLIS)
        cookieManager.removeAllCookies {
            handler.removeCallbacks(timeout)
            if (!exitRequested && !isDestroyed) {
                cookieManager.flush()
                runCatching { WebStorage.getInstance().deleteAllData() }
                ready = true
                webView?.let(::startFreshLoad)
            }
        }
    }

    @Composable
    private fun VerificationContent() {
        ZlibDomainVerificationPage(
            title = host,
            refreshDescription = stringResource(R.string.domain_verification_refresh),
            doneDescription = stringResource(R.string.domain_verification_done),
            refreshEnabled = ready && !exitRequested,
            doneEnabled = ready && !exitRequested,
            onBack = { requestExit(false) },
            onRefresh = { reload() },
            onDone = { onDoneClicked() },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (!ready) {
                    ZlibLoading()
                } else {
                    VerificationWebView()
                    if (loadProgress in 1..99 && !loadFailed) {
                        ZlibPreviewProgress(loadProgress, Modifier.align(Alignment.TopCenter))
                    }
                    if (loadFailed) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp).align(Alignment.TopCenter),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            Box(Modifier.widthIn(max = 480.dp)) {
                                ZlibStateCard(
                                    title = stringResource(R.string.domain_verification_title),
                                    message = stringResource(R.string.domain_verification_failed),
                                    actionLabel = stringResource(R.string.domain_verification_refresh),
                                    onAction = { reload() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun VerificationWebView() {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                WebView(context).apply {
                    isSaveEnabled = false
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)
                    setDownloadListener { _, _, _, _, _ ->
                        showToast(R.string.domain_verification_blocked)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            loadProgress = newProgress
                        }

                        override fun onPermissionRequest(request: PermissionRequest) {
                            request.deny()
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url?.toString().orEmpty()
                            if (isAllowedNavigation(url)) return false
                            if (request.isForMainFrame) {
                                showToast(R.string.domain_verification_blocked)
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            if (url == null || !isAllowedNavigation(url)) {
                                view.stopLoading()
                                showToast(R.string.domain_verification_blocked)
                            } else {
                                loadFailed = false
                            }
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (!exitRequested) {
                                cookieManager.flush()
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                loadFailed = true
                            }
                        }

                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                            handler.cancel()
                            loadFailed = true
                        }

                        override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                            handler.cancel()
                        }

                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            loadFailed = true
                            requestExit(false)
                            return true
                        }
                    }
                    webView = this
                    startFreshLoad(this)
                }
            },
            onRelease = { view ->
                if (webView === view) webView = null
                teardownWebView(view)
            },
        )
    }

    private fun startFreshLoad(view: WebView) {
        if (!ready || exitRequested) return
        loadFailed = false
        loadProgress = 0
        view.clearCache(true)
        view.clearHistory()
        view.clearFormData()
        view.loadUrl("https://$host/")
    }

    private fun reload() {
        if (!ready || exitRequested) return
        loadFailed = false
        loadProgress = 0
        webView?.stopLoading()
        webView?.loadUrl("https://$host/")
    }

    private fun onDoneClicked() {
        if (!ready || exitRequested) return
        val sameNetwork = verificationNetwork != null && !networkChanged && currentNetwork() == verificationNetwork
        if (!sameNetwork) {
            showToast(R.string.domain_verification_network_changed)
            requestExit(false)
            return
        }
        cookieManager.flush()
        val clearance = readVerificationCookie()
        if (clearance == null) {
            showToast(R.string.domain_verification_not_completed)
            return
        }
        requestExit(completed = true, capturedCookie = clearance)
    }

    private fun readVerificationCookie(): String? = runCatching {
        DomainVerificationCookies.sanitizeCookieHeader(cookieManager.getCookie("https://$host/"))
    }.getOrNull()

    private fun isAllowedNavigation(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val targetHost = uri.host?.lowercase()?.trimEnd('.') ?: return false
        if (targetHost.isEmpty() || targetHost == "localhost" || targetHost.endsWith(".localhost")) return false
        if ('.' !in targetHost || targetHost.startsWith('[') || IPV4_PATTERN.matches(targetHost)) return false
        if (targetHost == host) {
            val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
            if (segments.any { it.lowercase() in BLOCKED_ACCOUNT_PATHS }) return false
        }
        return true
    }

    private fun showToast(messageRes: Int) {
        if (isDestroyed) return
        activeToast?.cancel()
        activeToast = Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).also { it.show() }
    }

    private fun requestExit(completed: Boolean, capturedCookie: String? = null) {
        if (exitRequested) return
        exitRequested = true
        val sameNetwork = verificationNetwork != null && !networkChanged && currentNetwork() == verificationNetwork
        val clearance = if (completed && ready && sameNetwork) {
            capturedCookie ?: readVerificationCookie()
        } else null
        val capturedUserAgent = if (completed && clearance != null) {
            runCatching { DomainVerificationCookies.sanitizeUserAgent(webView?.settings?.userAgentString) }.getOrNull()
        } else null
        destroyWebView()
        if (!::cookieManager.isInitialized) {
            super.finish()
            return
        }
        val timeout = Runnable {
            if (!cleanupComplete) {
                cookieManager.removeAllCookies(null)
                runCatching { WebStorage.getInstance().deleteAllData() }
                setResult(RESULT_CANCELED)
                super.finish()
                scheduleProcessTeardown()
            }
        }
        handler.postDelayed(timeout, CLEANUP_TIMEOUT_MILLIS)
        cookieManager.removeAllCookies {
            handler.removeCallbacks(timeout)
            if (!cleanupComplete && !isDestroyed) {
                cookieManager.flush()
                runCatching { WebStorage.getInstance().deleteAllData() }
                cleanupComplete = true
                if (completed && ready && clearance != null && !networkChanged && currentNetwork() == verificationNetwork) {
                    setResult(
                        RESULT_OK,
                        Intent()
                            .putExtra(EXTRA_HOST, host)
                            .putExtra(EXTRA_COOKIE, clearance)
                            .putExtra(EXTRA_NETWORK_HANDLE, verificationNetwork ?: -1L)
                            .apply {
                                if (capturedUserAgent != null) {
                                    putExtra(EXTRA_USER_AGENT, capturedUserAgent)
                                }
                            },
                    )
                } else {
                    setResult(RESULT_CANCELED)
                }
                super.finish()
                scheduleProcessTeardown()
            }
        }
    }

    private fun scheduleProcessTeardown() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (Application.getProcessName() == "$packageName:domain_verification") {
                Process.killProcess(Process.myPid())
            }
        }, PROCESS_TEARDOWN_DELAY_MILLIS)
    }

    override fun finish() = requestExit(false)

    override fun onPause() {
        webView?.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        activeToast?.cancel()
        activeToast = null
        networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        destroyWebView()
        if (::cookieManager.isInitialized && !cleanupComplete) {
            cookieManager.removeAllCookies(null)
            runCatching { WebStorage.getInstance().deleteAllData() }
        }
        super.onDestroy()
    }

    private fun destroyWebView() {
        webView?.let(::teardownWebView)
        webView = null
    }

    private fun teardownWebView(view: WebView) {
        view.stopLoading()
        view.webChromeClient = null
        view.webViewClient = WebViewClient()
        view.clearCache(true)
        view.clearHistory()
        view.clearFormData()
        (view.parent as? ViewGroup)?.removeView(view)
        view.removeAllViews()
        view.destroy()
    }

    private fun currentNetwork(): Long? = runCatching { connectivity?.activeNetwork?.networkHandle }.getOrNull()

    companion object {
        const val EXTRA_HOST = "domain_verification_host"
        const val EXTRA_COOKIE = "domain_verification_clearance"
        const val EXTRA_USER_AGENT = "domain_verification_user_agent"
        const val EXTRA_NETWORK_HANDLE = "domain_verification_network"
        const val EXTRA_THEME_MODE = "domain_verification_theme_mode"
        private const val CLEANUP_TIMEOUT_MILLIS = 5000L
        private const val PROCESS_TEARDOWN_DELAY_MILLIS = 500L
        private val IPV4_PATTERN = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val BLOCKED_ACCOUNT_PATHS = setOf(
            "login", "register", "registration", "signin", "signup", "account", "oauth", "authorize",
        )

        fun createIntent(context: Context, host: String, themeMode: ThemeMode = ThemeMode.SYSTEM): Intent {
            require(host == DomainPolicy.normalizeHostInput(host))
            return Intent(context, DomainVerificationActivity::class.java)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_THEME_MODE, themeMode.name)
        }
    }
}
