package com.qgming.zlib.core.data.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal data class EpubMetadata(
    val title: String? = null,
    val authors: String? = null,
    val cover: ByteArray? = null,
    val coverExtension: String? = null,
)

/** Reads display metadata only; archive contents are never extracted or executed (ADR-120). */
internal object EpubMetadataParser {
    // Match the SAF copy bound. Independent entry/read bounds also limit highly compressed inputs.
    private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_XML_BYTES = 1024 * 1024
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024
    private const val MAX_READ_BYTES = 2 * MAX_XML_BYTES + MAX_COVER_BYTES
    private const val MAX_PATH_LENGTH = 4096
    private const val MAX_COVER_CANDIDATES = 8
    private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val OPF_NS = "http://www.idpf.org/2007/opf"
    private const val DC_NS = "http://purl.org/dc/elements/1.1/"
    private const val PACKAGE_MIME = "application/oebps-package+xml"
    private val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun parse(file: File, checkActive: () -> Unit = {}): EpubMetadata {
        checkActive()
        if (!file.isFile || file.length() !in 1..MAX_ARCHIVE_BYTES) return EpubMetadata()
        return safely {
            ZipFile(file).use { archive ->
                if (archive.size() > MAX_ENTRIES) throw IOException("EPUB has too many entries")
                val entries = buildMap<String, ZipEntry> {
                    val iterator = archive.entries()
                    while (iterator.hasMoreElements()) {
                        checkActive()
                        val entry = iterator.nextElement()
                        if (!entry.isDirectory && entry.name.length <= MAX_PATH_LENGTH) {
                            if (put(entry.name, entry) != null) throw IOException("EPUB has duplicate entries")
                        }
                    }
                }
                val budget = ReadBudget()
                val container = ContainerHandler(checkActive)
                readXml(readEntry(archive, entries["META-INF/container.xml"], MAX_XML_BYTES, budget, checkActive), container)
                val packagePath = container.rootfiles.sortedBy { it.second != PACKAGE_MIME }
                    .firstNotNullOfOrNull { (path, _) -> resolvePath(null, path)?.takeIf(entries::containsKey) }
                    ?: return@use EpubMetadata()
                val metadata = PackageHandler(checkActive)
                readXml(readEntry(archive, entries[packagePath], MAX_XML_BYTES, budget, checkActive), metadata)
                val text = EpubMetadata(metadata.title, metadata.authors.takeIf { it.isNotEmpty() }?.joinToString(" / "))
                val candidates = (metadata.items.filter { it.coverImage } +
                    metadata.coverIds.flatMap { id -> metadata.items.filter { it.id == id } })
                    .mapNotNull { item -> resolvePath(packagePath, item.href) }
                    .distinct().take(MAX_COVER_CANDIDATES)
                for (path in candidates) {
                    checkActive()
                    val bytes = safely { readEntry(archive, entries[path], MAX_COVER_BYTES, budget, checkActive) } ?: continue
                    val extension = rasterExtension(bytes) ?: continue
                    return@use text.copy(cover = bytes, coverExtension = extension)
                }
                text
            }
        } ?: EpubMetadata()
    }

    private class ReadBudget(var remaining: Int = MAX_READ_BYTES)

    private fun readEntry(
        archive: ZipFile,
        entry: ZipEntry?,
        limit: Int,
        budget: ReadBudget,
        checkActive: () -> Unit,
    ): ByteArray {
        checkActive()
        if (entry == null || entry.isDirectory || entry.size > limit || entry.size > budget.remaining) {
            throw IOException("EPUB entry unavailable or oversized")
        }
        archive.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                checkActive()
                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (read < 0) break
                budget.remaining -= read
                if (output.size() + read > limit || budget.remaining < 0) throw IOException("EPUB read limit exceeded")
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }

