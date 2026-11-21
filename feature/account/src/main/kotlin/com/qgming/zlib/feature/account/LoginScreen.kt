package com.qgming.zlib.feature.account

import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qgming.zlib.core.designsystem.ZlibCard
import com.qgming.zlib.core.designsystem.ZlibCardList
import com.qgming.zlib.core.designsystem.ZlibLoadingStatus
import com.qgming.zlib.core.designsystem.ZlibPage
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showWebView by rememberSaveable { mutableStateOf(false) }

    if (showWebView && state.domain != null) {
        WebViewLoginScreen(
            host = state.domain!!,
            isAllowedUrl = viewModel::isAllowedUrl,
            signingIn = state.loading,
            error = state.error,
            onCookie = { host, cookie -> viewModel.importFromWebView(host, cookie) },
            onBack = { showWebView = false },
        )
        return
    }

    ZlibPage(title = "登录 Zlib") { padding ->
        ZlibCardList(padding) {
            item {
                LoginForm(state = state, viewModel = viewModel, onWebView = { showWebView = true })
            }
        }
    }
}

@Composable
private fun LoginForm(
    state: LoginUiState,
    viewModel: LoginViewModel,
    onWebView: () -> Unit,
) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "登录后才能搜索、下载和管理书架。密码只用于本次登录，不会写入磁盘。",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )

            ZlibCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("当前域名", style = MiuixTheme.textStyles.body2)
                        Text(
                            text = state.domain ?: "尚未检测到可用域名",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (state.probing) {
                        ZlibLoadingStatus("体检中…")
                    } else {
                        TextButton(text = "换一批", onClick = viewModel::probeDomains)
                    }
                }
            }

            TextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = "邮箱",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            TextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = "密码",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
            )

            state.error?.let { error ->
                Text(
                    text = error,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                )
            }

            Button(
                onClick = viewModel::login,
                enabled = !state.loading && state.email.isNotBlank() && state.password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("登录")
            }

            if (state.loading) ZlibLoadingStatus("正在验证登录…")

            TextButton(
                text = if (state.showCookieImport) "收起 Cookie 导入" else "使用 Cookie 导入",
                onClick = viewModel::toggleCookieImport,
            )

            if (state.showCookieImport) {
                ZlibCard {
                    Text(
                        text = "支持三种格式：浏览器请求头里的 Cookie、Netscape cookies.txt、或 {\"remix_userid\":…} JSON。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = state.cookieText,
                        onValueChange = viewModel::onCookieTextChange,
                        label = "Cookie 内容",
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = viewModel::importCookieText,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("导入并校验")
                    }
                }
            }

            TextButton(
                text = "WebView 登录（账号密码被拒时使用）",
                onClick = onWebView,
                enabled = state.domain != null,
            )

            Text(
                text = "Zlib 是非官方客户端，不保存密码、不上传任何数据。",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
}

@Composable
private fun WebViewLoginScreen(
    host: String,
    isAllowedUrl: (String) -> Boolean,
    signingIn: Boolean,
    error: String?,
    onCookie: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val url = "https://$host/"
    var finalUrl by remember(host) { mutableStateOf(url) }
    var progress by remember(host) { mutableIntStateOf(0) }
    val webView = rememberWebView(
        host,
        isAllowedUrl,
        onPageFinished = { finalUrl = it },
        onProgressChanged = { progress = it },
    )

    DisposableEffect(webView) {
        onDispose { webView.destroy() }
    }

    ZlibPage(
        title = "登录 $host",
        onBack = onBack,
        actions = {
            TextButton(
                text = "完成",
                enabled = !signingIn,
                onClick = {
                    val cookieUrl = finalUrl
                    val cookieHost = runCatching { java.net.URI(cookieUrl).host }.getOrNull() ?: host
                    val cookie = CookieManager.getInstance().getCookie(cookieUrl)
                    onCookie(cookieHost, cookie.orEmpty())
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (signingIn || progress < 100) {
                ZlibLoadingStatus(
                    if (signingIn) "正在验证登录…" else "正在加载登录页…",
                    Modifier.padding(vertical = 8.dp),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
@android.annotation.SuppressLint("SetJavaScriptEnabled")
private fun rememberWebView(
    host: String,
    isAllowedUrl: (String) -> Boolean,
    onPageFinished: (String) -> Unit,
    onProgressChanged: (Int) -> Unit,
): WebView {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(host) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    onProgressChanged(newProgress)
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    return !(target.scheme == "https" && isAllowedUrl(target.toString()))
                }

                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    // Redirects and page-initiated navigations bypass shouldOverrideUrlLoading.
                    if (!isAllowedUrl(url)) {
                        view.stopLoading()
                        view.loadUrl("https://$host/")
                    }
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (isAllowedUrl(url)) onPageFinished(url)
                }
            }
            loadUrl("https://$host/")
        }
    }
}
