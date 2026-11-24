package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.ReleaseApk
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
class GitHubReleaseClient internal constructor(private val endpoint: HttpUrl) {
    constructor() : this(LATEST_RELEASE_URL.toHttpUrl())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latestRelease(): AppResult<AppRelease?> {
        val request = Request.Builder().url(endpoint)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "zhishi-zlib-app")
            .build()
        return try {
            fetch(request, ::parse)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            AppResult.Failure(AppError.Network(failure))
        } catch (_: SerializationException) {
            AppResult.Failure(AppError.Parse("Invalid GitHub release"))
        } catch (_: IllegalArgumentException) {
            AppResult.Failure(AppError.Parse("Invalid GitHub release"))
        }
    }

    suspend fun releaseHistory(): AppResult<List<AppRelease>> {
        val url = endpoint.newBuilder().removePathSegment(endpoint.pathSegments.lastIndex)
            .addQueryParameter("per_page", "20").build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "zhishi-zlib-app").build()
        return try {
            fetch(request) { response ->
                when {
                    response.code == 404 -> AppResult.Success(emptyList())
                    !response.isSuccessful -> AppResult.Failure(AppError.Server(response.code, "GitHub release request failed"))
                    else -> {
                        val items = json.parseToJsonElement(response.body.string()) as? JsonArray
                            ?: return@fetch invalidRelease()
                        AppResult.Success(items.mapNotNull { parseRelease(it.jsonObject) })
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: IOException) {
            AppResult.Failure(AppError.Network(failure))
        } catch (_: IllegalArgumentException) {
            invalidRelease()
        }
    }

    private suspend fun <T> fetch(request: Request, parseResponse: (Response) -> AppResult<T>): AppResult<T> =
        client.newCall(request).awaitParsed(parseResponse)

    private fun parse(response: Response): AppResult<AppRelease?> {
        if (response.code == 404) return AppResult.Success(null)
        if (!response.isSuccessful) return AppResult.Failure(AppError.Server(response.code, "GitHub release request failed"))
        val root = json.parseToJsonElement(response.body.string()).jsonObject
        return AppResult.Success(parseRelease(root))
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

    private fun invalidRelease() = AppResult.Failure(AppError.Parse("Invalid GitHub release"))

    companion object {
        const val REPOSITORY_URL = "https://github.com/qgming/zlib-app"
        const val RELEASES_URL = "$REPOSITORY_URL/releases"
        const val AUTHOR_URL = "https://github.com/qgming"
        private const val LATEST_RELEASE_URL = "https://api.github.com/repos/qgming/zlib-app/releases/latest"
        const val MAX_APK_BYTES = 500L * 1024 * 1024
    }
}
