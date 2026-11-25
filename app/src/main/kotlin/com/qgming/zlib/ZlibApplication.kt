package com.qgming.zlib

import android.app.Application
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
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import okhttp3.CookieJar
import okhttp3.OkHttpClient

@HiltAndroidApp
class ZlibApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var okHttpClient: OkHttpClient

    /** Comment image URLs are not PAPI requests and must never use the session client. */
    internal val commentAvatarLoader: ImageLoader by lazy {
        val anonymous = OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        ImageLoader.Builder(this)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { anonymous })) }
            .crossfade(true)
            .build()
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
     * Covers go through the same OkHttp client (timeouts, certificate handling) but never carry
     * session credentials: Coil requests are not tagged as EAPI calls and the strip interceptor
     * only keeps credentials for the original allowlisted host.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .crossfade(true)
            .build()
}
