package com.qgming.zlib.core.data.library

import com.qgming.zlib.core.model.BooklistCover
import com.qgming.zlib.core.model.CloudBooklist
import com.qgming.zlib.core.model.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Preview requests and cache scoped to one screen's active account and visible cards. */
class BooklistPreviews(
    private val scope: CoroutineScope,
    private val isCurrentSession: (Session) -> Boolean,
    private val readCovers: suspend (Session, String) -> List<BooklistCover>?,
) {
    private val requests = mutableMapOf<String, Job>()
    private val cache = mutableMapOf<String, List<BooklistCover>>()

    fun withCachedCovers(list: CloudBooklist): CloudBooklist = list.withCoverPreview(cache[list.id].orEmpty())

    fun loadVisible(
        session: Session,
        lists: List<CloudBooklist>,
        visibleIds: Set<String>,
        onLoaded: (listId: String, covers: List<BooklistCover>) -> Unit,
    ) {
        val candidates = lists.filter { it.id in visibleIds && it.needsCoverPreview }
        val ids = candidates.mapTo(mutableSetOf(), CloudBooklist::id)
        requests.keys.filter { it !in ids }.forEach { requests.remove(it)?.cancel() }
        candidates.forEach { list ->
            val id = list.id
            if (id in cache || id in requests) return@forEach
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val covers = readCovers(session, id)
                ensureActive()
                requests.remove(id)
                if (!isCurrentSession(session) || covers == null) return@launch
                cache[id] = covers
                if (covers.isNotEmpty()) onLoaded(id, covers)
            }
            requests[id] = job
            job.start()
        }
    }

    fun invalidate(id: String) {
        requests.remove(id)?.cancel()
        cache.remove(id)
    }

    fun cancel() {
        requests.values.forEach(Job::cancel)
        requests.clear()
    }

    fun clear() {
        cancel()
        cache.clear()
    }
}
