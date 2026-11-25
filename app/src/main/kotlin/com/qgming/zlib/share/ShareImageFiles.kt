package com.qgming.zlib.share

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID

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

    fun copyTo(context: Context, file: File, destination: Uri) {
        if (!contains(file) || destination.scheme != "content") throw IOException("Unavailable image destination")
        // A document-provider URI is chosen explicitly by the user, never a raw filesystem path.
        context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: throw IOException("Cannot open image destination")
    }

    companion object {
        const val DIRECTORY = "share-images"
        private const val RETENTION_MILLIS = 24L * 60 * 60 * 1_000
        private val FILE_NAME = Regex("[a-f0-9-]{36}\\.png")
    }
}
