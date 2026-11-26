package com.qgming.zlib.domain

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
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.qgming.zlib.R
import com.qgming.zlib.core.network.DomainPolicy
import com.qgming.zlib.core.network.DomainVerificationCookies
import com.qgming.zlib.core.network.EapiHeaders

class DomainVerificationActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var host: String
    private lateinit var cookieManager: CookieManager
    private lateinit var transport: DomainVerificationTransport
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var webView: WebView? = null
    private var exitRequested = false
    private var cleanupComplete = false
    private var ready = false
    private var verificationNetwork: Long? = null
    @Volatile private var networkChanged = false
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)
        setResult(RESULT_CANCELED)
        val requested = intent.getStringExtra(EXTRA_HOST)
        if (requested == null || requested != DomainPolicy.normalizeHostInput(requested)) {
            super.finish()
            return
        }
        host = requested
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
        transport = DomainVerificationTransport(host, cookieManager) {
            handler.post { showFailure() }
        }
        createContent()
        configureServiceWorkers()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = requestExit(false)
        })
        val timeout = Runnable {
            if (!ready && !exitRequested) {
                showFailure()
                requestExit(false)
            }
        }
        handler.postDelayed(timeout, CLEANUP_TIMEOUT_MILLIS)
        cookieManager.removeAllCookies {
            handler.removeCallbacks(timeout)
            if (!exitRequested && !isDestroyed) {
                cookieManager.flush()
                WebStorage.getInstance().deleteAllData()
                ready = true
                reload()
            }
        }
    }

    private fun createContent() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val spacing = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.domain_verification_title)
            textSize = 20f
            setPadding(spacing, spacing, spacing, spacing / 2)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.domain_verification_description, host)
            textSize = 14f
            setPadding(spacing, 0, spacing, spacing / 2)
        })
        status = TextView(this).apply {
            text = getString(R.string.domain_verification_loading)
            setPadding(spacing, 0, spacing, spacing / 2)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(spacing / 2, 0, spacing / 2, 0)
        }
        fun button(textResource: Int, action: () -> Unit) = Button(this).apply {
            setText(textResource)
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { action() }
            controls.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        button(R.string.domain_verification_close) { requestExit(false) }
        button(R.string.domain_verification_refresh) { reload() }
        button(R.string.domain_verification_done) { requestExit(true) }
        root.addView(controls)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4))
        webView = WebView(this).apply {
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_NO_CACHE
                safeBrowsingEnabled = true
                userAgentString = EapiHeaders.USER_AGENT
            }
            cookieManager.setAcceptThirdPartyCookies(this, false)
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(this@DomainVerificationActivity, R.string.domain_verification_blocked, Toast.LENGTH_SHORT).show()
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    this@DomainVerificationActivity.progress.progress = newProgress
                    this@DomainVerificationActivity.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }

                override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.method !in setOf("GET", "HEAD") || !transport.isAllowed(request.url.toString())

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse = transport.intercept(request)

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (url == null || !transport.isAllowed(url)) {
                        view.stopLoading()
                        showFailure()
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!exitRequested && status.text == getString(R.string.domain_verification_loading)) {
                        status.setText(R.string.domain_verification_loaded)
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) showFailure()
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    showFailure()
                }

                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) = handler.cancel()

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    showFailure()
                    requestExit(false)
                    return true
                }
            }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun configureServiceWorkers() {
        ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.apply {
                allowContentAccess = false
                allowFileAccess = false
                blockNetworkLoads = true
            }
            setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse = transport.intercept(request)
            })
        }
    }

    private fun reload() {
        if (!ready || exitRequested) return
        transport.cancel()
        webView?.stopLoading()
        status.setText(R.string.domain_verification_loading)
        webView?.loadUrl("https://$host/")
    }

    private fun showFailure() {
        if (!exitRequested && !isDestroyed && ::status.isInitialized) status.setText(R.string.domain_verification_failed)
    }

    private fun requestExit(completed: Boolean) {
        if (exitRequested) return
        exitRequested = true
        val sameNetwork = verificationNetwork != null && !networkChanged && currentNetwork() == verificationNetwork
        val clearance = if (completed && ready && sameNetwork) runCatching {
            DomainVerificationCookies.sanitizeCookieHeader(cookieManager.getCookie("https://$host/"))
        }.getOrNull() else null
        destroyWebView()
        if (::transport.isInitialized) transport.close()
        if (!::cookieManager.isInitialized) {
            super.finish()
            return
        }
        val timeout = Runnable {
            if (!cleanupComplete) {
                cookieManager.removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                setResult(RESULT_CANCELED)
                super.finish()
                Handler(Looper.getMainLooper()).postDelayed({
                    if (Application.getProcessName() == "$packageName:domain_verification") Process.killProcess(Process.myPid())
                }, 1000)
            }
        }
        handler.postDelayed(timeout, CLEANUP_TIMEOUT_MILLIS)
        cookieManager.removeAllCookies {
            handler.removeCallbacks(timeout)
            if (!cleanupComplete && !isDestroyed) {
                cookieManager.flush()
                WebStorage.getInstance().deleteAllData()
                cleanupComplete = true
                if (completed && ready) {
                    setResult(RESULT_OK, Intent().putExtra(EXTRA_HOST, host).apply {
                        if (clearance != null && !networkChanged && currentNetwork() == verificationNetwork) {
                            putExtra(EXTRA_COOKIES, clearance)
                            putExtra(EXTRA_NETWORK, verificationNetwork ?: -1L)
                        }
                    })
                }
                super.finish()
            }
        }
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
        networkCallback?.let { callback -> runCatching { connectivity?.unregisterNetworkCallback(callback) } }
        networkCallback = null
        destroyWebView()
        if (::transport.isInitialized) transport.close()
        if (::cookieManager.isInitialized && !cleanupComplete) {
            cookieManager.removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }

    private fun destroyWebView() {
        webView?.let { view ->
            view.stopLoading()
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
        webView = null
    }

    private fun currentNetwork(): Long? = runCatching { connectivity?.activeNetwork?.networkHandle }.getOrNull()

    companion object {
        const val EXTRA_HOST = "domain_verification_host"
        const val EXTRA_COOKIES = "domain_verification_clearance"
        const val EXTRA_NETWORK = "domain_verification_network"
        private const val CLEANUP_TIMEOUT_MILLIS = 5000L

        fun launchIntent(context: Context, host: String): Intent {
            require(host == DomainPolicy.normalizeHostInput(host))
            return Intent(context, DomainVerificationActivity::class.java).putExtra(EXTRA_HOST, host)
        }
    }
}
