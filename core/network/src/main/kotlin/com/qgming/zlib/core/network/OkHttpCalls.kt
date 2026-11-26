package com.qgming.zlib.core.network

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody

internal suspend fun <T> Call.awaitParsed(parse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(parse) }
            result.fold(continuation::resume, continuation::resumeWithException)
        }
    })
}

internal suspend fun <T> Call.executeParsedOnIo(parse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    if (continuation.isActive) {
        runCatching { execute().use(parse) }.fold(continuation::resume, continuation::resumeWithException)
    }
}

internal fun HttpUrl.hasSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

internal fun ResponseBody.stringUpTo(maxBytes: Long): String? =
    if (source().request(maxBytes + 1)) null else string()

internal fun OkHttpClient.Builder.oneShotTransport(): OkHttpClient.Builder =
    authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503 && response.header("Retry-After") != null) {
                response.newBuilder().removeHeader("Retry-After").build()
            } else {
                response
            }
        }
