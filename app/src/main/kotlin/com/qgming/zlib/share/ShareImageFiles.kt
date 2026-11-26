package com.qgming.zlib.share

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ShareImageSaveOutcome { Saved, Failed, Incomplete }

/** Private PNG files. Only this directory is exposed by the dedicated sharing provider. */
internal class ShareImageFiles(private val directory: File) {
    fun write(now: Long = System.currentTimeMillis(), encode: (File) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create image cache")
        directory.listFiles().orEmpty().filter { file ->
            file.isFile && FILE_NAME.matches(file.name) && now - file.lastModified() > RETENTION_MILLIS
        }.forEach(File::delete)
        val file = File(directory, "${UUID.randomUUID()}.png")
        try {
            encode(file)
            if (!file.isFile || file.length() == 0L) throw IOException("Empty image")
            return file
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    fun contains(file: File): Boolean = file.parentFile?.canonicalFile == directory.canonicalFile &&
        FILE_NAME.matches(file.name) && file.isFile && file.length() > 0

    suspend fun save(context: Context, file: File?, destination: Uri): ShareImageSaveOutcome {
        if (destination.scheme != "content" || !DocumentsContract.isDocumentUri(context, destination)) {
            return ShareImageSaveOutcome.Failed
        }
        val resolver = context.contentResolver
        return saveCreatedDocument(
            file,
            open = { resolver.openOutputStream(destination, "wt") },
            remove = { DocumentsContract.deleteDocument(resolver, destination) },
        )
    }

    internal suspend fun saveCreatedDocument(
        file: File?,
        open: () -> OutputStream?,
        remove: () -> Boolean,
    ): ShareImageSaveOutcome = try {
        if (file == null || !contains(file)) throw IOException("Unavailable image")
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        val output = open() ?: throw IOException("Cannot open image destination")
        output.use { sink ->
            file.inputStream().use { input ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                }
                coroutine.ensureActive()
                sink.flush()
            }
        }
        ShareImageSaveOutcome.Saved
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { runCatching { remove() } }
        throw cancelled
    } catch (_: Exception) {
        if (runCatching { remove() }.getOrDefault(false)) ShareImageSaveOutcome.Failed else ShareImageSaveOutcome.Incomplete
    }

    companion object {
        const val DIRECTORY = "share-images"
        private const val RETENTION_MILLIS = 24L * 60 * 60 * 1_000
        private val FILE_NAME = Regex("[a-f0-9-]{36}\\.png")
    }
}
