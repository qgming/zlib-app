package com.qgming.zlib.core.network

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Strips credentials on every network hop that either leaves the allowlist or crosses to a
 * different origin (including CDN redirects). Registered as a *network* interceptor so it also
 * sees redirect follow-ups, which application interceptors do not.
 *
 * See AGENTS.md §3.3 and docs/TESTING.md §2.1.
 */
class CredentialStripInterceptor(
    private val isAllowedUrl: (HttpUrl) -> Boolean = { DomainPolicy.isAllowed(it.host) },
    private val requireHttps: Boolean = true,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val initial = chain.call().request().url
        if ((!requireHttps || DomainPolicy.isStandardHttps(request.url)) && request.url.hasSameOrigin(initial) && isAllowedUrl(request.url)) {
            return chain.proceed(request)
        }
        val clean = request.newBuilder()
            .removeHeader("Cookie")
            .removeHeader("remix-userid")
            .removeHeader("remix-userkey")
            .removeHeader("remix-user-key")
            .removeHeader("Authorization")
            .build()
        return chain.proceed(clean)
    }
}
