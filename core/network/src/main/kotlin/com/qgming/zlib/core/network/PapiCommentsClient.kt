package com.qgming.zlib.core.network

import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.model.BookComment
import com.qgming.zlib.core.model.BookCommentsPage
import com.qgming.zlib.core.model.Session
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

class PapiCommentsClient(
    client: OkHttpClient,
    isAllowedHost: (String) -> Boolean,
    verificationCookieProvider: (HttpUrl) -> String? = { null },
    verificationUserAgentProvider: (HttpUrl) -> String? = { null },
) {
    private val transport = PapiTransport(
        client = client,
        isAllowedHost = isAllowedHost,
        verificationCookieProvider = verificationCookieProvider,
        verificationUserAgentProvider = verificationUserAgentProvider,
    )

    suspend fun comments(session: Session, bookId: String, page: Int): AppResult<BookCommentsPage> {
        if (!PAPI_ID.matches(bookId) || page !in 0 until MAX_PAGES) return invalidPapiParameters()
        return transport.request(
            session,
            "/papi/comments/book/$bookId/$page",
            query = { addQueryParameter("limit", PAGE_SIZE.toString()) },
        ) { root ->
            if (root.boolOrNull("commentsDisabled") == true) return@request BookCommentsPage(disabled = true)
            val rows = root.array("comments") ?: malformedPapiResponse()
            val likes = root.commentLikes()
            val base = DomainPolicy.baseUrl(session.domain).toHttpUrlOrNull()
            val comments = rows.take(PAGE_SIZE).map { entry ->
                val row = entry as? JsonObject ?: malformedPapiResponse()
                val id = row.stringOrNull("id")?.takeIf(PAPI_ID::matches) ?: malformedPapiResponse()
                val user = row.obj("user")
                val text = (row["text"] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: malformedPapiResponse()
                BookComment(
                    id = id,
                    parentId = row.stringOrNull("parent_id")?.takeIf(PAPI_ID::matches),
                    text = text.replace("\r\n", "\n").replace('\r', '\n')
                        .filterNot { it.isISOControl() && it != '\n' && it != '\t' }.trim(),
                    author = user?.stringOrNull("name").orEmpty().singleLine(),
                    date = (user?.stringOrNull("dateRelative") ?: row.stringOrNull("date")).orEmpty().singleLine(),
                    avatarUrl = base?.let { avatarUrl(user?.stringOrNull("avatar"), it) ?: avatarUrl(user?.stringOrNull("cover"), it) },
                    likeCount = likes[id] ?: 0,
                )
            }.distinctBy(BookComment::id)
            BookCommentsPage(comments = comments, nextPage = (page + 1).takeIf { rows.size >= PAGE_SIZE && it < MAX_PAGES })
        }
    }

    private fun JsonObject.commentLikes(): Map<String, Int> = buildMap {
        when (val reactions = obj("reactionsInfo")?.get("reactions")) {
            is JsonArray -> for (entry in reactions) {
                val row = entry as? JsonObject ?: continue
                val id = row.stringOrNull("item_id")?.takeIf(PAPI_ID::matches) ?: continue
                val count = row.intOrNull("count")?.takeIf { it >= 0 } ?: continue
                put(id, count)
            }
            is JsonObject -> for ((id, entry) in reactions) {
                if (!PAPI_ID.matches(id)) continue
                val count = when (entry) {
                    is JsonObject -> entry.intOrNull("count")
                    is JsonPrimitive -> entry.intOrNullValue()
                    else -> null
                }
                if (count != null && count >= 0) put(id, count)
            }
            else -> Unit
        }
    }

    private fun avatarUrl(raw: String?, base: HttpUrl): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2_048 && it.none(Char::isISOControl) && '\\' !in it }
            ?: return null
        val url = if (value.startsWith('/') && !value.startsWith("//")) base.resolve(value) else value.toHttpUrlOrNull()
        if (url == null || !url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null || url.encodedPath == "/"
        ) return null
        val host = url.host
        if (host != base.host && !DomainPolicy.isBuiltIn(host) && host != IMAGE_CDN && !host.endsWith(".$IMAGE_CDN")) return null
        return url.toString()
    }

    private fun String.singleLine(): String = filterNot(Char::isISOControl).trim()

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 20
        const val IMAGE_CDN = "cdn-zlib.sk"
    }
}
