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
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Probes `/eapi/info/domains` (no credentials). Classification follows docs/EAPI.md §2.9.
 * Redirects are followed manually so loops and allowlist escapes are distinguishable.
 *
 * A redirect to a host outside the allowlist is not trusted blindly (ADR-065): the target chain is
 * probed without credentials and only reported as [DomainCheck.redirectTarget] when its JSON
 * handshake proves a Z-Library backend. Hosts that are merely probe candidates (previously
 * discovered) must prove the same thing on every probe.
 */
class DomainProbe(
    client: OkHttpClient,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val timeoutMillis: Long = 10_000L,
    private val maxRedirects: Int = 3,
    private val json: Json = EapiJson.instance,
    /** Hosts the prober may contact at all (built-ins, mirrors, stored discoveries). */
    private val isAllowedHost: (String) -> Boolean = { DomainPolicy.isAllowed(it) },
    /** Static trust (built-in suffixes and user mirrors); everything else needs proof (ADR-065). */
    private val isTrustedHost: (String) -> Boolean = { DomainPolicy.isAllowed(it) },
    private val baseUrlFor: (String) -> String = { DomainPolicy.baseUrl(it) },
    private val requireHttps: Boolean = true,
    private val followForeignRedirects: Boolean = true,
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
        // A discovered host is only a probe candidate: require the same family proof as a target.
        val requireFamily = !isTrustedHost(normalized)
        // A redirect target needs its own hop budget, so the wall clock covers both stages.
        val budget = if (followForeignRedirects) timeoutMillis * 2 else timeoutMillis
        return try {
            withContext(dispatchers.io) {
                withTimeout(budget) { follow(normalized, requireFamily) }
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

    private fun follow(host: String, requireFamily: Boolean = false): DomainCheck {
        var url = endpointUrl(host)
        val visited = linkedSetOf(host)
        var hops = 0

        while (true) {
            probeClient.newCall(request(url)).execute().use { response ->
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
                    if (!next.isHttps && requireHttps) {
                        return DomainCheck(host, DomainStatus.INVALID, "重定向到非 HTTPS", finalHost)
                    }
                    // DiamWall answers the first hit with a 307 to the same URL plus a __diamwall
                    // cookie; only a JS-capable browser gets past it (EAPI.md §2.9).
                    if (isDiamWallChallenge(response)) {
                        return DomainCheck(host, DomainStatus.DIAMWALL, "DiamWall 人机验证", finalHost)
                    }
                    // Only statically trusted hosts are followed as-is; a probe candidate or a foreign
                    // host must pass the family proof and is reported as a redirect target (ADR-065).
                    if (!isTrustedHost(next.host)) {
                        if (!followForeignRedirects) {
                            return DomainCheck(host, DomainStatus.INVALID, "重定向到非允许域名", finalHost)
                        }
                        return followForeignHost(redirector = host, candidate = next.host)
                    }
                    if (!visited.add(next.host)) {
                        return DomainCheck(host, DomainStatus.REDIRECT_LOOP, "重定向循环", finalHost)
                    }
                    url = urlFor(next)
                    hops++
                    continue
                }

                return classify(host, code, body, finalHost, requireFamily)
            }
        }
    }

    /**
     * Walks the redirect chain of a host outside the allowlist without any credentials and only
     * accepts it when `/eapi/info/domains` answers with `success:1` and advertises at least one
     * built-in domain family. Failure is reported on the redirector so the UI keeps its identity.
     */
    private fun followForeignHost(redirector: String, candidate: String): DomainCheck {
        val start = DomainPolicy.normalizeHostInput(candidate)
            ?: return DomainCheck(redirector, DomainStatus.INVALID, "重定向目标格式无效", candidate)

        var url = endpointUrl(start)
        var host = start
        val visited = linkedSetOf(start)
        var hops = 0

        while (true) {
            val response = try {
                probeClient.newCall(request(url)).execute()
            } catch (io: IOException) {
                return DomainCheck(redirector, DomainStatus.NETWORK_ERROR, "重定向目标 $start 网络不可达", start)
            }

            response.use {
                val code = it.code
                val body = it.body?.string().orEmpty()

                if (it.isRedirect) {
                    if (hops >= maxRedirects) {
                        return DomainCheck(redirector, DomainStatus.REDIRECT_LOOP, "重定向目标 $start 重定向过多", host)
                    }
                    if (isDiamWallChallenge(it)) {
                        return DomainCheck(redirector, DomainStatus.DIAMWALL, "重定向目标 $start DiamWall 人机验证", host)
                    }
                    val location = it.header("Location")
                        ?: return DomainCheck(
                            redirector, DomainStatus.HTTP_ERROR,
                            "重定向目标 $start 缺少 Location", host,
                        )
                    val next = it.request.url.resolve(location)
                        ?: return DomainCheck(
                            redirector, DomainStatus.HTTP_ERROR,
                            "重定向目标 $start 无法解析", host,
                        )
                    if (!next.isHttps && requireHttps) {
                        return DomainCheck(redirector, DomainStatus.INVALID, "重定向目标 $start 不是 HTTPS", host)
                    }
                    val nextHost = DomainPolicy.normalizeHostInput(next.host)
                        ?: return DomainCheck(redirector, DomainStatus.INVALID, "重定向目标格式无效", host)
                    if (!visited.add(nextHost)) {
                        return DomainCheck(redirector, DomainStatus.REDIRECT_LOOP, "重定向目标 $start 循环", host)
                    }
                    url = urlFor(next)
                    host = nextHost
                    hops++
                    continue
                }

                return classifyForeign(redirector, start, code, body, host)
            }
        }
    }

    /** DiamWall stamps its challenge with `Server: DiamWall` or a `__diamwall` cookie. */
    private fun isDiamWallChallenge(response: okhttp3.Response): Boolean =
        response.header("Server")?.contains("DiamWall", ignoreCase = true) == true ||
            response.headers("Set-Cookie").any { it.contains("__diamwall", ignoreCase = true) }

    /** Always probes the documented endpoint on the target host, never the site root. */
    private fun endpointUrl(host: String): String =
        "${baseUrlFor(host).trimEnd('/')}${EapiEndpoints.DOMAINS}"

    /**
     * A redirector usually points at the site root (e.g. `https://zh.us101.ru/`); the EAPI probe must
     * still hit `/eapi/info/domains` there. Redirects that carry a real path are followed verbatim.
     */
    private fun urlFor(next: HttpUrl): String =
        if (next.encodedPath.isBlank() || next.encodedPath == "/") endpointUrl(next.host) else next.toString()

    private fun classifyForeign(
        redirector: String,
        target: String,
        code: Int,
        body: String,
        finalHost: String,
    ): DomainCheck {
        if (code == 513 || code == 517 || body.contains("diamwall", ignoreCase = true)) {
            return DomainCheck(redirector, DomainStatus.DIAMWALL, "重定向目标 $target 被 DiamWall 拦截", finalHost)
        }

        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (code in 200..299 && root != null) {
            val success = root.intOrNull("success")
            val domains = domainList(root)
            if (success == 1 && domains != null) {
                return if (domains.any(DomainPolicy::isKnownDomainFamily)) {
                    DomainCheck(
                        host = redirector,
                        status = DomainStatus.HEALTHY,
                        detail = "重定向 → $finalHost",
                        finalHost = finalHost,
                        redirectTarget = finalHost,
                    )
                } else {
                    DomainCheck(redirector, DomainStatus.INVALID, "重定向目标 $target 未通过校验", finalHost)
                }
            }
        }

        if (looksLikeHtml(body)) {
            return DomainCheck(redirector, DomainStatus.CHALLENGED, "重定向目标 $target 需要人机验证", finalHost)
        }
        if (code in 200..299) {
            return DomainCheck(redirector, DomainStatus.HTTP_ERROR, "重定向目标 $target 响应异常", finalHost)
        }
        return DomainCheck(redirector, DomainStatus.HTTP_ERROR, "重定向目标 $target HTTP $code", finalHost)
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .get()
        .header("Accept", EapiHeaders.ACCEPT)
        .header("User-Agent", EapiHeaders.USER_AGENT)
        .build()

    private fun classify(host: String, code: Int, body: String, finalHost: String, requireFamily: Boolean = false): DomainCheck {
        if (code == 513 || code == 517 || body.contains("diamwall", ignoreCase = true)) {
            return DomainCheck(host, DomainStatus.DIAMWALL, "HTTP $code", finalHost)
        }

        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (code in 200..299 && root != null) {
            val success = root.intOrNull("success")
            val domains = domainList(root)
            if (success == 1 && domains != null) {
                if (!requireFamily || domains.any(DomainPolicy::isKnownDomainFamily)) {
                    return DomainCheck(host, DomainStatus.HEALTHY, "", finalHost)
                }
                return DomainCheck(host, DomainStatus.INVALID, "未通过域名家族校验", finalHost)
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

    /** `domains` is a list of objects with a `domain` field, but tolerate plain strings. */
    private fun domainList(root: JsonObject): List<String>? =
        root.array("domains")?.mapNotNull { element ->
            when (element) {
                is JsonObject -> element.stringOrNull("domain")
                else -> element.stringOrNull()
            }
        }

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<") &&
            (trimmed.contains("<html", ignoreCase = true) || trimmed.contains("<!doctype", ignoreCase = true))
    }
}
