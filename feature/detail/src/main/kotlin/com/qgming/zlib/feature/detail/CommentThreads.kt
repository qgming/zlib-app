package com.qgming.zlib.feature.detail

import com.qgming.zlib.core.model.BookComment

internal data class ThreadReply(val comment: BookComment, val depth: Int, val replyTo: BookComment?)

/** An index over the deduplicated comments loaded so far. Siblings keep server order. */
internal class CommentThreads(comments: List<BookComment>) {
    val mainComments = comments.filter { it.parentId == null }
    private val mainById = mainComments.associateBy(BookComment::id)
    private val children = comments.filter { it.parentId != null && it.parentId != it.id }.groupBy(BookComment::parentId)

    fun mainComment(id: String): BookComment? = mainById[id]

    fun replyCount(id: String): Int = children[id]?.size ?: 0

    fun replies(id: String): List<ThreadReply> {
        if (id !in mainById) return emptyList()
        val visited = mutableSetOf(id)
        val pending = ArrayDeque<ThreadReply>()
        for (child in children[id].orEmpty().asReversed()) pending.addLast(ThreadReply(child, depth = 1, replyTo = null))
        return buildList {
            while (pending.isNotEmpty()) {
                val reply = pending.removeLast()
                if (!visited.add(reply.comment.id)) continue
                add(reply)
                for (child in children[reply.comment.id].orEmpty().asReversed()) {
                    pending.addLast(ThreadReply(child, reply.depth + 1, replyTo = reply.comment))
                }
            }
        }
    }
}
