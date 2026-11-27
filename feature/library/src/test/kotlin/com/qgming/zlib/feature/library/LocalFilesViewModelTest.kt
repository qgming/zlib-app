package com.qgming.zlib.feature.library

import androidx.lifecycle.ViewModelStore
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.LocalFilesState
import com.qgming.zlib.core.data.settings.AppSettings
import com.qgming.zlib.core.model.LocalFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFilesViewModelTest {
    @Test
    fun `leaving local page cancels a provider read and an ignored cancellation cannot launch later`() = runTest {
        withModel { fixture ->
            fixture.model.show()
            val intents = mutableListOf<LocalFileIntent>()
            val collector = backgroundScope.launch { fixture.model.fileIntents.collect { intents += it } }
            runCurrent()
            fixture.model.open(fixture.file)
            runCurrent()
            assertEquals(1, fixture.reads)
            fixture.model.hide()
            fixture.readGate.complete(AppResult.Success(fixture.file))
            runCurrent()
            fixture.model.show()
            runCurrent()
            assertTrue(intents.isEmpty())
            collector.cancel()
        }
    }

    @Test
    fun `changing directory rejects queued intent and cancels the old in flight open`() = runTest {
        withModel { fixture ->
            fixture.model.show()
            val old = LocalFileIntent(fixture.file, share = false, generation = fixture.model.feedbackGeneration.value)
            assertTrue(fixture.model.canLaunch(old))
            fixture.model.open(fixture.file)
            runCurrent()
            fixture.settings.value = AppSettings(downloadTreeUri = "other-tree")
            runCurrent()
            assertFalse(fixture.model.canLaunch(old))
            fixture.readGate.complete(AppResult.Success(fixture.file))
            runCurrent()
            fixture.model.open(fixture.file)
            runCurrent()
            assertEquals(1, fixture.reads)
            assertTrue(fixture.model.fileIntents.replayCache.isEmpty())
        }
    }

    @Test
    fun `opening without a collector never replays an old intent or toast on return`() = runTest {
        withModel { fixture ->
            fixture.model.show()
            fixture.readGate.complete(AppResult.Success(fixture.file))
            fixture.model.open(fixture.file)
            fixture.model.fileLaunchFailed("No reader", fixture.model.feedbackGeneration.value)
            runCurrent()
            assertTrue(fixture.model.fileIntents.replayCache.isEmpty())
            assertTrue(fixture.model.events.replayCache.isEmpty())
            val intents = mutableListOf<LocalFileIntent>()
            val notices = mutableListOf<LocalFileNotice>()
            backgroundScope.launch { fixture.model.fileIntents.collect { intents += it } }
            backgroundScope.launch { fixture.model.events.collect { notices += it } }
            runCurrent()
            assertTrue(intents.isEmpty())
            assertTrue(notices.isEmpty())
        }
    }

    @Test
    fun `settings observation never keeps the local scanner active off page`() = runTest {
        withModel { fixture ->
            assertEquals(0, fixture.scans)
            fixture.model.show()
            runCurrent()
            assertEquals(0, fixture.scans)
            val collection = backgroundScope.launch { fixture.model.state.collect {} }
            runCurrent()
            assertEquals(1, fixture.scans)
            collection.cancel()
            runCurrent()
            assertEquals(1, fixture.cancelledScans)
            fixture.settings.value = AppSettings(downloadTreeUri = "other-tree")
            runCurrent()
            assertEquals(1, fixture.scans)
        }
    }

    @Test
    fun `deletion completes after navigation without reviving stale actions or feedback`() = runTest {
        withModel { fixture ->
            fixture.model.show()
            backgroundScope.launch { fixture.model.state.collect {} }
            runCurrent()
            fixture.model.showActions(fixture.file)
            fixture.model.confirmDeletion()
            fixture.model.delete()
            runCurrent()
            assertEquals(1, fixture.deletes)
            assertTrue(fixture.model.action.value.busy)
            fixture.model.hide()
            assertEquals(LocalFileAction(), fixture.model.action.value)
            fixture.deleteGate.complete(AppResult.Success(Unit))
            runCurrent()
            fixture.model.show()
            runCurrent()
            assertTrue(fixture.deletionFinished)
            assertEquals(LocalFileAction(), fixture.model.action.value)
            assertTrue(fixture.model.events.replayCache.isEmpty())
        }
    }

    @Test
    fun `an initial settings snapshot is required before opening`() = runTest {
        withModel(primeSettings = false) { fixture ->
            fixture.model.show()
            fixture.model.open(fixture.file)
            runCurrent()
            assertEquals(0, fixture.reads)
            fixture.model.open(fixture.file)
            runCurrent()
            assertEquals(1, fixture.reads)
        }
    }

    private suspend fun TestScope.withModel(
        primeSettings: Boolean = true,
        block: suspend TestScope.(Fixture) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val fixture = Fixture()
            store.put("local", fixture.model)
            try {
                if (primeSettings) runCurrent()
                block(fixture)
            } finally {
                fixture.readGate.complete(AppResult.Success(fixture.file))
                fixture.deleteGate.complete(AppResult.Success(Unit))
            }
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private class Fixture {
        val file = LocalFile("tree", "exact.epub", "Book.epub", "epub")
        val settings = MutableStateFlow(AppSettings(downloadTreeUri = "tree"))
        val readGate = CompletableDeferred<AppResult<LocalFile>>()
        val deleteGate = CompletableDeferred<AppResult<Unit>>()
        var reads = 0
        var deletes = 0
        var deletionFinished = false
        var scans = 0
        var cancelledScans = 0
        val model = LocalFilesViewModel(
            observeFiles = flow {
                scans++
                try {
                    emit(LocalFilesState(treeUri = "tree", files = listOf(file)))
                    awaitCancellation()
                } finally {
                    cancelledScans++
                }
            },
            settings = settings,
            refreshFiles = {},
            readFile = {
                reads++
                // Some blocking document providers finish despite cancellation; the VM must still drop it.
                withContext(NonCancellable) { readGate.await() }
            },
            deleteFile = {
                deletes++
                deleteGate.await().also { deletionFinished = true }
            },
        )
    }
}
