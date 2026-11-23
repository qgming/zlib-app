package com.qgming.zlib.core.data.download

import android.content.Context
import android.database.ContentObserver
import android.provider.DocumentsContract
import androidx.core.net.toUri
import com.qgming.zlib.core.model.EbookFiles
import com.qgming.zlib.core.model.LocalFile
import com.qgming.zlib.core.common.SafeLog
import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

internal interface LocalFileStore {
    suspend fun scan(treeUri: String): List<LocalFile>
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
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use {
            if (!it.moveToFirst() || it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR) {
                throw IOException("无法读取下载目录，请在设置中重新选择")
            }
        } ?: throw IOException("无法读取下载目录，请在设置中重新选择")

        val result = mutableListOf<LocalFile>()
        val pending = ArrayDeque<String>().apply { add(rootId) }
        val visited = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, Projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    inspected++
                    currentCoroutineContext().ensureActive()
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    if (cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        pending.add(id)
                    } else {
                        val extension = EbookFiles.extension(name) ?: continue
                        val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
                        if (flags and DocumentsContract.Document.FLAG_PARTIAL != 0) continue
                        result += LocalFile(
                            treeUri, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, extension,
                            sizeBytes = if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 },
                            modifiedAt = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        )
                    }
                }
            } ?: throw IOException("无法读取下载目录，请检查文件权限")
        }
        SafeLog.d("LocalScan", "directories=${visited.size} entries=$inspected books=${result.size} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        return result.distinctBy(LocalFile::uri)
            .sortedWith(compareByDescending<LocalFile> { it.modifiedAt }.thenBy { it.name.lowercase() })
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
        return DocumentsContract.deleteDocument(resolver, uri)
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
