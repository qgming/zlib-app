package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR
import com.qgming.zlib.core.database.DownloadEntity
import com.qgming.zlib.core.database.DownloadStates
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.LocalFile
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class CompletedDownloadTest {
    @Test
    fun `completed row opens only its persisted exact reference and document`() = runTest {
        val fixture = Fixture()
        val result = assertIs<AppResult.Success<LocalFile>>(fixture.resolve())
        assertEquals(fixture.uri, result.value.uri)
        assertEquals("7:abc", result.value.book?.ref)
        assertEquals(listOf("7:abc" to fixture.uri), fixture.reads)
    }

    @Test
    fun `a matching title or numeric ID cannot substitute for a different hash`() = runTest {
        val fixture = Fixture().apply { book = book!!.copy(hash = "different", title = task!!.title) }
        assertMissing(fixture.resolve())
        assertEquals(emptyList(), fixture.reads)
    }

    @Test
    fun `missing task book or document association never searches for a namesake`() = runTest {
        val missingTask = Fixture().apply { task = null }
        assertMissing(missingTask.resolve())
        assertEquals(emptyList(), missingTask.reads)
        val missingBook = Fixture().apply { book = null }
        assertMissing(missingBook.resolve())
        assertEquals(emptyList(), missingBook.reads)
        val missingAssociation = Fixture().apply { book = book!!.copy(localUri = null) }
        assertMissing(missingAssociation.resolve())
        assertEquals(emptyList(), missingAssociation.reads)
    }

    @Test
    fun `non completed and malformed task references cannot be opened`() = runTest {
        val downloading = Fixture().apply { task = task!!.copy(state = DownloadStates.DOWNLOADING) }
        assertMissing(downloading.resolve())
        assertEquals(emptyList(), downloading.reads)
        val malformed = Fixture().apply { task = task!!.copy(hash = "other") }
        assertMissing(malformed.resolve())
        assertEquals(emptyList(), malformed.reads)
    }

    @Test
    fun `SAF missing permission and pending-publication errors are preserved`() = runTest {
        for (message in listOf("deleted", "folder permission expired", "not yet published")) {
            val expected = AppResult.Failure(AppError.Storage(IOException(message)))
            val fixture = Fixture().apply { onRead = { expected } }
            assertSame(expected, fixture.resolve())
        }
    }

    @Test
    fun `a document returned with a different URI cannot replace the associated file`() = runTest {
        val fixture = Fixture().apply { onRead = { AppResult.Success(file().copy(uri = "namesake.epub")) } }
        assertMissing(fixture.resolve())
    }

    @Test
    fun `task removal or replacement during SAF validation invalidates the result`() = runTest {
        val removed = Fixture().apply { onRead = { task = null; AppResult.Success(file()) } }
        assertMissing(removed.resolve())
        val replaced = Fixture().apply {
            onRead = { task = task!!.copy(createdAt = 2L); AppResult.Success(file()) }
        }
        assertMissing(replaced.resolve())
        val requeued = Fixture().apply {
            onRead = { task = task!!.copy(state = DownloadStates.QUEUED); AppResult.Success(file()) }
        }
        assertMissing(requeued.resolve())
    }

    @Test
    fun `changed book hash or URI during SAF validation invalidates the result`() = runTest {
        val changedHash = Fixture().apply {
            onRead = { book = book!!.copy(hash = "new"); AppResult.Success(file()) }
        }
        assertMissing(changedHash.resolve())
        val changedUri = Fixture().apply {
            onRead = { book = book!!.copy(localUri = "replacement.epub"); AppResult.Success(file()) }
        }
        assertMissing(changedUri.resolve())
    }

    @Test
    fun `old or changed download directory retains its own error`() = runTest {
        val changedTree = Fixture().apply {
            onRead = { tree = "different-tree"; AppResult.Success(file()) }
        }
        assertDirectoryChanged(changedTree.resolve())
        val oldTree = Fixture().apply {
            onRead = { AppResult.Success(file().copy(treeUri = "old-tree")) }
        }
        assertDirectoryChanged(oldTree.resolve())
        val noTree = Fixture().apply { tree = null }
        assertDirectoryChanged(noTree.resolve())
        assertEquals(emptyList(), noTree.reads)
    }

    @Test
    fun `cancelling a SAF read cancels the open request`() = runTest {
        val fixture = Fixture().apply { onRead = { throw CancellationException("screen left") } }
        assertFailsWith<CancellationException> { fixture.resolve() }
    }

    private fun assertMissing(result: AppResult<LocalFile>) {
        val error = assertIs<AppError.Storage>(assertIs<AppResult.Failure>(result).error)
        assertEquals(appText(TextR.string.text_local_file_not_found_may_have_been_deleted), error.cause.message)
    }

    private fun assertDirectoryChanged(result: AppResult<LocalFile>) {
        val error = assertIs<AppError.Storage>(assertIs<AppResult.Failure>(result).error)
        assertEquals(appText(TextR.string.text_the_download_folder_changed_select_the_file_again), error.cause.message)
    }

    private class Fixture {
        val uri = "content://documents/tree/downloads/document/downloads%2Fbook.epub"
        var tree: String? = "content://documents/tree/downloads"
        var book: Book? = Book("7", "abc", "A book", extension = "epub", localUri = uri)
        var task: DownloadEntity? = DownloadEntity(
            id = "7:abc", bookId = "7", hash = "abc", title = "A book", authors = "",
            extension = "epub", coverUrl = "", fileName = "book.epub", state = DownloadStates.COMPLETED,
            bytesWritten = 100, totalBytes = 100, error = null, createdAt = 1L, updatedAt = 1L,
        )
        val reads = mutableListOf<Pair<String, String>>()
        var onRead: suspend () -> AppResult<LocalFile> = { AppResult.Success(file()) }

        fun file() = LocalFile(
            treeUri = "content://documents/tree/downloads", uri = uri, name = "book.epub",
            extension = "epub", book = book,
        )

        suspend fun resolve(): AppResult<LocalFile> = resolveCompletedDownload(
            id = "7:abc",
            taskLookup = { task },
            bookLookup = { book },
            currentTree = { tree },
            readable = { candidate, uri ->
                reads += candidate.ref to uri
                onRead()
            },
        )
    }
}