    private fun readXml(bytes: ByteArray, handler: SafeXmlHandler) {
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true; isValidating = false }
        val reader = factory.newSAXParser().xmlReader
        // Android Expat and the JVM expose different optional feature sets. The required lexical
        // handler rejects every DOCTYPE; the resolver independently refuses all external input.
        listOf(
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://javax.xml.XMLConstants/feature/secure-processing" to true,
        ).forEach { (feature, enabled) ->
            try { reader.setFeature(feature, enabled) } catch (_: SAXException) { /* Guarded by the required handlers below. */ }
        }
        reader.contentHandler = handler
        reader.entityResolver = handler
        reader.errorHandler = handler
        reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler)
        reader.parse(InputSource(bytes.inputStream()))
    }

    /** Resolves a URI reference within the ZIP root, never against the filesystem or a URL. */
    private fun resolvePath(base: String?, reference: String): String? {
        if (reference.length > MAX_PATH_LENGTH) return null
        val path = decodePath(reference.trim().substringBefore('#').substringBefore('?')) ?: return null
        if (path.isBlank() || path.startsWith('/') || scheme.containsMatchIn(path) ||
            path.any { it == '\\' || it.code < 32 || it.code == 127 }
        ) return null
        val segments = base?.substringBeforeLast('/', "")?.split('/')?.filter(String::isNotEmpty)?.toMutableList()
            ?: mutableListOf()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/").takeIf { it.isNotBlank() && it.length <= MAX_PATH_LENGTH }
    }

    private fun decodePath(value: String): String? {
        val bytes = ByteArrayOutputStream()
        var start = 0
        var position = 0
        while (position < value.length) {
            if (value[position] != '%') { position++; continue }
            bytes.write(value.substring(start, position).toByteArray(Charsets.UTF_8))
            if (position + 2 >= value.length) return null
            val high = value[position + 1].digitToIntOrNull(16) ?: return null
            val low = value[position + 2].digitToIntOrNull(16) ?: return null
            bytes.write(high * 16 + low)
            position += 3
            start = position
        }
        bytes.write(value.substring(start).toByteArray(Charsets.UTF_8))
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: IOException) { null }
    }

    private fun rasterExtension(bytes: ByteArray): String? {
        fun prefix(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes[it].toInt() and 0xff == values[it] }
        return when {
            prefix(0xff, 0xd8, 0xff) -> "jpg"
            prefix(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "png"
            bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in listOf("GIF87a", "GIF89a") -> "gif"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "webp"
            prefix(0x42, 0x4d) -> "bmp"
            else -> null
        }
    }

    private abstract class SafeXmlHandler(private val checkActive: () -> Unit) : DefaultHandler2() {
        protected var depth = 0
            private set
        private var elements = 0

        final override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            checkActive()
            depth++
            if (depth > 64 || ++elements > MAX_ENTRIES || attributes.length > 64) throw SAXException("EPUB XML too complex")
            onStart(uri, localName, attributes)
        }

        final override fun endElement(uri: String, localName: String, qName: String) {
            checkActive()
            onEnd(uri, localName)
            depth--
        }

        final override fun characters(chars: CharArray, start: Int, length: Int) {
            checkActive()
            onText(chars, start, length)
        }

        protected abstract fun onStart(uri: String, name: String, attributes: Attributes)
        protected open fun onEnd(uri: String, name: String) = Unit
        protected open fun onText(chars: CharArray, start: Int, length: Int) = Unit
        final override fun startDTD(name: String?, publicId: String?, systemId: String?) { throw SAXException("EPUB DOCTYPE is forbidden") }
        final override fun resolveEntity(publicId: String?, systemId: String?): InputSource = throw SAXException("EPUB external entity is forbidden")
        final override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource =
            throw SAXException("EPUB external entity is forbidden")
        final override fun getExternalSubset(name: String?, baseURI: String?): InputSource? = null
        final override fun skippedEntity(name: String?) { throw SAXException("EPUB entity unavailable") }
        final override fun error(error: SAXParseException) { throw error }
        final override fun fatalError(error: SAXParseException) { throw error }
    }

    private class ContainerHandler(checkActive: () -> Unit) : SafeXmlHandler(checkActive) {
        val rootfiles = mutableListOf<Pair<String, String>>()

        override fun onStart(uri: String, name: String, attributes: Attributes) {
            if (depth == 1 && (name != "container" || uri !in listOf("", CONTAINER_NS))) throw SAXException("Invalid EPUB container")
            if (name == "rootfile" && uri in listOf("", CONTAINER_NS) && rootfiles.size < 16) {
                attributes.getValue("full-path")?.takeIf { it.isNotBlank() && it.length <= MAX_PATH_LENGTH }?.let {
                    rootfiles.add(it to attributes.getValue("media-type").orEmpty().trim().lowercase(Locale.ROOT))
                }
            }
        }
    }

    private data class ManifestItem(val id: String, val href: String, val coverImage: Boolean)

    private class PackageHandler(checkActive: () -> Unit) : SafeXmlHandler(checkActive) {
        var title: String? = null
            private set
        val authors = mutableListOf<String>()
        val coverIds = mutableListOf<String>()
        val items = mutableListOf<ManifestItem>()
        private var metadata = false
        private var manifest = false
        private var capture: String? = null
        private val text = StringBuilder()

        override fun onStart(uri: String, name: String, attributes: Attributes) {
            val opf = uri == "" || uri == OPF_NS
            if (depth == 1 && (name != "package" || !opf)) throw SAXException("Invalid EPUB package")
            if (depth == 2 && opf) {
                metadata = name == "metadata"
                manifest = name == "manifest"
            }
            if (depth != 3) return
            if (metadata && uri in listOf("", DC_NS) && name in listOf("title", "creator")) {
                capture = name
                text.setLength(0)
            }
            if (metadata && opf && name == "meta" && attributes.getValue("name") == "cover" && coverIds.size < 16) {
                attributes.getValue("content")?.trim()?.takeIf { it.isNotBlank() && it.length <= 256 }?.let(coverIds::add)
            }
            if (manifest && opf && name == "item") {
                val id = attributes.getValue("id").orEmpty().take(256)
                val href = attributes.getValue("href")?.takeIf { it.length <= MAX_PATH_LENGTH } ?: return
                val cover = attributes.getValue("properties").orEmpty().split(Regex("\\s+")).contains("cover-image")
                items.add(ManifestItem(id, href, cover))
            }
        }

        override fun onText(chars: CharArray, start: Int, length: Int) {
            if (capture != null && text.length < 4096) text.append(chars, start, minOf(length, 4096 - text.length))
        }

        override fun onEnd(uri: String, name: String) {
            if (depth == 3 && capture != null) {
                val normalized = normalizeText(text.toString(), if (capture == "title") 512 else 128)
                if (normalized.isNotEmpty()) {
                    if (capture == "title" && title == null) title = normalized
                    if (capture == "creator" && authors.size < 16 && normalized !in authors) authors.add(normalized)
                }
                capture = null
            }
            if (depth == 2) { metadata = false; manifest = false }
        }
    }

    private fun normalizeText(value: String, limit: Int): String = buildString {
        var space = false
        for (char in value) {
            if (char.isWhitespace() || Character.isSpaceChar(char)) {
                space = isNotEmpty()
            } else if (!char.isISOControl()) {
                if (space && length < limit) append(' ')
                space = false
                if (length >= limit) break
                append(char)
            }
        }
        if (lastOrNull()?.isHighSurrogate() == true) setLength(length - 1)
    }.trim()

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        null
    } catch (_: SAXException) {
        null
    } catch (_: ParserConfigurationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
