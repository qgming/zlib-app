package com.qgming.zlib

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.webkit.WebView
import com.qgming.zlib.BuildConfig
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.common.AppText
import com.qgming.zlib.core.network.DomainPolicy
import dagger.hilt.android.HiltAndroidApp
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

@HiltAndroidApp
class ZlibApplication : Application(), SingletonImageLoader.Factory {

    /** Anonymous transport for public image URLs: never sends credentials or follows unsafe redirects. */
    private val anonymousImageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(SafeImageRedirectInterceptor)
            .build()
    }

    /** Comment image URLs are not PAPI requests and must never use the session client. */
    internal val commentAvatarLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { anonymousImageClient })) }
            .crossfade(true)
            .build()
    }

    override fun attachBaseContext(base: Context) {
        if (getProcessName() == "${base.packageName}:domain_verification") {
            WebView.setDataDirectorySuffix("domain_verification")
        }
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
        SafeLog.enabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }

    /**
     * Covers use a dedicated anonymous OkHttp client without cookies and with strict standard-HTTPS
     * redirect validation (no credentials, no loopback/IP/custom-port redirects).
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { anonymousImageClient }))
            }
            .crossfade(true)
            .build()

    private object SafeImageRedirectInterceptor : Interceptor {
        private const val MAX_REDIRECTS = 3

        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            if (!DomainPolicy.isStandardHttps(request.url)) {
                throw IOException("Blocked non-standard image URL")
            }
            var response = chain.proceed(request)
            var redirects = 0
            while (response.isRedirect && redirects < MAX_REDIRECTS) {
                val location = response.header("Location") ?: break
                val target = request.url.resolve(location)
                if (target == null || !DomainPolicy.isStandardHttps(target)) {
                    response.close()
                    throw IOException("Blocked unsafe image redirect")
                }
                response.close()
                request = request.newBuilder()
                    .url(target)
                    .removeHeader("Cookie")
                    .removeHeader("Authorization")
                    .build()
                response = chain.proceed(request)
                redirects++
            }
            return response
        }
    }
}
