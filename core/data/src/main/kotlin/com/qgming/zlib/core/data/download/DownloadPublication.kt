package com.qgming.zlib.core.data.download

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class DownloadDocument(val uri: String, val name: String, val supportsRename: Boolean = true)

internal interface DownloadPublicationFiles {
    fun children(): List<DownloadDocument>
    fun rename(part: DownloadDocument, name: String): String?
    fun create(name: String): String?
    fun read(uri: String): InputStream
    fun write(uri: String): OutputStream
    fun delete(uri: String): Boolean
}

internal class DownloadPublication(
    private val files: DownloadPublicationFiles,
    private val save: suspend (PendingDownloadPublication) -> Unit,
) {
    suspend fun recover(pending: PendingDownloadPublication): PendingDownloadPublication? {
        val children = files.children()
        val target = children.firstOrNull { it.uri == pending.targetUri }
            ?: if (pending.step == PublicationStep.Rename && originalPart(pending, children) == null) {
                children.singleOrNull { it.name == pending.targetName }
            } else null
        return if (target != null && target.verified(pending)) complete(pending, target.uri) else null
    }

    fun availableName(suggestedName: String): String {
        val children = files.children()
        return DownloadFileNames.uniqueFinalName(suggestedName) { name -> children.any { it.name == name } }
    }

    suspend fun publish(initial: PendingDownloadPublication, sourceVerified: Boolean = false): PendingDownloadPublication {
        var pending = initial
        if (pending.step == PublicationStep.Complete) {
            val target = files.children().firstOrNull { it.uri == pending.targetUri }
            if (target != null && target.verified(pending)) return pending
            throw IOException("The completed download could not be recovered")
        }

        var children = files.children()
        var part = originalPart(pending, children)
        if (pending.step == PublicationStep.Rename) {
            if (part == null) {
                val target = children.singleOrNull { it.name == pending.targetName }
                if (target != null && target.verified(pending)) return complete(pending, target.uri)
                throw IOException("The renamed download could not be recovered")
            }
            if (!sourceVerified && !part.verified(pending)) throw IOException("The downloaded temporary file changed")
            val targetName = DownloadFileNames.uniqueFinalName(pending.targetName) { name -> children.any { it.name == name } }
            if (targetName != pending.targetName) pending = pending.copy(targetName = targetName).also { save(it) }
            if (part.supportsRename) {
                val renamed = files.rename(part, pending.targetName)
                if (renamed != null) {
                    if (fileSha256(files.read(renamed), pending.totalBytes) != pending.sha256) {
                        throw IOException("The renamed download does not match the temporary file")
                    }
                    return complete(pending, renamed)
                }

                children = files.children()
                part = originalPart(pending, children)
                if (part == null) {
                    val target = children.singleOrNull { it.name == pending.targetName }
                    if (target != null && target.verified(pending)) return complete(pending, target.uri)
                    throw IOException("The renamed download could not be recovered")
                }
            }
            pending = pending.copy(step = PublicationStep.Copy).also { save(it) }
        }

        val target = children.firstOrNull { it.uri == pending.targetUri }
        if (target != null && target.verified(pending)) return complete(pending, target.uri)
        val source = part ?: throw IOException("The downloaded temporary file is missing")
        if (!sourceVerified && initial.step != PublicationStep.Rename && !source.verified(pending)) {
            throw IOException("The downloaded temporary file changed")
        }
        val targetUri = if (target != null) {
            if (!isPrefix(source.uri, target.uri, pending.totalBytes)) throw IOException("The download destination changed")
            target.uri
        } else {
            val targetName = DownloadFileNames.uniqueFinalName(pending.targetName) { name -> children.any { it.name == name } }
            if (targetName != pending.targetName) pending = pending.copy(targetName = targetName).also { save(it) }
            withContext(NonCancellable) {
                val created = files.create(pending.targetName) ?: throw IOException("The download destination could not be created")
                if (children.any { it.uri == created }) throw IOException("The provider returned an existing document")
                pending = pending.copy(targetUri = created).also { save(it) }
                created
            }
        }

        files.read(source.uri).use { input ->
            files.write(targetUri).use { output -> StreamCopy.copy(input, output, pending.totalBytes) }
        }
        if (fileSha256(files.read(targetUri), pending.totalBytes) != pending.sha256) {
            throw IOException("The copied download does not match the temporary file")
        }
        return complete(pending, targetUri)
    }

    suspend fun abandon(pending: PendingDownloadPublication): Boolean {
        val children = files.children()
        val target = children.firstOrNull { it.uri == pending.targetUri }
        if (target == null) {
            if (pending.targetUri != null) return true
            if (pending.step == PublicationStep.Copy) return true
            val existing = children.singleOrNull { it.name == pending.targetName }
            if (existing == null) return true
            return existing.verified(pending) || originalPart(pending, children) != null
        }
        if (target.verified(pending)) return true
        val part = originalPart(pending, children) ?: return false
        return pending.step == PublicationStep.Copy && part.verified(pending) &&
            isPrefix(part.uri, target.uri, pending.totalBytes) && files.delete(target.uri)
    }

    suspend fun removeOriginalPart(completed: PendingDownloadPublication) {
        if (completed.partUri == completed.targetUri) return
        val part = originalPart(completed, files.children())
        if (part != null && part.verified(completed)) files.delete(part.uri)
    }

    private suspend fun complete(pending: PendingDownloadPublication, targetUri: String): PendingDownloadPublication =
        pending.copy(step = PublicationStep.Complete, targetUri = targetUri).also { save(it) }

    private fun originalPart(pending: PendingDownloadPublication, children: List<DownloadDocument>): DownloadDocument? =
        children.firstOrNull { it.uri == pending.partUri && it.name == DownloadFileNames.partName(pending.taskId) }

    private suspend fun DownloadDocument.verified(pending: PendingDownloadPublication): Boolean =
        fileSha256(files.read(uri), pending.totalBytes) == pending.sha256

    private suspend fun isPrefix(partUri: String, targetUri: String, total: Long): Boolean =
        files.read(partUri).use { part ->
            files.read(targetUri).use prefix@{ target ->
                val buffer = ByteArray(32 * 1024)
                var remaining = total
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val read = target.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    if (read == -1) return@prefix true
                    val expected = part.readNBytes(read)
                    if (expected.size != read || expected.indices.any { expected[it] != buffer[it] }) return@prefix false
                    remaining -= read
                }
                target.read() == -1
            }
        }
}

