package com.qgming.zlib.feature.detail

import androidx.lifecycle.viewModelScope
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.BookComment
import com.qgming.zlib.core.model.BookCommentsPage
import com.qgming.zlib.core.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class BookCommentsViewModelTest {
    @Test
    fun `append failure retains comments and retry uses the same page until results stop progressing`() = withDetailTestMain {
        val session = MutableStateFlow<Session?>(Account)
        val requests = mutableListOf<Int>()
        val vm = BookCommentsViewModel(session) { expected, id, page ->
            assertEquals(Account, expected)
            assertEquals("123", id)
            requests += page
            when (requests.size) {
                1 -> AppResult.Success(BookCommentsPage(listOf(comment("1")), nextPage = 1))
                2 -> AppResult.Failure(AppError.Server(503, ""))
                3 -> AppResult.Success(BookCommentsPage(listOf(comment("1"), comment("2")), nextPage = 2))
                else -> AppResult.Success(BookCommentsPage(listOf(comment("2")), nextPage = 3))
            }
        }
        withViewModel(vm) {
            vm.open("123")
            vm.open("123")
            runCurrent()
            vm.loadMore()
            vm.loadMore()
            runCurrent()
            assertIs<AppError.Server>(vm.state.value.error)
            assertEquals(listOf("1"), vm.state.value.page?.comments?.map(BookComment::id))
            vm.loadMore()
            vm.retry()
            runCurrent()
            assertEquals(listOf("1", "2"), vm.state.value.page?.comments?.map(BookComment::id))
            assertEquals(2, vm.state.value.page?.nextPage)
            vm.loadMore()
            runCurrent()
            assertNull(vm.state.value.page?.nextPage)
            vm.loadMore()
            runCurrent()
            assertEquals(listOf(0, 1, 1, 2), requests)
        }
    }

    @Test
    fun `mirror changes cancel the old request and reopen reads from zero`() = withDetailTestMain {
        val session = MutableStateFlow<Session?>(Account)
        val mirror = Account.copy(domain = "z-lib.gd")
        val pending = CompletableDeferred<BookCommentsPage>()
        var oldRequestFinished = false
        val reads = mutableListOf<Pair<String, Int>>()
        val vm = BookCommentsViewModel(session) { expected, _, page ->
            reads += expected.domain to page
            if (expected == Account) {
                try {
                    AppResult.Success(pending.await())
                } finally {
                    oldRequestFinished = true
                }
            } else {
                AppResult.Success(BookCommentsPage(listOf(comment("2"))))
            }
        }
        withViewModel(vm) {
            vm.open("123")
            runCurrent()
            assertTrue(vm.state.value.loading)
            session.value = mirror
            runCurrent()
            assertTrue(oldRequestFinished)
            pending.complete(BookCommentsPage(listOf(comment("1"))))
            runCurrent()
            assertEquals("2", vm.state.value.page?.comments?.single()?.id)
            vm.close()
            assertNull(vm.state.value.page)
            vm.open("123")
            runCurrent()
            assertEquals(listOf(Account.domain to 0, mirror.domain to 0, mirror.domain to 0), reads)
            session.value = null
            runCurrent()
            assertNull(vm.state.value.page)
            assertEquals(AppError.NotLoggedIn, vm.state.value.error)
            assertFalse(vm.state.value.loading)
        }
    }

    @Test
    fun `only main comments are listed and detail keeps the same dataset while replies appear across pages`() = withDetailTestMain {
        val reads = mutableListOf<Int>()
        val main = comment("1").copy(text = "Full text\n2\n3\n4\n5")
        val directReply = comment("2", "1").copy(author = "Direct reader")
        val nestedReply = comment("3", "2").copy(author = "Nested reader")
        val vm = BookCommentsViewModel(MutableStateFlow(Account)) { _, _, page ->
            reads += page
            AppResult.Success(when (page) {
                0 -> BookCommentsPage(listOf(nestedReply, comment("8", "9")), nextPage = 1)
                1 -> BookCommentsPage(listOf(main, directReply, comment("4")), nextPage = 2)
                else -> BookCommentsPage(listOf(directReply, comment("5", "4"), comment("6", "1"), comment("7", "3")))
            })
        }
        withViewModel(vm) {
            vm.open("123")
            runCurrent()
            val first = vm.state.value
            assertEquals(listOf(0, 1), reads)
            assertEquals(listOf("1", "4"), first.threads.mainComments.map(BookComment::id))
            assertEquals(1, first.threads.replyCount("1"))
            vm.open("123", "1")
            runCurrent()
            assertEquals(main.text, vm.state.value.threads.mainComment("1")?.text)
            assertEquals(listOf(
                ThreadReply(directReply, depth = 1, replyTo = null),
                ThreadReply(nestedReply, depth = 2, replyTo = directReply),
            ), vm.state.value.threads.replies("1"))
            assertEquals(listOf(0, 1), reads)
            vm.loadMore()
            runCurrent()
            val replies = vm.state.value.threads.replies("1")
            assertEquals(listOf("2", "3", "7", "6"), replies.map { it.comment.id })
            assertEquals(listOf(1, 2, 3, 1), replies.map(ThreadReply::depth))
            assertEquals(listOf(null, "Direct reader", "Nested reader", null), replies.map { it.replyTo?.author })
            assertEquals(2, vm.state.value.threads.replyCount("1"))
            assertEquals(listOf("5"), vm.state.value.threads.replies("4").map { it.comment.id })
            vm.open("123")
            runCurrent()
            assertEquals(first.dataset, vm.state.value.dataset)
            assertEquals(listOf("1", "4"), vm.state.value.threads.mainComments.map(BookComment::id))
            assertEquals(listOf(0, 1, 2), reads)
        }
    }

    @Test
    fun `restored comment route searches later pages and retry uses the failed page`() = withDetailTestMain {
        val reads = mutableListOf<Int>()
        val vm = BookCommentsViewModel(MutableStateFlow(Account)) { _, _, page ->
            reads += page
            when {
                page == 0 -> AppResult.Success(BookCommentsPage(listOf(comment("1")), nextPage = 1))
                reads.size == 2 -> AppResult.Failure(AppError.Server(503, ""))
                else -> AppResult.Success(BookCommentsPage(listOf(comment("2"))))
            }
        }
        withViewModel(vm) {
            vm.open("123", "2")
            runCurrent()
            assertNull(vm.state.value.threads.mainComment("2"))
            assertIs<AppError.Server>(vm.state.value.error)
            vm.retry()
            runCurrent()
            assertEquals("2", vm.state.value.threads.mainComment("2")?.id)
            assertEquals(listOf(0, 1, 1), reads)
        }
    }

    @Test
    fun `preview fills at most three main comment cards and direct navigation shares loaded data`() = withDetailTestMain {
        val pending = CompletableDeferred<BookCommentsPage>()
        val reads = mutableListOf<Int>()
        val vm = BookCommentsViewModel(MutableStateFlow(Account)) { _, _, page ->
            reads += page
            AppResult.Success(when (page) {
                0 -> BookCommentsPage(listOf(comment("1"), comment("2", "1")), nextPage = 1)
                1 -> pending.await()
                else -> error("Unnecessary prefetch")
            })
        }
        withViewModel(vm) {
            vm.showPreview("123")
            runCurrent()
            assertEquals(listOf("1"), vm.state.value.previewComments.map(BookComment::id))
            assertEquals(listOf(0, 1), reads)
            val dataset = vm.state.value.dataset
            pending.complete(BookCommentsPage(listOf(comment("3"), comment("4"), comment("5")), nextPage = 2))
            runCurrent()
            assertEquals(listOf("1", "3", "4"), vm.state.value.previewComments.map(BookComment::id))
            vm.open("123", "3")
            runCurrent()
            assertEquals("3", vm.state.value.threads.mainComment("3")?.id)
            vm.open("123")
            runCurrent()
            assertEquals(listOf("1", "3", "4", "5"), vm.state.value.threads.mainComments.map(BookComment::id))
            assertEquals(dataset, vm.state.value.dataset)
            assertEquals(listOf(0, 1), reads)
        }
    }

    @Test
    fun `empty or failed preview does not invent cards and entering the list can retry`() = withDetailTestMain {
        var reads = 0
        val vm = BookCommentsViewModel(MutableStateFlow(Account)) { _, _, _ ->
            if (++reads == 1) AppResult.Failure(AppError.Server(503, "")) else AppResult.Success(BookCommentsPage())
        }
        withViewModel(vm) {
            vm.showPreview("123")
            runCurrent()
            assertTrue(vm.state.value.previewComments.isEmpty())
            assertIs<AppError.Server>(vm.state.value.error)
            vm.open("123")
            runCurrent()
            assertEquals(2, reads)
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.previewComments.isEmpty())
            assertFalse(vm.state.value.loading)
        }
    }

    private suspend fun TestScope.withViewModel(vm: BookCommentsViewModel, block: suspend () -> Unit) {
        try {
            block()
        } finally {
            vm.viewModelScope.cancel()
            runCurrent()
        }
    }

    private fun comment(id: String, parentId: String? = null) = BookComment(id, parentId, "Comment $id", "Reader", "")

    private companion object {
        val Account = Session("7", "test-key", "zlib.bz")
    }
}
