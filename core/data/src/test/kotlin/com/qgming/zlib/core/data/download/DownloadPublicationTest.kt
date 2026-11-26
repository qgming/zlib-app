package com.qgming.zlib.core.data.download

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class DownloadPublicationTest {
    private val content = "test book bytes"
    private val part = "part"
    private val pending = PendingDownloadPublication(
        "123:sample", 123, "tree", part, "Title.epub", content.length.toLong(), downloadSha256(content),
    )

    @Test
    fun `rename already completed before its journal update can be recovered without a new write`() = runTest {
        val files = MemoryFiles()
        files.add(part, pending.targetName, content)
        val recovered = DownloadPublication(files) {}.recover(pending)
        assertEquals(part, assertNotNull(recovered).targetUri)
        assertEquals(PublicationStep.Complete, recovered.step)
        assertEquals(0, files.writes)
    }

    @Test
    fun `partial copy stays pending and resumes with the same recorded target`() = runTest {
        val files = MemoryFiles()
        files.add(part, DownloadFileNames.partName(pending.taskId), content, rename = false)
        var durable = pending
        val publisher = DownloadPublication(files) { durable = it }
        files.beforeWrite = { assertEquals(it, durable.targetUri) }
        files.interruptWrite = true
        assertFailsWith<CancellationException> { publisher.publish(pending) }
        assertEquals(PublicationStep.Copy, durable.step)
        assertNull(publisher.recover(durable))
        assertTrue(files.data(part).isNotEmpty())

        files.interruptWrite = false
        val completed = publisher.publish(durable)
        assertEquals(1, files.created)
        assertContentEquals(content.toByteArray(), files.data(assertNotNull(completed.targetUri)))
        assertEquals(PublicationStep.Complete, durable.step)
        assertEquals(durable, publisher.recover(completed))
    }

    @Test
    fun `copy refuses to overwrite a target that no longer has the expected bytes`() = runTest {
        val files = MemoryFiles()
        files.add(part, DownloadFileNames.partName(pending.taskId), content)
        files.add("target", pending.targetName, "user's file")
        val publisher = DownloadPublication(files) {}
        val copy = pending.copy(step = PublicationStep.Copy, targetUri = "target")
        assertFailsWith<IOException> { publisher.publish(copy) }
        assertEquals(0, files.writes)
        assertContentEquals("user's file".toByteArray(), files.data("target"))
    }

    @Test
    fun `copy recovers a completely written target without overwriting it`() = runTest {
        val files = MemoryFiles()
        files.add("different-id", pending.targetName, content)
        val recovered = DownloadPublication(files) {}.recover(pending.copy(step = PublicationStep.Copy, targetUri = "different-id"))
        assertEquals("different-id", recovered?.targetUri)
        assertEquals(0, files.writes)
    }

    private class MemoryFiles : DownloadPublicationFiles {
        private data class Document(val name: String, val bytes: ByteArray, val rename: Boolean)

        private val documents = mutableMapOf<String, Document>()
        var interruptWrite = false
        var beforeWrite: (String) -> Unit = {}
        var created = 0
            private set
        var writes = 0
            private set

        fun add(uri: String, name: String, value: String, rename: Boolean = true) {
            documents[uri] = Document(name, value.toByteArray(), rename)
        }

        fun data(uri: String): ByteArray = documents.getValue(uri).bytes

        override fun children(): List<DownloadDocument> = documents.map { (uri, file) -> DownloadDocument(uri, file.name, file.rename) }

        override fun rename(part: DownloadDocument, name: String): String = part.uri.also {
            documents[it] = documents.getValue(it).copy(name = name)
        }

        override fun create(name: String): String = "created-${++created}".also { add(it, name, "") }

        override fun read(uri: String) = data(uri).inputStream()

        override fun write(uri: String): OutputStream {
            beforeWrite(uri)
            writes++
            return object : ByteArrayOutputStream() {
                override fun write(buffer: ByteArray, offset: Int, count: Int) {
                    super.write(buffer, offset, if (interruptWrite) 3 else count)
                    if (interruptWrite) throw CancellationException("copy interrupted")
                }

                override fun close() {
                    documents[uri] = documents.getValue(uri).copy(bytes = toByteArray())
                }
            }
        }

        override fun delete(uri: String): Boolean = documents.remove(uri) != null
    }
}
