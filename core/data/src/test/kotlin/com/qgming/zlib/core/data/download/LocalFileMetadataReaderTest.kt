package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.model.LocalFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class LocalFileMetadataReaderTest {
    @get:Rule val folder = TemporaryFolder()
    private val file = LocalFile("tree", "uri", "downloaded-file.epub", "epub", modifiedAt = 1)

    @Test
    fun `embedded display metadata is cached by exact URI size and timestamp and temporary books are removed`() = runTest {
        val directory = folder.newFolder()
        var opened = 0
        val reader = CachedEpubMetadataReader(directory, { _, _ -> opened++; epub("Book title").inputStream() })
        val first = reader.read(file)
        assertEquals("Book title", first.title)
        assertEquals("An author", first.authors)
        assertEquals(first, reader.read(file))
        assertEquals(1, opened)
        assertEquals(first, reader.cached(file))
        assertNull(reader.cached(file.copy(uri = "different")))
        assertNull(reader.cached(file.copy(sizeBytes = 123)))
        assertNull(reader.cached(file.copy(modifiedAt = 2)))
        assertNull(reader.cached(file.copy(treeUri = "other")))
        assertEquals(0, directory.listFiles().orEmpty().size)
    }

    @Test
    fun `explicit refresh and changed file set invalidate metadata including unknown provider versions`() = runTest {
        val directory = folder.newFolder()
        var title = "Before"
        var opened = 0
        val reader = CachedEpubMetadataReader(directory, { _, _ -> opened++; epub(title).inputStream() })
        assertEquals("Before", reader.read(file).title)
        title = "After"
        reader.invalidate("tree")
        assertNull(reader.cached(file))
        assertEquals("After", reader.read(file).title)
        reader.retain("tree", listOf(file))
        assertNotNull(reader.cached(file))
        reader.retain("tree", listOf(file.copy(modifiedAt = 2)))
        assertNull(reader.cached(file))
        reader.read(file.copy(modifiedAt = 2))
        reader.retain("new-tree", emptyList())
        assertNull(reader.cached(file.copy(modifiedAt = 2)))
        assertEquals(3, opened)
    }

    @Test
    fun `sampled covers use content identity and a private cache while source bytes are unchanged`() = runTest {
        val directory = folder.newFolder()
        val image = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2)
        val original = epub("Book", image)
        var encoded = 0
        val reader = CachedEpubMetadataReader(directory, { _, _ -> original.inputStream() }, { bytes, target ->
            assertContentEquals(image, bytes)
            encoded++
            target.writeBytes(byteArrayOf(7, 8, 9))
            true
        })
        val metadata = reader.read(file)
        val cached = File(java.net.URI(assertNotNull(metadata.coverUri)))
        assertEquals(directory.canonicalFile, assertNotNull(cached.parentFile).canonicalFile)
        assertTrue(cached.name.matches(Regex("[0-9a-f]{64}\\.webp")))
        assertContentEquals(byteArrayOf(7, 8, 9), cached.readBytes())
        reader.invalidate("tree")
        assertEquals(metadata.coverUri, reader.read(file).coverUri)
        assertEquals(1, encoded)
        assertContentEquals(original, epub("Book", image))
        assertEquals(listOf(cached.name), directory.listFiles().orEmpty().map { it.name })
    }

    @Test
    fun `cover decode rejection keeps title and a corrupt archive is negatively cached`() = runTest {
        val directory = folder.newFolder()
        var content = epub("Title stays", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0))
        var opened = 0
        val reader = CachedEpubMetadataReader(directory, { _, _ -> opened++; content.inputStream() }, { _, _ -> false })
        val metadata = reader.read(file)
        assertEquals("Title stays", metadata.title)
        assertNull(metadata.coverUri)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        content = "not an epub".toByteArray()
        reader.invalidate("tree")
        assertNull(reader.read(file).title)
        assertNull(reader.read(file).title)
        assertEquals(2, opened)
    }

    @Test
    fun `unsupported and oversized files do not open and a changed length cannot publish stale metadata`() = runTest {
        val directory = folder.newFolder()
        var opened = 0
        val reader = CachedEpubMetadataReader(directory, { _, _ -> opened++; epub("Stale").inputStream() })
        assertNull(reader.read(file.copy(extension = "pdf")).title)
        assertNull(reader.read(file.copy(sizeBytes = 128L * 1024 * 1024 + 1)).title)
        assertEquals(0, opened)
        assertNull(reader.read(file.copy(sizeBytes = 5)).title)
        assertEquals(1, opened)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation closes provider stream deletes temporary copy and does not poison metadata cache`() = runTest {
        val directory = folder.newFolder()
        var closed = false
        var cancel = true
        val reader = CachedEpubMetadataReader(directory, { _, _ ->
            if (cancel) object : InputStream() {
                override fun read(): Int = throw CancellationException("stop local parsing")
                override fun close() { closed = true }
            } else ByteArrayInputStream(epub("Retry"))
        })
        assertFailsWith<CancellationException> { reader.read(file) }
        assertTrue(closed)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertNull(reader.cached(file))
        cancel = false
        assertEquals("Retry", reader.read(file).title)
    }

    @Test
    fun `missing disk cover falls back without retaining a broken image URI`() = runTest {
        val directory = folder.newFolder()
        val reader = CachedEpubMetadataReader(directory, { _, _ ->
            epub("Title", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())).inputStream()
        }, { _, output -> output.writeText("sampled"); true })
        val cover = File(java.net.URI(assertNotNull(reader.read(file).coverUri)))
        assertTrue(cover.delete())
        assertEquals("Title", reader.cached(file)?.title)
        assertNull(reader.cached(file)?.coverUri)
        assertFalse(cover.exists())
    }

    @Test
    fun `cancelling a provider read closes its blocked stream before joining the worker`() = runTest {
        val directory = folder.newFolder()
        val started = CompletableDeferred<Unit>()
        val released = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val stream = object : InputStream() {
            override fun read(): Int {
                started.complete(Unit)
                while (!closed.get()) {
                    try { released.await() } catch (_: InterruptedException) { /* Simulate an uninterruptible provider pipe. */ }
                }
                throw java.io.IOException("closed pipe")
            }
            override fun close() { closed.set(true); released.countDown() }
        }
        val reader = CachedEpubMetadataReader(directory, { _, _ -> stream })
        withContext(Dispatchers.Default) {
            val reading = async { reader.read(file) }
            withTimeout(5_000) { started.await() }
            withTimeout(5_000) { reading.cancelAndJoin() }
        }
        assertTrue(closed.get())
        assertNull(reader.cached(file))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    private fun epub(title: String, cover: ByteArray? = null): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("META-INF/container.xml", """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray())
            entry("OPS/book.opf", """<package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/"><metadata><dc:title>$title</dc:title><dc:creator>An author</dc:creator></metadata><manifest><item id="cover" href="cover.jpg" properties="cover-image" media-type="image/jpeg"/></manifest></package>""".toByteArray())
            if (cover != null) entry("OPS/cover.jpg", cover)
        }
        return output.toByteArray()
    }
}
