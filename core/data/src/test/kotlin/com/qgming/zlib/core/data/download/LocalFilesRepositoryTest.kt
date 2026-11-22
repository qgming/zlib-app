package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.data.settings.SettingsStore
import com.qgming.zlib.core.model.Book
import com.qgming.zlib.core.model.BookGridColumns
import com.qgming.zlib.core.model.EbookFiles
import com.qgming.zlib.core.model.LocalFile
import com.qgming.zlib.core.model.ThemeMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFilesRepositoryTest {
    @Test
    fun `actual directory files are listed without database records and missing records are not invented`() = runTest {
        withFiles { store, _, known, _, repository ->
            store.files = listOf(file("outside-download.epub"), file("known.pdf"))
            known.value = listOf(
                Book("7", "abc", "Known title", localUri = "known.pdf"),
                Book("8", "def", "Missing file", localUri = "missing.epub"),
            )
            val result = repository.observeFiles().first { !it.loading }
            assertEquals(listOf("outside-download.epub", "known.pdf"), result.files.map { it.uri })
            assertNull(result.files.first().book)
            assertEquals("Known title", result.files.last().title)
        }
    }

    @Test
    fun `external deletion is reflected by visible directory polling without database changes`() = runTest {
        withFiles { store, _, _, _, repository ->
            store.files = listOf(file("gone.epub"))
            var latest: LocalFilesState? = null
            val collection = backgroundScope.launch { repository.observeFiles().collect { latest = it } }
            runCurrent()
            assertEquals(1, latest?.files?.size)
            store.files = emptyList()
            advanceTimeBy(3_001)
            runCurrent()
            assertTrue(assertNotNull(latest).files.isEmpty())
            val scans = store.scans
            collection.cancel()
            runCurrent()
            advanceTimeBy(6_001)
            assertEquals(scans, store.scans)
        }
    }

    @Test
    fun `directory switch clears old files and read failures are not empty success`() = runTest {
        withFiles { store, settings, _, _, repository ->
            store.files = listOf(file("old.epub"))
            var latest: LocalFilesState? = null
            backgroundScope.launch { repository.observeFiles().collect { latest = it } }
            runCurrent()
            store.failure = SecurityException("permission revoked")
            settings.value.value = AppSettings(downloadTreeUri = "other")
            runCurrent()
            assertEquals("other", latest?.treeUri)
            assertTrue(assertNotNull(latest).files.isEmpty())
            assertNotNull(latest?.error)
            settings.value.value = AppSettings()
            runCurrent()
            assertNull(latest?.treeUri)
            assertNull(latest?.error)
        }
    }

    @Test
    fun `delete removes a real file and then clears only its exact URI reference`() = runTest {
        val actual = Files.createTempFile("zlib-local-", ".epub").toFile()
        try {
            actual.writeText("test ebook")
            withFiles { store, _, _, cleared, repository ->
                val document = file(actual.absolutePath)
                store.files = listOf(document)
                store.actual = actual
                assertIs<AppResult.Success<Unit>>(repository.deleteLocal(document))
                assertFalse(actual.exists())
                assertEquals(listOf(document.uri), cleared)
                assertTrue(repository.observeFiles().first { !it.loading }.files.isEmpty())
            }
        } finally { actual.delete() }
    }

    @Test
    fun `refused or unconfirmed delete retains file association`() = runTest {
        withFiles { store, _, _, cleared, repository ->
            val document = file("keep.epub")
            store.files = listOf(document)
            store.refuseDelete = true
            assertIs<AppResult.Failure>(repository.deleteLocal(document))
            assertTrue(cleared.isEmpty())
            assertEquals(listOf(document), store.files)
            store.refuseDelete = false
            store.keepAfterDelete = true
            assertIs<AppResult.Failure>(repository.deleteLocal(document))
            assertTrue(cleared.isEmpty())
        }
    }

    @Test
    fun `old directory selections cannot open or delete files and cannot clear associations`() = runTest {
        withFiles { store, settings, _, cleared, repository ->
            val document = file("old.epub")
            store.files = listOf(document)
            settings.value.value = AppSettings(downloadTreeUri = "other")
            assertIs<AppResult.Failure>(repository.readable(document))
            assertIs<AppResult.Failure>(repository.deleteLocal(document))
            assertEquals(0, store.deletes)
            assertTrue(cleared.isEmpty())
            settings.value.value = AppSettings(downloadTreeUri = "tree")
            store.files = emptyList()
            assertIs<AppResult.Failure>(repository.readable(document))
        }
    }

    @Test
    fun `extension recognition excludes partial and unrelated files and handles uppercase`() {
        assertEquals("epub", EbookFiles.extension("A.EPUB"))
        assertEquals("pdf", EbookFiles.extension("book.with.dots.pdf"))
        assertNull(EbookFiles.extension("unfinished.epub.part"))
        assertNull(EbookFiles.extension("cover.jpg"))
        assertNull(EbookFiles.extension("no-extension"))
        assertEquals("application/pdf", EbookFiles.mimeType("PDF"))
    }

    private suspend fun TestScope.withFiles(
        block: suspend TestScope.(FakeStore, MemorySettings, MutableStateFlow<List<Book>>, MutableList<String>, LocalFilesRepository) -> Unit,
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : AppDispatchers {
            override val io = dispatcher
            override val default = dispatcher
            override val main = dispatcher
        }
        val store = FakeStore()
        val settings = MemorySettings()
        val known = MutableStateFlow<List<Book>>(emptyList())
        val cleared = mutableListOf<String>()
        block(store, settings, known, cleared, LocalFilesRepository(store, settings, known, { cleared += it }, dispatchers))
    }

    private fun file(uri: String) = LocalFile("tree", uri, uri.substringAfterLast('/'), "epub")

    private class FakeStore : LocalFileStore {
        var files = emptyList<LocalFile>()
        var failure: Exception? = null
        var refuseDelete = false
        var keepAfterDelete = false
        var scans = 0
        var deletes = 0
        var actual: File? = null
        override suspend fun scan(treeUri: String): List<LocalFile> {
            scans++
            failure?.let { throw it }
            return files
        }
        override fun changes(treeUri: String): Flow<Unit> = emptyFlow()
        override fun isReadable(file: LocalFile) = files.any { it.uri == file.uri }
        override fun delete(file: LocalFile): Boolean {
            deletes++
            if (refuseDelete) return false
            actual?.let { if (!it.delete()) return false }
            if (!keepAfterDelete) files = files.filterNot { it.uri == file.uri }
            return true
        }
    }

    private class MemorySettings : SettingsStore {
        val value = MutableStateFlow(AppSettings(downloadTreeUri = "tree"))
        override val settings = value
        override suspend fun read() = value.value
        override suspend fun setDownloadTreeUri(uri: String?) { value.value = value.value.copy(downloadTreeUri = uri) }
        override suspend fun setCurrentHost(host: String?) = Unit
        override suspend fun setCustomHosts(hosts: Set<String>) = Unit
        override suspend fun setLockedHost(host: String?) = Unit
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setBookGridColumns(columns: BookGridColumns) = Unit
    }
}
