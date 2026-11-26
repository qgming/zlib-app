package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.qgming.zlib.core.model.EbookFiles
import com.qgming.zlib.core.model.LocalFile
import com.qgming.zlib.core.common.SafeLog
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine

internal interface LocalFileStore {
    suspend fun scan(treeUri: String): List<LocalFile>
    suspend fun find(file: LocalFile): LocalFile?
    suspend fun isDirectChild(file: LocalFile): Boolean
    fun changes(treeUri: String): Flow<Unit>
    fun isReadable(file: LocalFile): Boolean
    fun delete(file: LocalFile): Boolean
}

/** Only enumerates descendants of the exact tree selected in Settings. */
internal class SafLocalFileStore(context: Context) : LocalFileStore {
    private val resolver = context.contentResolver

    override suspend fun scan(treeUri: String): List<LocalFile> {
        val started = System.nanoTime()
        var inspected = 0
        val tree = treeUri.toUri()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        if (query(root, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE)) {
                it.moveToFirst() && it.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            } != true
        ) throw IOException(appText(TextR.string.text_cannot_read_the_download_folder_choose_it_again_in_settings))

        val result = mutableListOf<LocalFile>()
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val visited = mutableSetOf<String>()
        val coroutine = currentCoroutineContext()
        while (pending.isNotEmpty()) {
            coroutine.ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            query(children, Projection) { cursor ->
                while (cursor.moveToNext()) {
                    inspected++
                    coroutine.ensureActive()
                    val id = cursor.getString(0) ?: continue
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else {
                        document(cursor, treeUri, tree, id)?.let(result::add)
                    }
                }
            } ?: throw IOException(appText(TextR.string.text_cannot_read_the_download_folder_check_file_permissions))
        }
        SafeLog.d("LocalScan", "directories=${visited.size} entries=$inspected books=${result.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        return result.distinctBy(LocalFile::uri)
            .sortedWith(compareByDescending<LocalFile> { it.modifiedAt }.thenBy { it.name.lowercase() })
    }

    override suspend fun find(file: LocalFile): LocalFile? {
        val tree = file.treeUri.toUri()
        val uri = file.uri.toUri()
        val id = documentIdInTree(tree, uri) ?: return null
        return try {
            val match = query(uri, Projection) { cursor ->
                listOfNotNull(if (cursor.moveToFirst() && cursor.getString(0) == id) document(cursor, file.treeUri, tree, id) else null)
            }
            if (match != null) match.singleOrNull() else scan(file.treeUri).firstOrNull { it.uri == file.uri }
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override suspend fun isDirectChild(file: LocalFile): Boolean {
        val tree = file.treeUri.toUri()
        val id = documentIdInTree(tree, file.uri.toUri()) ?: return false
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)) { cursor ->
            var found = false
            while (!found && cursor.moveToNext()) found = cursor.getString(0) == id
            found
        } ?: throw IOException(appText(TextR.string.text_cannot_read_the_download_folder_check_file_permissions))
    }

    private fun documentIdInTree(tree: Uri, document: Uri): String? {
        if (document.scheme != "content" || document.authority != tree.authority || !DocumentsContract.isTreeUri(document)) return null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val documentId = runCatching { DocumentsContract.getDocumentId(document) }.getOrNull() ?: return null
        return documentId.takeIf { it != rootId && DocumentsContract.buildDocumentUriUsingTree(tree, it) == document }
    }

    private fun document(cursor: Cursor, treeUri: String, tree: Uri, id: String): LocalFile? {
        val name = cursor.getString(1) ?: return null
        val extension = EbookFiles.extension(name) ?: return null
        val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
        val size = if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 }
        if (flags and DocumentsContract.Document.FLAG_PARTIAL != 0 || size == 0L) return null
        return LocalFile(
            treeUri, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, extension,
            sizeBytes = size,
            modifiedAt = if (cursor.isNull(4)) 0 else cursor.getLong(4),
        )
    }

    private suspend fun <T> query(uri: Uri, projection: Array<String>, read: (Cursor) -> T): T? =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            if (continuation.isActive) {
                continuation.resumeWith(runCatching { resolver.query(uri, projection, null, null, null, signal)?.use(read) })
            }
        }

    override fun changes(treeUri: String): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        // Some providers do not implement notifications; visible-page polling is the fallback.
        val registered = runCatching {
            val tree = treeUri.toUri()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            resolver.registerContentObserver(children, true, observer)
        }.isSuccess
        awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
    }

    override fun isReadable(file: LocalFile): Boolean =
        resolver.openAssetFileDescriptor(file.uri.toUri(), "r")?.use { true } == true

    override fun delete(file: LocalFile): Boolean {
        val uri = file.uri.toUri()
        return try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: FileNotFoundException) {
            true
        }
    }

    private companion object {
        val Projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
        )
    }
}
