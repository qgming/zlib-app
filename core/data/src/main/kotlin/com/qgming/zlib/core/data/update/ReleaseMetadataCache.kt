package com.qgming.zlib.core.data.update

import android.util.AtomicFile
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.network.GitHubReleaseClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class ReleaseCacheKind { Latest, History }

internal interface ReleaseMetadataCache {
    suspend fun read(kind: ReleaseCacheKind): List<AppRelease>
    suspend fun write(kind: ReleaseCacheKind, releases: List<AppRelease>)
}

internal class FileReleaseMetadataCache(
    private val directory: File,
    private val io: CoroutineDispatcher,
) : ReleaseMetadataCache {
    private val mutex = Mutex()

    override suspend fun read(kind: ReleaseCacheKind): List<AppRelease> = withContext(io) {
        mutex.withLock {
            try {
                val bytes = file(kind).openRead().use { it.readNBytes(MAX_BYTES + 1) }
                if (bytes.size > MAX_BYTES) emptyList() else decodeReleaseSnapshot(bytes)
            } catch (_: IOException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
    }

    override suspend fun write(kind: ReleaseCacheKind, releases: List<AppRelease>) = withContext(io) {
        mutex.withLock {
            val encoded = encodeReleaseSnapshot(releases)
            if (encoded.size > MAX_BYTES) return@withLock
            val destination = file(kind)
            try {
                val stream = destination.startWrite()
                try {
                    stream.write(encoded)
                    destination.finishWrite(stream)
                } catch (failure: IOException) {
                    destination.failWrite(stream)
                    throw failure
                }
            } catch (_: IOException) {
            }
        }
    }

    private fun file(kind: ReleaseCacheKind) = AtomicFile(File(directory, when (kind) {
        ReleaseCacheKind.Latest -> "github-latest-release-v1.json"
        ReleaseCacheKind.History -> "github-release-history-v1.json"
    }))

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}

@Serializable
private data class CachedRelease(val tag: String, val notes: String, val title: String, val publishedAt: String?) {
    fun toModel() = AppRelease(tag, notes, GitHubReleaseClient.RELEASES_URL, title, publishedAt)
}

internal fun encodeReleaseSnapshot(releases: List<AppRelease>): ByteArray =
    Json.encodeToString(releases.take(20).map { CachedRelease(it.tag, it.notes, it.title, it.publishedAt) }).encodeToByteArray()

internal fun decodeReleaseSnapshot(encoded: ByteArray): List<AppRelease> =
    Json.decodeFromString<List<CachedRelease>>(encoded.decodeToString()).take(20).map(CachedRelease::toModel)
