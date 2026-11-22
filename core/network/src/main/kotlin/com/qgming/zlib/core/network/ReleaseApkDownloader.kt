package com.qgming.zlib.core.network

import com.qgming.zlib.core.model.ReleaseApk
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Anonymous release transfer, separate from book downloads and their redirect policy. */
class ReleaseApkDownloader internal constructor(private val allowUrl: (HttpUrl, Boolean) -> Boolean) {
    constructor() : this(::allowedReleaseAssetUrl)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES).build()

    suspend fun download(asset: ReleaseApk, target: File, progress: (Long, Long) -> Unit) {
        val initial = asset.url.toHttpUrlOrNull()
        require(initial != null && allowUrl(initial, true)) { "Invalid release asset URL" }
        require(asset.size in 1..GitHubReleaseClient.MAX_APK_BYTES) { "Invalid release size" }
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val active = AtomicReference<Call?>()
                continuation.invokeOnCancellation { active.get()?.cancel() }
                fun fetch(url: HttpUrl, hops: Int) {
                    if (!continuation.isActive) { finished.complete(Unit); return }
                    val call = client.newCall(Request.Builder().url(url).header("User-Agent", "zhishi-zlib-app").build())
                    active.set(call)
                    if (!continuation.isActive) { call.cancel(); finished.complete(Unit); return }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            finished.complete(Unit)
                            continuation.resumeWithException(e)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            var redirected = false
                            try {
                                response.use { r ->
                                    if (r.code in listOf(301, 302, 303, 307, 308)) {
                                        val next = r.header("Location")?.let(url::resolve)
                                        if (hops >= 5 || next == null || !allowUrl(next, false)) throw IOException("Invalid release redirect")
                                        redirected = true
                                        fetch(next, hops + 1)
                                        return
                                    }
                                    if (!r.isSuccessful) throw IOException("Release HTTP ${r.code}")
                                    val length = r.body.contentLength()
                                    if (length > 0 && length != asset.size) throw IOException("Release length mismatch")
                                    val digest = MessageDigest.getInstance("SHA-256")
                                    var written = 0L
                                    var lastProgress = 0L
                                    target.outputStream().buffered().use { output ->
                                        r.body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                if (!continuation.isActive) throw IOException("Download cancelled")
                                                val read = input.read(buffer)
                                                if (read == -1) break
                                                written += read
                                                if (written > asset.size) throw IOException("Release exceeds declared length")
                                                output.write(buffer, 0, read)
                                                digest.update(buffer, 0, read)
                                                val now = System.nanoTime()
                                                if (now - lastProgress > 150_000_000 || written == asset.size) {
                                                    progress(written, asset.size)
                                                    lastProgress = now
                                                }
                                            }
                                        }
                                    }
                                    if (written != asset.size) throw IOException("Incomplete release")
                                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                    if (asset.sha256 != null && !actual.equals(asset.sha256, true)) throw IOException("Release checksum mismatch")
                                    continuation.resume(Unit)
                                }
                            } catch (failure: Exception) {
                                continuation.resumeWithException(failure)
                            } finally {
                                if (!redirected) finished.complete(Unit)
                            }
                        }
                    })
                }
                fetch(initial, 0)
            }
        } finally {
            // A cancellation must join the writer before its owner deletes or reuses the file.
            withContext(NonCancellable) { finished.await() }
        }
    }
}

internal fun allowedReleaseAssetUrl(url: HttpUrl, initial: Boolean = false): Boolean {
    if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) return false
    if (url.host == "github.com") return url.encodedPath.startsWith("/qgming/zlib-app/releases/download/")
    return !initial && url.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
}
