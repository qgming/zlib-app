package com.qgming.zlib.core.data.download

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Arrays
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class StreamCopyTest {
    @Test
    fun `copies every byte and reports the exact count`() = runBlocking {
        val data = ByteArray(150_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        val written = StreamCopy.copy(ByteArrayInputStream(data), output, data.size.toLong())

        assertEquals(data.size.toLong(), written)
        assertContentEquals(data, output.toByteArray())
    }

    @Test
    fun `a short body is rejected instead of being treated as complete`() = runBlocking {
        val failure = assertFailsWith<DownloadIncompleteException> {
            StreamCopy.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), 20)
        }
        assertEquals(20L, failure.expected)
        assertEquals(10L, failure.actual)
    }

    @Test
    fun `a body longer than declared is rejected`() = runBlocking {
        val failure = assertFailsWith<DownloadIncompleteException> {
            StreamCopy.copy(ByteArrayInputStream(ByteArray(30)), ByteArrayOutputStream(), 20)
        }
        assertEquals(20L, failure.expected)
        assertEquals(30L, failure.actual)
    }

    @Test
    fun `an empty body is rejected`(): Unit = runBlocking {
        assertFailsWith<DownloadEmptyException> {
            StreamCopy.copy(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), null)
        }
    }

    @Test
    fun `null streams fail cleanly`(): Unit = runBlocking {
        assertFailsWith<IOException> {
            StreamCopy.copy(null, ByteArrayOutputStream(), 1)
        }
        assertFailsWith<IOException> {
            StreamCopy.copy(ByteArrayInputStream(byteArrayOf(1)), null, 1)
        }
    }

    @Test
    fun `read failures and write failures keep their category`(): Unit = runBlocking {
        val unreadable = object : InputStream() {
            override fun read(): Int = throw IOException("network")
        }
        assertFailsWith<DownloadReadException> {
            StreamCopy.copy(unreadable, ByteArrayOutputStream(), 1)
        }

        val unwritable = object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("disk")
            }
        }
        assertFailsWith<DownloadWriteException> {
            StreamCopy.copy(ByteArrayInputStream(byteArrayOf(1)), unwritable, 1)
        }
    }

    @Test
    fun `copy is cancellable and does not report success`() = runBlocking {
        val total = 1_000_000
        val output = ByteArrayOutputStream()
        val job = launch(Dispatchers.Default) {
            StreamCopy.copy(
                SlowInputStream(total = total, chunk = 4_096, delayMillis = 2),
                output,
                total.toLong(),
            )
        }
        delay(40)
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertTrue(output.size() < total, "copied=${output.size()}")
    }

    private class SlowInputStream(
        private val total: Int,
        private val chunk: Int,
        private val delayMillis: Long,
    ) : InputStream() {
        private var position = 0

        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= total) return -1
            Thread.sleep(delayMillis)
            val count = minOf(chunk, length, total - position)
            Arrays.fill(buffer, offset, offset + count, 1.toByte())
            position += count
            return count
        }
    }
}
