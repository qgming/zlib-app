package com.qgming.zlib.core.network

/** Transport constants shared by EAPI requests and the domain probe. */
object EapiHeaders {
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    const val ACCEPT = "application/json"

    /**
     * Client source marker sent by the mobile apps. `/eapi/book/search` answers
     * HTTP 400 `Access denied` without it, even for a valid session (ADR-076).
     */
    const val SOURCE = "source"
    const val SOURCE_ANDROID = "android"

    const val REMIX_USER_ID = "remix-userid"
    const val REMIX_USER_KEY = "remix-userkey"
    const val REMIX_USER_KEY_ALT = "remix-user-key"
    const val COOKIE = "Cookie"
}

object EapiEndpoints {
    const val LOGIN = "/eapi/user/login"
    const val PROFILE = "/eapi/user/profile"
    const val DOMAINS = "/eapi/info/domains"
    const val SEARCH = "/eapi/book/search"
    const val SAVED = "/eapi/user/book/saved"
    const val DOWNLOADED = "/eapi/user/book/downloaded"
    const val RECOMMENDED = "/eapi/user/book/recommended"
    const val MOST_POPULAR = "/eapi/book/most-popular"

    fun detail(id: String, hash: String): String = "/eapi/book/$id/$hash"
    fun formats(id: String, hash: String): String = "/eapi/book/$id/$hash/formats"
    fun file(id: String, hash: String): String = "/eapi/book/$id/$hash/file"
    fun deleteHistory(id: String): String = "/eapi/user/book/$id/delete"
    fun saveBook(id: String): String = "/eapi/user/book/$id/save"
    fun unsaveBook(id: String): String = "/eapi/user/book/$id/unsave"
}
