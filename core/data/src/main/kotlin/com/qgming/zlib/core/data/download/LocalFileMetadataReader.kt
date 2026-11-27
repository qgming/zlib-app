package com.qgming.zlib.core.data.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.CancellationSignal
import com.qgming.zlib.core.model.LocalFile
import com.qgming.zlib.core.model.LocalFileMetadata
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface LocalFileMetadataReader {
    fun cached(file: LocalFile): LocalFileMetadata?
    suspend fun read(file: LocalFile): LocalFileMetadata
    fun invalidate(treeUri: String)
    fun retain(treeUri: String, files: List<LocalFile>)
}

internal object NoLocalFileMetadataReader : LocalFileMetadataReader {
    override fun cached(file: LocalFile): LocalFileMetadata? = null
    override suspend fun read(file: LocalFile) = LocalFileMetadata()
    override fun invalidate(treeUri: String) = Unit
    override fun retain(treeUri: String, files: List<LocalFile>) = Unit
}

/** The version is provided by SAF; explicit refresh also invalidates providers with no timestamps. */
internal data class LocalFileVersion(
    val treeUri: String,
    val uri: String,
    val name: String,
    val sizeBytes: Long?,
    val modifiedAt: Long,
) {
    constructor(file: LocalFile) : this(file.treeUri, file.uri, file.name, file.sizeBytes, file.modifiedAt)
}

/**
 * Only one bounded private copy is open at a time. Nothing is written to the source directory.
 * Text results (including failed reads) are cached in memory; sampled covers use a private disk
 * cache whose content-based filenames also invalidate Coil's image cache when the image changes.
 */
