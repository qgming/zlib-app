package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppErrorException
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.DomainStatus
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.model.Session
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.CacheControl
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response

internal val PAPI_ID = Regex("[1-9][0-9]{0,19}")

internal fun invalidPapiParameters() = AppResult.Failure(AppError.Parse(appText(TextR.string.text_invalid_request_parameters)))

internal fun malformedPapiResponse(): Nothing =
    throw AppErrorException(AppError.Parse(appText(TextR.string.text_unexpected_server_response_please_retry_later)))

private class PapiRequestCredentials(val cookie: String)

/** One-shot transport shared by documented booklist and comment JSON requests. */
private fun restrictedBooklistClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
    .oneShotTransport()
    .callTimeout(30, TimeUnit.SECONDS)
    .addNetworkInterceptor { chain ->
        val original = chain.request()
        val cookie = original.tag(PapiRequestCredentials::class.java)?.cookie
        val request = original.newBuilder()
            .removeHeader(EapiHeaders.COOKIE)
            .removeHeader(EapiHeaders.REMIX_USER_ID)
            .removeHeader(EapiHeaders.REMIX_USER_KEY)
            .removeHeader(EapiHeaders.REMIX_USER_KEY_ALT)
            .removeHeader(EapiHeaders.SOURCE)
            .removeHeader("Authorization")
            // Do not restore a Cookie already removed by an upstream credential guard.
            .apply { if (cookie != null && original.header(EapiHeaders.COOKIE) != null) header(EapiHeaders.COOKIE, cookie) }
            .build()
        chain.proceed(request)
    }
    .build()

