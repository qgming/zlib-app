package com.qgming.zlib.feature.library

import com.qgming.zlib.core.data.library.BooklistRepository
import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal class BooklistPreviews(private val repository: BooklistRepository, private val scope: CoroutineScope) {
    private val requests = mutableMapOf<String, Job>()
    private val cache = mutableMapOf<String, List<BooklistCover>>()

    fun withCachedCovers(lists: List<CloudBooklist>): List<CloudBooklist> =
        lists.map { list -> list.withCovers(cache[list.id].orEmpty()) }

    fun loadVisible(
        session: Session,
        lists: List<CloudBooklist>,
        visibleIds: Set<String>,
        onLoaded: (CloudBooklist) -> Unit,
    ) {
        requests.keys.filter { it !in visibleIds }.forEach { requests.remove(it)?.cancel() }
        lists.asSequence().filter { it.id in visibleIds }.forEach { list ->
            val id = list.id
            val slots = minOf(list.bookCount ?: BooklistCover.COUNT, BooklistCover.COUNT)
            if (list.covers.size >= slots || id in cache || id in requests) return@forEach
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val covers = repository.previewCovers(session, id)
                ensureActive()
                requests.remove(id)
                if (repository.session.value != session) return@launch
                cache[id] = covers
                if (covers.isNotEmpty()) onLoaded(list.withCovers(covers))
            }
            requests[id] = job
            job.start()
        }
    }

    fun cancel() {
        requests.values.forEach(Job::cancel)
        requests.clear()
    }

    fun clear() {
        cancel()
        cache.clear()
    }

    private fun CloudBooklist.withCovers(preview: List<BooklistCover>): CloudBooklist =
        if (preview.isEmpty()) this else copy(covers = (covers + preview).distinctBy(BooklistCover::bookId).take(BooklistCover.COUNT))
}