internal class CachedEpubMetadataReader(
    private val directory: File,
    private val open: (LocalFile, CancellationSignal) -> InputStream?,
    private val writeCover: (ByteArray, File) -> Boolean = ::writeSampledCover,
) : LocalFileMetadataReader {
    private val lock = Any()
    private val reads = Mutex()
    private val cache = LinkedHashMap<LocalFileVersion, LocalFileMetadata>(32, 0.75f, true)
    private var generation = 0L
    private var writesSinceCleanup = CLEANUP_INTERVAL

    override fun cached(file: LocalFile): LocalFileMetadata? = synchronized(lock) {
        cache[LocalFileVersion(file)]?.let { metadata ->
            // The OS may clear private caches independently of the in-memory display metadata.
            val cover = metadata.coverUri
            if (cover != null && !runCatching { File(java.net.URI(cover)).isFile }.getOrDefault(false)) {
                metadata.copy(coverUri = null).also { cache[LocalFileVersion(file)] = it }
            } else metadata
        }
    }

    override fun invalidate(treeUri: String) = synchronized(lock) {
        generation++
        cache.keys.removeAll { it.treeUri == treeUri }
        Unit
    }

    override fun retain(treeUri: String, files: List<LocalFile>) = synchronized(lock) {
        val versions = files.filter { it.extension.equals("epub", ignoreCase = true) }.map(::LocalFileVersion).toSet()
        cache.keys.removeAll { it.treeUri != treeUri || it !in versions }
        Unit
    }

    override suspend fun read(file: LocalFile): LocalFileMetadata = reads.withLock {
        currentCoroutineContext().ensureActive()
        cached(file)?.let { return@withLock it }
        val startedGeneration = synchronized(lock) { generation }
        val metadata = try {
            readUncached(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            LocalFileMetadata()
        } catch (_: SecurityException) {
            LocalFileMetadata()
        } catch (_: IllegalArgumentException) {
            LocalFileMetadata()
        }
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (generation == startedGeneration) {
                cache[LocalFileVersion(file)] = metadata
                while (cache.size > MAX_METADATA_ENTRIES) cache.remove(cache.keys.first())
            }
        }
        metadata
    }

    private suspend fun readUncached(file: LocalFile): LocalFileMetadata {
        if (!file.extension.equals("epub", ignoreCase = true) ||
            (file.sizeBytes != null && file.sizeBytes !in 1..MAX_ARCHIVE_BYTES)
        ) return LocalFileMetadata()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Local metadata cache unavailable")
        val coroutine = currentCoroutineContext()
        val temporary = File.createTempFile("read-", ".epub", directory)
        val parsed = try {
            copyToTemporary(file, temporary)
            EpubMetadataParser.parse(temporary) { coroutine.ensureActive() }
        } finally {
            temporary.delete()
        }
        coroutine.ensureActive()
        val coverUri = try {
            parsed.cover?.let { bytes -> cacheCover(bytes) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return LocalFileMetadata(parsed.title, parsed.authors, coverUri)
    }

    private suspend fun copyToTemporary(file: LocalFile, temporary: File) = coroutineScope {
        val coroutine = currentCoroutineContext()
        val signal = CancellationSignal()
        val opened = AtomicReference<InputStream?>()
        // Keep the blocking work in a child. On cancellation, await's finally can cancel the
        // provider open and close a stalled pipe before coroutineScope joins its writer.
        val copying = async(Dispatchers.IO) {
            runInterruptible {
                coroutine.ensureActive()
                open(file, signal)?.use { input ->
                    opened.set(input)
                    coroutine.ensureActive()
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            coroutine.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            if (copied > MAX_ARCHIVE_BYTES) throw IOException("Local EPUB exceeds metadata limit")
                            output.write(buffer, 0, count)
                        }
                        if (file.sizeBytes != null && copied != file.sizeBytes) throw IOException("Local EPUB changed during read")
                    }
                } ?: throw IOException("Local EPUB unavailable")
            }
        }
        try {
            copying.await()
        } finally {
            if (!coroutine.isActive || !copying.isCompleted) {
                signal.cancel()
                runCatching { opened.getAndSet(null)?.close() }
            }
        }
    }

    private suspend fun cacheCover(bytes: ByteArray): String? {
        currentCoroutineContext().ensureActive()
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val target = File(directory, "$key.webp")
        if (!target.isFile) {
            val temporary = File.createTempFile("cover-", ".tmp", directory)
            try {
                if (!writeCover(bytes, temporary)) return null
                currentCoroutineContext().ensureActive()
                if (!temporary.renameTo(target) && !target.isFile) throw IOException("Local cover cache unavailable")
            } finally {
                temporary.delete()
            }
        }
        target.setLastModified(System.currentTimeMillis())
        if (++writesSinceCleanup >= CLEANUP_INTERVAL) {
            pruneCovers(target)
            writesSinceCleanup = 0
        }
        return target.toURI().toString()
    }

    private fun pruneCovers(keep: File) {
        val now = System.currentTimeMillis()
        val files = directory.listFiles().orEmpty()
        // Only the exact names created here are eligible for cleanup, never arbitrary local books.
        files.filter { (it.name.startsWith("read-") || it.name.startsWith("cover-")) && now - it.lastModified() > MAX_CACHE_AGE }
            .forEach { it.delete() }
        var total = 0L
        var count = 0
        files.filter { COVER_NAME.matches(it.name) }.sortedByDescending { if (it == keep) Long.MAX_VALUE else it.lastModified() }
            .forEach { entry ->
                val length = entry.length()
                if (entry != keep && (now - entry.lastModified() > MAX_CACHE_AGE || total + length > MAX_COVER_CACHE_BYTES || count >= MAX_METADATA_ENTRIES)) {
                    entry.delete()
                } else {
                    total += length
                    count++
                }
            }
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_METADATA_ENTRIES = 1024
        const val MAX_COVER_CACHE_BYTES = 64L * 1024 * 1024
        const val MAX_CACHE_AGE = 7L * 24 * 60 * 60 * 1000
        const val CLEANUP_INTERVAL = 16
        val COVER_NAME = Regex("[0-9a-f]{64}\\.webp")
    }
}

/** Decode a single sampled frame, with bounded dimensions, before caching any untrusted image. */
private fun writeSampledCover(bytes: ByteArray, target: File): Boolean {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width !in 1..32_768 || height !in 1..32_768 || width.toLong() * height > 100_000_000L) return false
    var sample = 1
    while (width / sample > 1024 || height / sample > 1024) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return false
    return try {
        if (bitmap.width.toLong() * bitmap.height > 4_194_304L) false
        else target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
    } finally {
        bitmap.recycle()
    }
}
