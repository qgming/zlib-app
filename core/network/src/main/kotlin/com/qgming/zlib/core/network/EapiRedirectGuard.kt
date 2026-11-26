package com.qgming.zlib.core.network

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Marks EAPI JSON calls. The redirect guard only applies to these, never to file downloads. */
internal object EapiRequestMarker

internal class BlockedRedirectException : IOException("redirect blocked by allowlist")

internal class EapiRedirectGuardInterceptor(
    private val isAllowedUrl: (HttpUrl) -> Boolean,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val initial = chain.call().request()
        if (initial.tag(EapiRequestMarker::class.java) == null) return chain.proceed(request)
        if (!request.url.hasSameOrigin(initial.url) || !isAllowedUrl(request.url) || !request.url.encodedPath.startsWith("/eapi/")) {
            throw BlockedRedirectException()
        }
        val response = chain.proceed(request)
        if (!response.isRedirect) return response

        val location = response.header("Location") ?: return response
        val target = response.request.url.resolve(location)
        if (target == null || !target.hasSameOrigin(initial.url) || !isAllowedUrl(target) || !target.encodedPath.startsWith("/eapi/")) {
            response.close()
            throw BlockedRedirectException()
        }
        return response
    }
}

internal fun Request.Builder.markEapi(): Request.Builder =
    tag(EapiRequestMarker::class.java, EapiRequestMarker)
