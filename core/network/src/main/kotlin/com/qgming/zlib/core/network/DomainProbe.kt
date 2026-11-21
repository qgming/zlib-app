package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.DefaultAppDispatchers
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.model.DomainCheck
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Probes `/eapi/info/domains` (no credentials). Classification follows docs/EAPI.md §2.9.
 * Redirects are followed manually so loops and allowlist escapes are distinguishable.
 */
class DomainProbe(
    client: OkHttpClient,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val timeoutMillis: Long = 10_000L,
    private val maxRedirects: Int = 3,
    private val json: Json = EapiJson.instance,
    private val isAllowedHost: (String) -> Boolean = { DomainPolicy.isAllowed(it) },
    private val baseUrlFor: (String) -> String = { DomainPolicy.baseUrl(it) },
    private val requireHttps: Boolean = true,
) {

    private val probeClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()

    suspend fun probe(host: String): DomainCheck {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return DomainCheck(host = host, status = DomainStatus.INVALID, detail = "域名格式无效")
        if (!isAllowedHost(normalized)) {
            return DomainCheck(host = normalized, status = DomainStatus.INVALID, detail = "不在允许列表")
        }
        return try {
            withContext(dispatchers.io) {
                withTimeout(timeoutMillis) { follow(normalized) }
            }
        } catch (timeout: TimeoutCancellationException) {
            DomainCheck(host = normalized, status = DomainStatus.NETWORK_ERROR, detail = "探测超时")
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            DomainCheck(host = normalized, status = DomainStatus.NETWORK_ERROR, detail = io.javaClass.simpleName)
        } catch (t: Throwable) {
            DomainCheck(host = normalized, status = DomainStatus.NETWORK_ERROR, detail = t.javaClass.simpleName)
        }
    }

    suspend fun probeAll(hosts: List<String>, concurrency: Int = 8): List<DomainCheck> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        hosts.distinct().map { host ->
            async { semaphore.withPermit { probe(host) } }
        }.awaitAll()
    }

    private fun follow(host: String): DomainCheck {
        val start = "${baseUrlFor(host).trimEnd('/')}${EapiEndpoints.DOMAINS}"
        val visited = linkedSetOf(start)
        var url = start
        var hops = 0

        while (true) {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", EapiHeaders.ACCEPT)
                .header("User-Agent", EapiHeaders.USER_AGENT)
                .build()

            probeClient.newCall(request).execute().use { response ->
                val code = response.code
                val body = response.body?.string().orEmpty()
                val finalHost = response.request.url.host

                if (response.isRedirect) {
                    if (hops >= maxRedirects) {
                        return DomainCheck(host, DomainStatus.REDIRECT_LOOP, "重定向超过 $maxRedirects 次", finalHost)
                    }
                    val location = response.header("Location")
                        ?: return DomainCheck(host, DomainStatus.HTTP_ERROR, "HTTP $code 缺少 Location", finalHost)
                    val next = response.request.url.resolve(location)
                        ?: return DomainCheck(host, DomainStatus.HTTP_ERROR, "无法解析重定向", finalHost)
                    if (requireHttps && !next.isHttps) {
                        return DomainCheck(host, DomainStatus.INVALID, "重定向到非 HTTPS", finalHost)
                    }
                    if (!isAllowedHost(next.host)) {
                        return DomainCheck(host, DomainStatus.INVALID, "重定向到非允许域名", finalHost)
                    }
                    if (!visited.add(next.toString())) {
                        return DomainCheck(host, DomainStatus.REDIRECT_LOOP, "重定向循环", finalHost)
                    }
                    url = next.toString()
                    hops++
                    continue
                }

                return classify(host, code, body, finalHost)
            }
        }
    }

    private fun classify(host: String, code: Int, body: String, finalHost: String): DomainCheck {
        if (code == 513 || code == 517 || body.contains("diamwall", ignoreCase = true)) {
            return DomainCheck(host, DomainStatus.DIAMWALL, "HTTP $code", finalHost)
        }

        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (code in 200..299 && root != null) {
            val success = root.intOrNull("success")
            if (success == 1 && root.array("domains") != null) {
                return DomainCheck(host, DomainStatus.HEALTHY, "", finalHost)
            }
            if (success == 0) {
                return DomainCheck(
                    host,
                    DomainStatus.HTTP_ERROR,
                    root.stringOrNull("error").orEmpty().ifBlank { "success:0" },
                    finalHost,
                )
            }
        }

        if (looksLikeHtml(body)) {
            return DomainCheck(host, DomainStatus.CHALLENGED, "HTML challenge", finalHost)
        }
        if (code in 200..299) {
            return DomainCheck(host, DomainStatus.HTTP_ERROR, "非预期响应", finalHost)
        }
        return DomainCheck(host, DomainStatus.HTTP_ERROR, "HTTP $code", finalHost)
    }

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<") &&
            (trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<!doctype", ignoreCase = true))
    }
}