/** Same-host JSON transport using only the current session's two remix cookies and optional wall-clearance cookies. */
internal class PapiTransport(
    client: OkHttpClient,
    private val isAllowedHost: (String) -> Boolean,
    private val verificationCookieProvider: (HttpUrl) -> String? = { null },
    private val verificationUserAgentProvider: (HttpUrl) -> String? = { null },
) {
    private val transport = restrictedBooklistClient(client)

    suspend fun <T> request(
        session: Session,
        path: String,
        body: RequestBody? = null,
        query: HttpUrl.Builder.() -> Unit = {},
        createConfirmation: Boolean = false,
        allowEmptySuccess: Boolean = false,
        parse: (JsonObject) -> T,
    ): AppResult<T> {
        val baseCookie = sessionCookie(session) ?: return AppResult.Failure(AppError.NotLoggedIn)
        val host = session.domain
        if (DomainPolicy.normalizeHostInput(host) != host.lowercase() || !isAllowedHost(host)) {
            return AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID))
        }
        val url = DomainPolicy.baseUrl(host).toHttpUrlOrNull()?.newBuilder()?.encodedPath(path)?.apply(query)?.build()
            ?: return AppResult.Failure(AppError.DomainBlocked(DomainStatus.INVALID))
        val verificationCookie = DomainVerificationCookies.sanitizeCookieHeader(verificationCookieProvider(url))
        val cookie = listOfNotNull(baseCookie, verificationCookie).filter(String::isNotBlank).joinToString("; ")
        val userAgent = if (verificationCookie != null) {
            DomainVerificationCookies.sanitizeUserAgent(verificationUserAgentProvider(url)) ?: EapiHeaders.USER_AGENT
        } else {
            EapiHeaders.USER_AGENT
        }
        val request = Request.Builder().url(url)
            .cacheControl(NO_CACHE)
            .header("Accept", EapiHeaders.ACCEPT)
            .header("User-Agent", userAgent)
            .tag(PapiRequestCredentials::class.java, PapiRequestCredentials(cookie))
            .header(EapiHeaders.COOKIE, cookie)
            .apply { if (body != null) post(body) }
            .build()
        return try {
            transport.newCall(request).awaitParsed { response ->
                AppResult.Success(parse(envelope(response, createConfirmation, allowEmptySuccess)))
            }
        } catch (failure: AppErrorException) {
            AppResult.Failure(failure.error)
        } catch (failure: IOException) {
            AppResult.Failure(AppError.Network(failure))
        }
    }

    private fun envelope(response: Response, createConfirmation: Boolean, allowEmptySuccess: Boolean): JsonObject {
        if (response.code == 513 || response.code == 517 || response.header("Server").equals("DiamWall", true) ||
            (response.code in 300..399 && response.headers("Set-Cookie").any { it.trimStart().startsWith("__diamwall=", true) })
        ) {
            throw AppErrorException(AppError.DomainBlocked(DomainStatus.DIAMWALL))
        }
        if (response.code in 300..399) throw AppErrorException(AppError.DomainBlocked(DomainStatus.REDIRECT_LOOP))
        if (response.code == 429) throw AppErrorException(AppError.Unsupported(appText(TextR.string.text_papi_rate_limited)))
        val type = response.body.contentType()
        val body = response.body.stringUpTo(MAX_RESPONSE_BYTES) ?: malformedPapiResponse()
        if ((createConfirmation || allowEmptySuccess) && response.isSuccessful && body.isEmpty()) return JsonObject(emptyMap())
        // Live Z-Library PAPI endpoints often return valid JSON with `Content-Type: text/html; charset=UTF-8` (ADR-109).
        val root = jsonObject(body)
        if (root == null) {
            if (body.contains("diamwall", ignoreCase = true)) {
                throw AppErrorException(AppError.DomainBlocked(DomainStatus.DIAMWALL))
            }
            if (type?.subtype == "html" || looksLikeHtml(body)) {
                throw AppErrorException(AppError.DomainBlocked(DomainStatus.CHALLENGED))
            }
            if (response.code == 401) {
                throw AppErrorException(AppError.Unsupported(appText(TextR.string.text_papi_session_not_accepted)))
            }
            if (!response.isSuccessful) {
                throw AppErrorException(AppError.Server(response.code, ""))
            }
            malformedPapiResponse()
        }
        val success = root.intOrNull("success")
        val explicitError = root.obj("error")?.stringOrNull("message") ?: root.stringOrNull("error")
        val errorText = explicitError ?: root.stringOrNull("message")
        rejectAuthentication(response.code, root)
        val hasCreateError = createConfirmation &&
            (explicitError != null || !root.obj("error").isNullOrEmpty() || !root.array("error").isNullOrEmpty() ||
                success != 1 && errorText != null)
        if (!response.isSuccessful || success == 0 || hasCreateError) {
            val message = if (response.isSuccessful) appText(TextR.string.text_papi_request_refused) else ""
            throw AppErrorException(AppError.Server(response.code, message))
        }
        val hasCreatedList = createConfirmation && "success" !in root &&
            root.obj("readlist")?.stringOrNull("id")?.matches(PAPI_ID) == true
        if (success != 1 && !hasCreatedList) malformedPapiResponse()
        return root
    }

    private fun looksLikeHtml(body: String): Boolean {
        val trimmed = body.trimStart()
        return trimmed.startsWith("<") && (trimmed.contains("<html", true) || trimmed.contains("<!doctype", true))
    }

    private fun jsonObject(body: String): JsonObject? = try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun rejectAuthentication(code: Int, root: JsonObject?) {
        val errorText = root?.obj("error")?.stringOrNull("message") ?: root?.stringOrNull("error") ?: root?.stringOrNull("message")
        val authError = root?.intOrNull("success") != 1 && errorText != null && AUTH_ERRORS.any { errorText.contains(it, true) }
        if (code == 401 || authError) {
            throw AppErrorException(AppError.Unsupported(appText(TextR.string.text_papi_session_not_accepted)))
        }
    }

    private fun sessionCookie(session: Session): String? {
        if (!session.isValid) return null
        return session.cookieHeader
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 4L * 1024 * 1024
        val NO_CACHE = CacheControl.Builder().noCache().noStore().build()
        val AUTH_ERRORS = listOf("please login", "please log in", "please sign in", "invalid credentials", "session expired", "not authorized", "unauthenticated", "请登录", "请先登录")
    }
}
