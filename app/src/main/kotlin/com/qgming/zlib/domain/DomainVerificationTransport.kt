package com.qgming.zlib.domain

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.qgming.zlib.core.network.DomainVerificationCookies
import com.qgming.zlib.core.network.DomainVerificationNavigation
import com.qgming.zlib.core.network.EapiHeaders
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class DomainVerificationTransport(
    private val host: String,
    private val cookies: CookieManager,
    private val onFailure: () -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(4)
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher(executor).apply {
            maxRequests = 4
            maxRequestsPerHost = 4
        })
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var closed = false

    fun intercept(resource: WebResourceRequest): WebResourceResponse {
        if (closed || !isAllowed(resource.url.toString()) || resource.method !in setOf("GET", "HEAD")) return blocked()
        return try {
            fetch(resource)
        } catch (_: Exception) {
            if (!closed && resource.isForMainFrame) onFailure()
            blocked()
        }
    }

    fun isAllowed(rawUrl: String): Boolean = DomainVerificationNavigation.isAllowed(host, rawUrl) &&
        rawUrl.toHttpUrl().pathSegments.none { it.lowercase() in LOGIN_PATHS }

    fun cancel() {
        client.dispatcher.cancelAll()
    }

    fun close() {
        closed = true
        cancel()
        client.connectionPool.evictAll()
        executor.shutdownNow()
    }

    private fun fetch(resource: WebResourceRequest): WebResourceResponse {
        var url = resource.url.toString().toHttpUrl()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        for (redirect in 0..MAX_REDIRECTS) {
            if (closed || !isAllowed(url.toString())) return blocked()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw IOException("Verification request timed out")
            val request = Request.Builder().url(url)
                .method(resource.method, null)
                .header("User-Agent", EapiHeaders.USER_AGENT)
                .header("Cache-Control", "no-store")
                .apply {
                    resource.requestHeaders.entries.firstOrNull { it.key.equals("Accept", true) }
                        ?.value?.let { header("Accept", it) }
                    resource.requestHeaders.entries.firstOrNull { it.key.equals("Accept-Language", true) }
                        ?.value?.let { header("Accept-Language", it) }
                    DomainVerificationCookies.sanitizeCookieHeader(cookies.getCookie(url.toString()))
                        ?.let { header("Cookie", it) }
                }.build()
            val response = awaitResponse(client.newCall(request), remaining)
            try {
                receiveCookies(url, response)
            } catch (exception: Exception) {
                response.close()
                throw exception
            }
            if (response.code in 300..399) {
                val target = response.header("Location")?.let(url::resolve)
                response.close()
                if (redirect == MAX_REDIRECTS || target == null || !isAllowed(target.toString())) {
                    if (resource.isForMainFrame) onFailure()
                    return blocked()
                }
                url = target
                continue
            }
            return webResponse(response)
        }
        return blocked()
    }

    private fun awaitResponse(call: Call, remainingNanos: Long): Response {
        val result = CompletableFuture<Response>()
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: IOException) {
                result.completeExceptionally(exception)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!result.complete(response)) response.close()
            }
        })
        return try {
            result.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (exception: Exception) {
            result.cancel(false)
            call.cancel()
            throw exception
        }
    }

    private fun receiveCookies(url: HttpUrl, response: Response) {
        response.headers.values("Set-Cookie").take(64).forEach { raw ->
            if (raw.length > 8192) return@forEach
            val parsed = Cookie.parse(url, raw) ?: return@forEach
            if (parsed.name != DomainVerificationCookies.COOKIE_NAME || parsed.domain != host) return@forEach
            val header = DomainVerificationCookies.sanitizeCookieHeader("${parsed.name}=${parsed.value}") ?: return@forEach
            val expiry = if (parsed.expiresAt <= System.currentTimeMillis()) "; Max-Age=0" else ""
            val stored = CompletableFuture<Boolean>()
            mainHandler.post {
                if (closed) stored.complete(false)
                else cookies.setCookie("https://$host/", "$header; Path=/; Secure$expiry") { stored.complete(it) }
            }
            if (!stored.get(3, TimeUnit.SECONDS)) throw IOException("Verification cookie rejected")
        }
    }

    private fun webResponse(response: Response): WebResourceResponse {
        val body = response.body
        if (body == null) {
            response.close()
            return blocked()
        }
        val contentType = body.contentType()
        val sourcePolicy = response.header("Content-Security-Policy")
        val headers = response.headers.names().filterNot { name ->
            name.lowercase() in REMOVED_HEADERS
        }.associateWith { response.header(it).orEmpty() }.toMutableMap()
        val boundaryPolicy = "default-src https://$host; script-src https://$host 'unsafe-inline' 'unsafe-eval'; " +
            "style-src https://$host 'unsafe-inline'; connect-src https://$host; img-src https://$host; " +
            "frame-src 'none'; child-src 'none'; worker-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
        headers["Content-Security-Policy"] = sourcePolicy?.let { "$it, $boundaryPolicy" } ?: boundaryPolicy
        headers["Cache-Control"] = "no-store"
        headers["X-Content-Type-Options"] = "nosniff"
        val stream = object : FilterInputStream(body.byteStream()) {
            private var remaining = MAX_RESOURCE_BYTES

            override fun read(): Int {
                if (remaining <= 0) return -1
                val next = super.read()
                if (next >= 0) remaining--
                return next
            }

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = super.read(buffer, offset, minOf(length, remaining))
                if (count > 0) remaining -= count
                return count
            }

            override fun close() {
                response.close()
            }
        }
        return WebResourceResponse(
            contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
            contentType?.charset()?.name() ?: "UTF-8",
            response.code.coerceIn(100, 599),
            response.message.ifBlank { "Response" },
            headers,
            stream,
        )
    }

    private fun blocked(): WebResourceResponse = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val MAX_RESOURCE_BYTES = 8 * 1024 * 1024
        private val LOGIN_PATHS = setOf("login", "register", "registration", "signin", "signup", "account", "oauth", "authorize")
        private val REMOVED_HEADERS = setOf(
            "set-cookie", "set-cookie2", "content-security-policy", "content-security-policy-report-only",
            "report-to", "reporting-endpoints", "nel", "refresh", "location", "content-length", "content-encoding",
        )
    }
}
