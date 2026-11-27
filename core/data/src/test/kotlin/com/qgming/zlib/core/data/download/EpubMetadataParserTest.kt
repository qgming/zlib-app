package com.qgming.zlib.core.data.download

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class EpubMetadataParserTest {
    @get:Rule val folder = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3)
    private val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 1, 2, 3)

    @Test
    fun `EPUB2 metadata comes from container-selected OPF rather than filename or first OPF`() {
        val epub = epub(
            container("Books/actual.opf"),
            "wrong.opf" to packageXml("<dc:title>Wrong book</dc:title>").toByteArray(),
            "Books/actual.opf" to packageXml(
                """<dc:title>  Embedded  title </dc:title><dc:creator> Alice </dc:creator><dc:creator>Bob</dc:creator><meta name="cover" content="front"/>""",
                """<item id="front" href="Images/cover.jpg" media-type="image/jpeg"/>""",
                version = "2.0",
            ).toByteArray(),
            "Books/Images/cover.jpg" to jpeg,
        )

        val result = EpubMetadataParser.parse(epub)

        assertEquals("Embedded title", result.title)
        assertEquals("Alice / Bob", result.authors)
        assertContentEquals(jpeg, result.cover)
        assertEquals("jpg", result.coverExtension)
    }

    @Test
    fun `EPUB3 cover-image takes priority over legacy cover and inline nested text is normalized`() {
        val epub = epub(
            container("OPS/book.opf"),
            "OPS/book.opf" to packageXml(
                """<dc:title>Title &amp; <em>subtitle</em></dc:title><dc:creator>Same&#160;  Author</dc:creator><dc:creator>Same Author</dc:creator><meta name="cover" content="old"/>""",
                """<item id="old" href="legacy.jpg" media-type="image/jpeg"/><item id="front" href="front.png" properties="nav cover-image" media-type="image/png"/>""",
            ).toByteArray(),
            "OPS/legacy.jpg" to jpeg,
            "OPS/front.png" to png,
        )

        val result = EpubMetadataParser.parse(epub)

        assertEquals("Title & subtitle", result.title)
        assertEquals("Same Author", result.authors)
        assertContentEquals(png, result.cover)
        assertEquals("png", result.coverExtension)
    }

    @Test
    fun `namespace prefixes and percent-encoded root and cover paths are supported`() {
        val opf = """
            <p:package xmlns:p="http://www.idpf.org/2007/opf" xmlns:m="http://purl.org/dc/elements/1.1/" version="3.0">
              <p:metadata><m:title>书内名称</m:title><m:creator>作者</m:creator></p:metadata>
              <p:manifest><p:item id="cover" href="../Images/%E5%B0%81%E9%9D%A2%20%2B%20front.png" properties="cover-image"/></p:manifest>
            </p:package>
        """.trimIndent()
        val epub = epub(
            container("OPS/Package%20Files/book.opf"),
            "OPS/Package Files/book.opf" to opf.toByteArray(),
            "OPS/Images/封面 + front.png" to png,
        )

        val result = EpubMetadataParser.parse(epub)

        assertEquals("书内名称", result.title)
        assertEquals("作者", result.authors)
        assertContentEquals(png, result.cover)
    }

    @Test
    fun `relative dot segments within archive and literal plus signs are retained`() {
        val epub = epub(
            container("OPS/book.opf"),
            "OPS/book.opf" to packageXml(
                "<dc:title>Paths</dc:title>",
                """<item id="cover" href="./text/../../Images/front+cover.png#image" properties="cover-image"/>""",
            ).toByteArray(),
            "Images/front+cover.png" to png,
        )

        assertContentEquals(png, EpubMetadataParser.parse(epub).cover)
    }

    @Test
    fun `foreign namespace fields do not masquerade as Dublin Core metadata`() {
        val epub = epub(
            container("book.opf"),
            "book.opf" to packageXml("""<title xmlns="https://invalid.test/">Wrong</title><dc:title>Right</dc:title><creator xmlns="https://invalid.test/">Wrong</creator>""").toByteArray(),
        )

        val result = EpubMetadataParser.parse(epub)

        assertEquals("Right", result.title)
        assertNull(result.authors)
    }

    @Test
    fun `blank fields and missing manifest cover preserve valid authors`() {
        val epub = epub(
            container("book.opf"),
            "book.opf" to packageXml("<dc:title>  </dc:title><dc:creator>Someone</dc:creator>").toByteArray(),
        )

        val result = EpubMetadataParser.parse(epub)

        assertNull(result.title)
        assertEquals("Someone", result.authors)
        assertNull(result.cover)
        assertNull(result.coverExtension)
    }

    @Test
    fun `invalid modern cover falls back to valid EPUB2 raster without losing title`() {
        val epub = epub(
            container("book.opf"),
            "book.opf" to packageXml(
                """<dc:title>Usable title</dc:title><meta name="cover" content="legacy"/>""",
                """<item id="modern" href="front.svg" properties="cover-image"/><item id="legacy" href="legacy.jpg"/>""",
            ).toByteArray(),
            "front.svg" to """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray(),
            "legacy.jpg" to jpeg,
        )

        val result = EpubMetadataParser.parse(epub)

        assertEquals("Usable title", result.title)
        assertContentEquals(jpeg, result.cover)
        assertEquals("jpg", result.coverExtension)
    }

    @Test
    fun `archive traversal absolute URLs and invalid escapes cannot provide covers`() {
        val unsafePaths = listOf(
            "../../secret.png", "..%2F..%2Fsecret.png", "/secret.png", "//remote.test/secret.png",
            "https://remote.test/secret.png", "file:/secret.png", "data:image/png;base64,AA==",
            "..\\secret.png", "%2Fsecret.png", "%5csecret.png", "%00secret.png", "%zz", "%c0%afsecret.png",
        )
        for (path in unsafePaths) {
            val epub = epub(
                container("OPS/book.opf"),
                "OPS/book.opf" to packageXml("<dc:title>Safe text</dc:title>", """<item id="cover" href="$path" properties="cover-image"/>""").toByteArray(),
                "secret.png" to png,
                path to png,
            )

            val result = EpubMetadataParser.parse(epub)

            assertEquals("Safe text", result.title, path)
            assertNull(result.cover, path)
        }
    }

    @Test
    fun `rootfile cannot escape archive or reference an external URL`() {
        for (path in listOf("../book.opf", "%2E%2E/book.opf", "/book.opf", "https://remote.test/book.opf", "OPS\\book.opf")) {
            assertEquals(
                EpubMetadata(),
                EpubMetadataParser.parse(epub(container(path), path to packageXml("<dc:title>Wrong</dc:title>").toByteArray())),
                path,
            )
        }
    }

    @Test
    fun `missing invalid or truncated archives return empty metadata`() {
        val invalid = folder.newFile("invalid.epub").apply { writeText("not a ZIP") }
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(invalid))
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(File(folder.root, "missing.epub")))
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub("mimetype" to "application/epub+zip".toByteArray())))
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(container("missing.opf"))))
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(container("broken.opf"), "broken.opf" to "<package".toByteArray())))
    }

    @Test
    fun `container and OPF doctypes external entities and entity expansion are refused`() {
        val secret = folder.newFile("private.txt").apply { writeText("must never be read") }
        val external = """<!DOCTYPE package [<!ENTITY leak SYSTEM "${secret.toURI()}">]>"""
        val opf = external + packageXml("<dc:title>&leak;</dc:title>")
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(container("book.opf"), "book.opf" to opf.toByteArray())))

        val internal = """<!DOCTYPE package [<!ENTITY a "expanded"><!ENTITY b "&a;&a;&a;">]>"""
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(container("book.opf"), "book.opf" to (internal + packageXml("<dc:title>&b;</dc:title>")).toByteArray())))

        val container = container("book.opf")
        val maliciousContainer = "<!DOCTYPE container SYSTEM \"${secret.toURI()}\">".toByteArray() + container.second
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(container.first to maliciousContainer, "book.opf" to packageXml("<dc:title>Wrong</dc:title>").toByteArray())))
    }

    @Test
    fun `compressed oversized XML is refused before metadata can be used`() {
        val huge = packageXml("<dc:title>" + "A".repeat(1024 * 1024) + "</dc:title>").toByteArray()
        val epub = epub(container("book.opf"), "book.opf" to huge)

        assertTrue(epub.length() < 16 * 1024)
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub))
    }

    @Test
    fun `oversized cover keeps title and author and permits smaller legacy fallback`() {
        val huge = ByteArray(8 * 1024 * 1024 + 1).apply { png.copyInto(this) }
        val epub = epub(
            container("book.opf"),
            "book.opf" to packageXml(
                """<dc:title>Kept</dc:title><dc:creator>Author</dc:creator><meta name="cover" content="legacy"/>""",
                """<item id="huge" href="huge.png" properties="cover-image"/><item id="legacy" href="small.jpg"/>""",
            ).toByteArray(),
            "huge.png" to huge,
            "small.jpg" to jpeg,
        )

        val result = EpubMetadataParser.parse(epub)

        assertEquals("Kept", result.title)
        assertEquals("Author", result.authors)
        assertContentEquals(jpeg, result.cover)
    }

    @Test
    fun `actual decompression limit still applies if ZIP declares a falsely small XML size`() {
        val huge = packageXml("<dc:title>" + "A".repeat(1024 * 1024) + "</dc:title>").toByteArray()
        val epub = epub(container("book.opf"), "book.opf" to huge)
        val bytes = epub.readBytes()
        var position = 0
        while (position < bytes.size - 46) {
            if (bytes[position] == 0x50.toByte() && bytes[position + 1] == 0x4b.toByte() &&
                bytes[position + 2] == 0x01.toByte() && bytes[position + 3] == 0x02.toByte()
            ) {
                val nameLength = (bytes[position + 28].toInt() and 0xff) + ((bytes[position + 29].toInt() and 0xff) shl 8)
                if (String(bytes, position + 46, nameLength, Charsets.UTF_8) == "book.opf") {
                    for (offset in 24..27) bytes[position + offset] = if (offset == 24) 1 else 0
                    break
                }
            }
            position++
        }
        epub.writeBytes(bytes)

        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub))
    }

    @Test
    fun `deep XML and too many entries are refused`() {
        val deep = packageXml("<dc:title>" + "<b>".repeat(65) + "Title" + "</b>".repeat(65) + "</dc:title>")
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(container("book.opf"), "book.opf" to deep.toByteArray())))
        val entries = (0..10_000).map { "entry-$it" to byteArrayOf() }
        assertEquals(EpubMetadata(), EpubMetadataParser.parse(epub(*entries.toTypedArray())))
    }

    @Test
    fun `text and author counts are capped while preserving readable unicode`() {
        val metadata = "<dc:title>" + "名".repeat(1000) + "</dc:title>" +
            (0..30).joinToString("") { "<dc:creator>Author$it</dc:creator>" }
        val result = EpubMetadataParser.parse(epub(container("book.opf"), "book.opf" to packageXml(metadata).toByteArray()))

        assertEquals(512, result.title?.length)
        assertEquals(16, result.authors?.split(" / ")?.size)
    }

    @Test
    fun `archive size bound is checked before opening ZIP`() {
        val file = folder.newFile("oversized.epub")
        RandomAccessFile(file, "rw").use { it.setLength(128L * 1024 * 1024 + 1) }

        assertEquals(EpubMetadata(), EpubMetadataParser.parse(file))
    }

    @Test
    fun `cancellation propagates from archive XML parsing and cover reading`() {
        val epub = epub(
            container("book.opf"),
            "book.opf" to packageXml("<dc:title>Cancelled</dc:title>", """<item id="c" href="cover.png" properties="cover-image"/>""").toByteArray(),
            "cover.png" to ByteArray(64 * 1024).apply { png.copyInto(this) },
        )
        for (cancelAt in listOf(1, 4, 12, 25)) {
            var checks = 0
            assertFailsWith<CancellationException> {
                EpubMetadataParser.parse(epub) { if (++checks == cancelAt) throw CancellationException("stop") }
            }
            assertEquals(cancelAt, checks)
        }
    }

    private fun epub(vararg entries: Pair<String, ByteArray>): File = folder.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun container(path: String) = "META-INF/container.xml" to """
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles><rootfile full-path="$path" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent().toByteArray()

    private fun packageXml(metadata: String, manifest: String = "", version: String = "3.0") = """
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/" version="$version">
          <metadata>$metadata</metadata><manifest>$manifest</manifest>
        </package>
    """.trimIndent()
}
