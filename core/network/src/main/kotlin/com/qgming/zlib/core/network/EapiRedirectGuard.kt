package com.qgming.zlib.core.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Marks EAPI JSON calls. The redirect guard only applies to these, never to file downloads. */
internal object EapiRequestMarker

internal class BlockedRedirectException : IOException("redirect blocked by allowlist")

/**
 * EAPI endpoints must never accept a redirect to a host outside the allowlist: a 307/308 also
 * replays the form body (which may contain the password). Registered as a network interceptor
 * so it sees every hop before OkHttp follows it.
 */
class EapiRedirectGuardInterceptor(
    private val isAllowedUrl: (HttpUrl) -> Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (chain.call().request().tag(EapiRequestMarker::class.java) == null) return response
        if (!response.isRedirect) return response

        val location = response.header("Location") ?: return response
        val target = response.request.url.resolve(location)
        if (target == null || !target.isHttps || !isAllowedUrl(target)) {
            response.close()
            throw BlockedRedirectException()
        }
        return response
    }
}

internal fun Request.Builder.markEapi(): Request.Builder =
    tag(EapiRequestMarker::class.java, EapiRequestMarker)
