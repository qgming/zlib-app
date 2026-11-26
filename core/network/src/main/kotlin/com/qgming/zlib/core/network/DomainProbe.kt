package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.DefaultAppDispatchers
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.model.DomainCheck
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Probes `/eapi/info/domains` (no credentials). Classification follows docs/EAPI.md §2.9.
 * Redirects are followed manually so loops and allowlist escapes are distinguishable.
 *
 * Reports the responding host separately from the requested host. Discovery never grants
 * permission to send credentials (ADR-102).
 */
class DomainProbe(
    client: OkHttpClient,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val timeoutMillis: Long = 10_000L,
    private val maxRedirects: Int = 3,
    private val json: Json = EapiJson.instance,
    /** Hosts the prober may contact at all (built-ins, mirrors, stored discoveries). */
    private val isAllowedHost: (String) -> Boolean = { DomainPolicy.isAllowed(it) },
    /** Approved hosts need no family hint from their anonymous response. */
    private val isTrustedHost: (String) -> Boolean = { DomainPolicy.isAllowed(it) },
    private val baseUrlFor: (String) -> String = { DomainPolicy.baseUrl(it) },
    private val requireHttps: Boolean = true,
    private val followForeignRedirects: Boolean = true,
    private val verificationCookieProvider: (HttpUrl) -> String? = { null },
) {

    private val probeClient: OkHttpClient = client.newBuilder()
        .oneShotTransport()
        .build()

    suspend fun probe(host: String): DomainCheck {
        val normalized = DomainPolicy.normalizeHostInput(host)
            ?: return DomainCheck(host = host, status = DomainStatus.INVALID, detail = appText(TextR.string.text_invalid_domain_format))
        if (!isAllowedHost(normalized)) {
            return DomainCheck(host = normalized, status = DomainStatus.INVALID, detail = appText(TextR.string.text_not_on_the_allowed_list))
        }
        return try {
            withContext(dispatchers.io) { follow(normalized) }
        } catch (_: InterruptedIOException) {
            DomainCheck(host = normalized, status = DomainStatus.NETWORK_ERROR, detail = appText(TextR.string.text_check_timed_out))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (io: IOException) {
            DomainCheck(host = normalized, status = DomainStatus.NETWORK_ERROR, detail = io.javaClass.simpleName)
        }
    }

    suspend fun probeAll(hosts: List<String>, concurrency: Int = 8): List<DomainCheck> = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        hosts.distinct().map { host ->
            async { semaphore.withPermit { probe(host) } }
        }.awaitAll()
    }

    private suspend fun follow(host: String): DomainCheck {
        var respondingHost = host
        val visited = linkedSetOf(host)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

        while (true) {
            val url = endpointUrl(respondingHost).toHttpUrlOrNull()
            if (url == null || requireHttps && (!DomainPolicy.isStandardHttps(url) || url.host != respondingHost)) {
                return DomainCheck(host, DomainStatus.INVALID, appText(TextR.string.text_invalid_redirect_target), respondingHost)
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw InterruptedIOException("Probe timeout")
            val call = probeClient.newCall(request(url, includeVerification = respondingHost == host))
            call.timeout().timeout(remaining, TimeUnit.NANOSECONDS)
            when (val step = call.executeParsedOnIo { response ->
                if (requireHttps && !response.request.url.hasSameOrigin(url)) {
                    ProbeStep.Done(DomainCheck(host, DomainStatus.INVALID, appText(TextR.string.text_invalid_redirect_target), respondingHost))
                } else {
                    interpret(host, respondingHost, visited, response)
                }
            }) {
                is ProbeStep.Done -> return step.check
                is ProbeStep.Redirect -> respondingHost = step.host
            }
        }
    }

    private fun interpret(host: String, respondingHost: String, visited: MutableSet<String>, response: Response): ProbeStep {
        fun failure(status: DomainStatus, detail: String) = ProbeStep.Done(DomainCheck(host, status, detail, respondingHost))
        if (!response.isRedirect) {
            val body = response.body.stringUpTo(MAX_RESPONSE_BYTES)
                ?: return failure(DomainStatus.HTTP_ERROR, appText(TextR.string.text_unexpected_response))
            return ProbeStep.Done(classify(host, response.code, body, respondingHost, requireFamily = !isTrustedHost(respondingHost)))
        }
        if (isDiamWallChallenge(response)) {
            return failure(DomainStatus.DIAMWALL, appText(TextR.string.text_diamwall_human_verification))
        }
        if (visited.size > maxRedirects) {
            return failure(DomainStatus.REDIRECT_LOOP, appText(TextR.string.text_more_than_redirects, maxRedirects))
        }
        val location = response.header("Location")
            ?: return failure(DomainStatus.HTTP_ERROR, appText(TextR.string.text_http_is_missing_location, response.code))
        val next = response.request.url.resolve(location)
            ?: return failure(DomainStatus.HTTP_ERROR, appText(TextR.string.text_cannot_resolve_redirect))
        if (requireHttps && !DomainPolicy.isStandardHttps(next)) {
            return failure(DomainStatus.INVALID, appText(TextR.string.text_invalid_redirect_target))
        }
        val nextHost = if (!requireHttps && next.hasSameOrigin(response.request.url)) respondingHost else DomainPolicy.normalizeHostInput(next.host)
        if (nextHost == null || !followForeignRedirects && !isTrustedHost(nextHost)) {
            return failure(DomainStatus.INVALID, appText(TextR.string.text_redirect_to_a_domain_that_is_not_allowed))
        }
        if (!visited.add(nextHost)) {
            return failure(DomainStatus.REDIRECT_LOOP, appText(TextR.string.text_redirect_loop))
        }
        return ProbeStep.Redirect(nextHost)
    }

    private fun isDiamWallChallenge(response: Response): Boolean =
        response.header("Server")?.contains("DiamWall", ignoreCase = true) == true ||
            response.headers("Set-Cookie").any { it.contains("__diamwall", ignoreCase = true) }

    /** Always probes the documented endpoint on the target host, never the site root. */
    private fun endpointUrl(host: String): String =
        "${baseUrlFor(host).trimEnd('/')}${EapiEndpoints.DOMAINS}"

    private fun request(url: HttpUrl, includeVerification: Boolean): Request = Request.Builder()
        .url(url)
        .get()
        .header("Accept", EapiHeaders.ACCEPT)
        .header("User-Agent", EapiHeaders.USER_AGENT)
        .apply {
            if (includeVerification) {
                DomainVerificationCookies.sanitizeCookieHeader(verificationCookieProvider(url))?.let { header("Cookie", it) }
            }
        }
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
                    val target = finalHost.takeUnless { it == host }
                    return DomainCheck(host, DomainStatus.HEALTHY, target?.let { appText(TextR.string.text_redirect, it) }.orEmpty(), finalHost, target)
                }
                return DomainCheck(host, DomainStatus.INVALID, appText(TextR.string.text_domain_family_verification_failed), finalHost)
            }
            if (success == 0) {
                return DomainCheck(
                    host,
                    DomainStatus.HTTP_ERROR,
                    "HTTP $code · success:0",
                    finalHost,
                )
            }
        }

        if (looksLikeHtml(body)) {
            return DomainCheck(host, DomainStatus.CHALLENGED, "HTML challenge", finalHost)
        }
        if (code in 200..299) {
            return DomainCheck(host, DomainStatus.HTTP_ERROR, appText(TextR.string.text_unexpected_response), finalHost)
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

    private sealed interface ProbeStep {
        data class Done(val check: DomainCheck) : ProbeStep
        data class Redirect(val host: String) : ProbeStep
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 256L * 1024
    }
}