internal suspend fun fileSha256(input: InputStream, expectedSize: Long): String? = input.use {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var remaining = expectedSize
    while (remaining > 0) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
        if (read == -1) return@use null
        digest.update(buffer, 0, read)
        remaining -= read
    }
    if (input.read() == -1) digest.digest().toHexString() else null
}

internal class SafDownloadPublicationFiles(private val resolver: ContentResolver, private val tree: Uri) : DownloadPublicationFiles {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    override fun children(): List<DownloadDocument> {
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == Document.MIME_TYPE_DIR) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
                    add(DownloadDocument(uri.toString(), name, cursor.getLong(3) and Document.FLAG_SUPPORTS_RENAME.toLong() != 0L))
                }
            }
        } ?: throw IOException("The download folder could not be read")
    }

    override fun rename(part: DownloadDocument, name: String): String? = try {
        DocumentsContract.renameDocument(resolver, part.uri.toUri(), name)?.toString()
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun create(name: String): String? = DocumentsContract.createDocument(resolver, root, "application/octet-stream", name)?.toString()

    override fun read(uri: String): InputStream = resolver.openInputStream(uri.toUri()) ?: throw FileNotFoundException("The download document is unavailable")

    override fun write(uri: String): OutputStream = resolver.openOutputStream(uri.toUri(), "wt") ?: throw FileNotFoundException("The download document is not writable")

    override fun delete(uri: String): Boolean = DocumentsContract.deleteDocument(resolver, uri.toUri())
}
