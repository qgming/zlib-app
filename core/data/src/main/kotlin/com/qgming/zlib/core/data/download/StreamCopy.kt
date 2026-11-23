package com.qgming.zlib.core.data.download

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A failure while reading the network body (retryable as a network error). */
internal class DownloadReadException(cause: IOException) : IOException(cause.message, cause)

/** A failure while writing to the SAF document (storage, never auto-retried). */
internal class DownloadWriteException(cause: IOException) : IOException(cause.message, cause)

internal class DownloadIncompleteException(
    val expected: Long,
    val actual: Long,
) : IOException("下载不完整：预期 $expected 字节，实际 $actual 字节")

internal class DownloadEmptyException : IOException("下载内容为空")

/**
 * Copies exactly one response body (or one SAF document) with cooperative cancellation and an
 * exact length check. It deliberately does not swallow [kotlinx.coroutines.CancellationException].
 */
internal object StreamCopy {
    private const val BUFFER_SIZE = 64 * 1024

    suspend fun copy(
        input: InputStream?,
        output: OutputStream?,
        expectedBytes: Long?,
        onProgress: suspend (written: Long) -> Unit = {},
    ): Long {
        if (input == null) throw IOException("无法读取下载内容")
        if (output == null) throw IOException("无法写入目标文件")

        var written = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = try {
                input.read(buffer)
            } catch (io: IOException) {
                throw DownloadReadException(io)
            }
            if (read < 0) break
            currentCoroutineContext().ensureActive()
            try {
                output.write(buffer, 0, read)
            } catch (io: IOException) {
                throw DownloadWriteException(io)
            }
            written += read
            onProgress(written)
        }
        try {
            output.flush()
        } catch (io: IOException) {
            throw DownloadWriteException(io)
        }
        if (written == 0L) throw DownloadEmptyException()
        val expected = expectedBytes?.takeIf { it >= 0 }
        if (expected != null && written != expected) {
            throw DownloadIncompleteException(expected, written)
        }
        return written
    }
}
