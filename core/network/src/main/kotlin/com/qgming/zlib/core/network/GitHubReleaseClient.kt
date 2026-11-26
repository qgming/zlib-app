package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.ReleaseApk
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** A separate, anonymous transport: never accepts the EAPI client or an account/session. */
class GitHubReleaseClient internal constructor(
    private val endpoint: HttpUrl,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val networkHandle: () -> Long? = { null },
) {
    constructor(networkHandle: () -> Long? = { null }) : this(LATEST_RELEASE_URL.toHttpUrl(), networkHandle = networkHandle)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 503 && response.header("Retry-After") != null) {
                response.newBuilder().removeHeader("Retry-After").build()
            } else {
                response
            }
        }
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val requests = Mutex()
    private val rateLimits = mutableMapOf<Long?, AppError.Server>()
    private var previousRequestNetwork: Long? = null

    suspend fun latestRelease(): AppResult<AppRelease?> = fetch(endpoint, null) { parseRelease(it.jsonObject) }

    suspend fun releaseHistory(): AppResult<List<AppRelease>> {
        val url = endpoint.newBuilder().removePathSegment(endpoint.pathSegments.lastIndex)
            .addQueryParameter("per_page", "20").build()
        return fetch(url, emptyList()) { root ->
            require(root is JsonArray) { "Invalid GitHub history" }
            root.take(20).mapNotNull { parseRelease(it.jsonObject) }
        }
    }

    private suspend fun <T> fetch(url: HttpUrl, notFound: T, parse: (JsonElement) -> T): AppResult<T> = requests.withLock {
        val network = networkHandle()
        activeRateLimit(network, nowMillis())?.let { return@withLock AppResult.Failure(it) }
        if (previousRequestNetwork != network) client.connectionPool.evictAll()
        previousRequestNetwork = network
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "zhishi-zlib-app").build()
        try {
            val (result, limit) = client.newCall(request).awaitParsed { response ->
                val limit = parseRateLimit(response, nowMillis())
                val result = when {
                    response.code == 404 -> AppResult.Success(notFound)
                    !response.isSuccessful -> AppResult.Failure(
                        limit?.takeIf { it.code == response.code } ?: AppError.Server(response.code, "GitHub release request failed"),
                    )
                    else -> {
                        try {
                            require(!response.body.source().request(MAX_RESPONSE_BYTES + 1)) { "GitHub release response too large" }
                            AppResult.Success(parse(json.parseToJsonElement(response.body.string())))
                        } catch (_: IllegalArgumentException) {
                            AppResult.Failure(AppError.Parse("Invalid GitHub release"))
                        }
                    }
                }
                result to limit
            }
            if (limit != null) rateLimits[network?.takeIf { it == networkHandle() }] = limit
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            AppResult.Failure(AppError.Network(failure))
        }
    }

    private fun activeRateLimit(network: Long?, now: Long): AppError.Server? {
        rateLimits.entries.removeAll { (it.value.retryAtEpochMillis ?: 0L) <= now }
        return rateLimits.asSequence()
            .filter { (scope, _) -> network == null || scope == null || scope == network }
            .maxByOrNull { (_, limit) -> limit.retryAtEpochMillis ?: 0L }
            ?.value
    }

    private fun parseRateLimit(response: Response, now: Long): AppError.Server? {
        val remaining = response.header("X-RateLimit-Remaining")?.trim()?.toLongOrNull()
        val exhausted = remaining == 0L
        val retryAfter = response.header("Retry-After")?.trim()
        val rateLimited = response.code == 429 || (response.code == 403 && (
            exhausted || retryAfter != null || runCatching {
                val body = json.parseToJsonElement(response.peekBody(4096).string()).jsonObject
                body.stringOrNull("message").orEmpty().contains("rate limit", ignoreCase = true)
            }.getOrDefault(false)
        ))
        if (!exhausted && !rateLimited) return null
        val reset = response.header("X-RateLimit-Reset")?.trim()?.toLongOrNull()
            ?.takeIf { it in 1..Long.MAX_VALUE / 1000L }?.let { it * 1000L }
        val retryAt = retryAfter?.let { value ->
            val seconds = value.toLongOrNull()
            if (seconds != null) {
                seconds.takeIf { it in 1..(Long.MAX_VALUE - now) / 1000L }?.let { now + it * 1000L }
            } else {
                runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            }
        }
        val deadline = listOfNotNull(reset.takeIf { exhausted || (rateLimited && remaining == null) }, retryAt)
            .filter { it > now }.maxOrNull() ?: (now + 60_000L)
        return AppError.Server(if (rateLimited) response.code else 429, "GitHub API rate limited", deadline)
    }

    private fun parseRelease(root: JsonObject): AppRelease? {
        val draft = root["draft"]?.jsonPrimitive?.booleanOrNull ?: throw IllegalArgumentException("Missing release status")
        val prerelease = root["prerelease"]?.jsonPrimitive?.booleanOrNull ?: throw IllegalArgumentException("Missing release status")
        if (draft || prerelease) return null
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing release tag")
        val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // Construct the destination from a trusted base; ignore html_url and asset URLs.
        val page = RELEASES_URL.toHttpUrl().newBuilder().addPathSegment("tag").addPathSegment(tag).build()
        val apks = root.array("assets").orEmpty().mapNotNull { item ->
            val asset = item as? JsonObject ?: return@mapNotNull null
            val name = asset.stringOrNull("name") ?: return@mapNotNull null
            val size = asset.longOrNull("size") ?: return@mapNotNull null
            if (!name.endsWith(".apk", true) || '/' in name || '\\' in name || size !in 1..MAX_APK_BYTES || asset.stringOrNull("state") != "uploaded") return@mapNotNull null
            val url = RELEASES_URL.toHttpUrl().newBuilder().addPathSegment("download").addPathSegment(tag).addPathSegment(name).build()
            val digest = asset.stringOrNull("digest")?.takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }?.substringAfter(':')
            ReleaseApk(name, url.toString(), size, digest)
        }
        return AppRelease(tag, notes, page.toString(), root.stringOrNull("name") ?: tag, root.stringOrNull("published_at"), apks)
    }

    companion object {
        const val REPOSITORY_URL = "https://github.com/qgming/zlib-app"
        const val RELEASES_URL = "$REPOSITORY_URL/releases"
        const val AUTHOR_URL = "https://github.com/qgming"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/qgming/zlib-app/releases/latest"
        private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
        const val MAX_APK_BYTES = 500L * 1024 * 1024
    }
}
