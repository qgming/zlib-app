package com.qgming.zlib.feature.detail

import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.BooklistMembership
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class BooklistPickerViewModelTest {
    @Test
    fun `picker reads on each open and adds only an owned unselected row once`() = withDetailTestMain {
        val response = CompletableDeferred<AppResult<Unit>>()
        var reads = 0
        val writes = mutableListOf<String>()
        val vm = picker(read = { _, _ ->
            reads++
            AppResult.Success(listOf(membership("42"), membership("43", added = true)))
        }, add = { expected, item, bookId ->
            assertEquals(Account, expected)
            writes += "${item.booklistId}:$bookId"
            response.await()
        })
        withPicker(vm) { notices ->
            vm.open("123")
            runCurrent()
            vm.add("unknown")
            vm.add("43")
            vm.add("42")
            vm.add("42")
            vm.dismiss()
            runCurrent()
            assertEquals(listOf("42:123"), writes)
            assertEquals(PickerRequest.Add, vm.state.value.request)
            assertTrue(vm.state.value.open)
            assertFalse(vm.state.value.lists.first().isMember)
            response.complete(AppResult.Success(Unit))
            runCurrent()
            assertTrue(vm.state.value.lists.first().isMember)
            assertEquals(1, notices.size)
            vm.add("42")
            vm.dismiss()
            vm.open("123")
            runCurrent()
            assertEquals(2, reads)
            assertEquals(1, writes.size)
        }
    }

    @Test
    fun `create once with the current book and verify an empty response against new membership`() = withDetailTestMain {
        val createResponse = CompletableDeferred<AppResult<String?>>()
        val writes = mutableListOf<Pair<String, String>>()
        val old = membership("42", added = true)
        val new = membership("57", added = true)
        val responses = ArrayDeque(listOf(
            AppResult.Success(listOf(old)),
            AppResult.Success(listOf(old, new.copy(isMember = false))),
            AppResult.Success(listOf(old, new)),
        ))
        val invalidations = mutableListOf<Session>()
        val vm = picker(read = { _, _ -> responses.removeFirst() }, create = { expected, title, bookId ->
            assertEquals(Account, expected)
            writes += title to bookId
            createResponse.await()
        }, invalidate = { invalidations += it })
        withPicker(vm) { notices ->
            vm.open("123")
            runCurrent()
            vm.showForm()
            vm.editTitle("  Reading  ")
            vm.create()
            vm.create()
            runCurrent()
            assertEquals(listOf("Reading" to "123"), writes)
            createResponse.complete(AppResult.Success(null))
            runCurrent()
            assertTrue(vm.state.value.needsVerification)
            assertTrue(vm.state.value.formOpen)
            assertTrue(notices.isEmpty())
            vm.create()
            vm.recheck()
            runCurrent()
            assertFalse(vm.state.value.formOpen)
            assertEquals(listOf(old, new), vm.state.value.lists)
            assertEquals(1, writes.size)
            assertEquals(listOf(Account), invalidations)
            assertEquals(1, notices.size)
        }
    }

    @Test
    fun `an ambiguous write failure leads to a read only recheck instead of a second write`() = withDetailTestMain {
        var reads = 0
        var writes = 0
        val vm = picker(read = { _, _ ->
            reads++
            AppResult.Success(if (reads == 1) emptyList() else listOf(membership("57", added = true)))
        }, create = { _, _, _ ->
            writes++
            AppResult.Failure(AppError.Network(IOException("test disconnect")))
        })
        withPicker(vm) { notices ->
            vm.open("123")
            runCurrent()
            vm.showForm()
            vm.editTitle("Reading")
            vm.create()
            runCurrent()
            assertIs<AppError.Network>(vm.state.value.createError)
            assertTrue(vm.state.value.needsVerification)
            vm.create()
            vm.recheck()
            runCurrent()
            assertEquals(2, reads)
            assertEquals(1, writes)
            assertEquals(1, notices.size)
        }
    }

    @Test
    fun `uncertain add requires reloading membership before another click can write`() = withDetailTestMain {
        var reads = 0
        var writes = 0
        val vm = picker(read = { _, _ -> AppResult.Success(listOf(membership("42", added = ++reads > 1))) }, add = { _, _, _ ->
            writes++
            AppResult.Failure(AppError.Server(503, ""))
        })
        withPicker(vm) { notices ->
            vm.open("123")
            runCurrent()
            vm.add("42")
            runCurrent()
            assertIs<AppError.Server>(vm.state.value.loadError)
            assertFalse(vm.state.value.lists.single().isMember)
            vm.add("42")
            vm.retryList()
            runCurrent()
            vm.add("42")
            runCurrent()
            assertTrue(vm.state.value.lists.single().isMember)
            assertEquals(1, writes)
            assertEquals(1, notices.size)
        }
    }

    @Test
    fun `session changes close picker and prevent stale writes and feedback`() = withDetailTestMain {
        val session = MutableStateFlow<Session?>(Account)
        val next = Account.copy(remixUserId = "8")
        val response = CompletableDeferred<AppResult<String?>>()
        val reads = mutableListOf<Session>()
        var cancelled = false
        val vm = picker(session, read = { expected, _ ->
            reads += expected
            AppResult.Success(listOf(membership("42")))
        }, create = { _, _, _ ->
            try {
                response.await()
            } finally {
                cancelled = true
            }
        })
        withPicker(vm) { notices ->
            vm.open("123")
            runCurrent()
            vm.showForm()
            vm.editTitle("Old account draft")
            vm.create()
            runCurrent()
            session.value = next
            vm.create() // even before the collector observes the change
            runCurrent()
            assertTrue(cancelled)
            response.complete(AppResult.Success("57"))
            runCurrent()
            assertFalse(vm.state.value.open)
            assertEquals("", vm.state.value.title)
            assertNull(vm.state.value.request)
            assertTrue(notices.isEmpty())
            vm.open("123")
            runCurrent()
            assertEquals(listOf(Account, next), reads)
        }
    }

    @Test
    fun `cards load covers only when visible and cancel when the form or another account opens`() = withDetailTestMain {
        val session = MutableStateFlow<Session?>(Account)
        val cover = BooklistCover("8", "https://images.example.test/8.jpg")
        val reads = mutableListOf<String>()
        val incomplete = CompletableDeferred<List<BooklistCover>>()
        var cancellations = 0
        val vm = picker(
            session = session,
            read = { _, _ -> AppResult.Success(listOf(
                membership("42", count = 0), membership("43", count = 1), membership("45", count = 3), membership("46"),
            )) },
            covers = { expected, id ->
                assertEquals(Account, expected)
                reads += id
                if (id == "43") listOf(cover) else try {
                    incomplete.await()
                } finally {
                    cancellations++
                }
            },
        )
        withPicker(vm) {
            vm.open("123")
            runCurrent()
            assertEquals(emptyList(), reads)
            vm.showPreviews(setOf("42", "43", "45"))
            runCurrent()
            assertEquals(listOf("43", "45"), reads)
            assertEquals(listOf(cover), vm.state.value.lists[1].booklist.covers)
            vm.showForm()
            runCurrent()
            assertEquals(1, cancellations)
            vm.backToLists()
            vm.showPreviews(setOf("43", "45"))
            runCurrent()
            assertEquals(listOf("43", "45", "45"), reads)
            session.value = Account.copy(domain = "z-lib.gd")
            runCurrent()
            assertEquals(2, cancellations)
            assertTrue(vm.state.value.lists.isEmpty())
            assertFalse(vm.state.value.open)
        }
    }

    @Test
    fun `confirmed add refreshes the visible cover slots without resetting membership`() = withDetailTestMain {
        val cover = BooklistCover("123", "https://images.example.test/123.jpg")
        val requests = mutableListOf<String>()
        val vm = picker(
            read = { _, _ -> AppResult.Success(listOf(membership("42", count = 0))) },
            add = { _, _, _ -> AppResult.Success(Unit) },
            covers = { _, id -> requests += id; listOf(cover) },
        )
        withPicker(vm) {
            vm.open("123")
            runCurrent()
            vm.showPreviews(setOf("42"))
            runCurrent()
            assertEquals(emptyList(), requests)
            vm.add("42")
            runCurrent()
            val item = vm.state.value.lists.single()
            assertEquals(listOf("42"), requests)
            assertTrue(item.isMember)
            assertEquals(1, item.booklist.bookCount)
            assertEquals(listOf(cover), item.booklist.covers)
        }
    }

    private fun picker(
        session: MutableStateFlow<Session?> = MutableStateFlow(Account),
        read: suspend (Session, String) -> AppResult<List<BooklistMembership>>,
        add: suspend (Session, BooklistMembership, String) -> AppResult<Unit> = { _, _, _ -> error("Unexpected add") },
        create: suspend (Session, String, String) -> AppResult<String?> = { _, _, _ -> error("Unexpected create") },
        invalidate: (Session) -> Unit = {},
        covers: suspend (Session, String) -> List<BooklistCover> = { _, _ -> emptyList() },
    ) = BooklistPickerViewModel(session, read, add, create, invalidate, covers)

    private suspend fun TestScope.withPicker(vm: BooklistPickerViewModel, block: suspend (List<String>) -> Unit) {
        val notices = mutableListOf<String>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        try {
            block(notices)
        } finally {
            collector.cancel()
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    private fun membership(id: String, added: Boolean = false, count: Int? = null) =
        BooklistMembership(CloudBooklist(id, "Reading", count), added)

    private companion object {
        val Account = Session("7", "test-key", "zlib.bz")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun withDetailTestMain(block: suspend TestScope.() -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    try {
        block()
    } finally {
        Dispatchers.resetMain()
    }
}
