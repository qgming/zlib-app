package com.qgming.zlib.core.model

/** One account session. Only remix credentials and necessary cookies are persisted (encrypted). */
data class Session(
    val remixUserId: String,
    val remixUserKey: String,
    /** Bare host, e.g. `z-lib.gd`. Never includes a scheme or path. */
    val domain: String,
    val cookies: Map<String, String> = emptyMap(),
    val createdAtMillis: Long = 0L,
) {
    val isValid: Boolean
        get() = remixUserId.isNotBlank() && remixUserKey.isNotBlank() && domain.isNotBlank()

    /** `name=value; name2=value2` for the Cookie header. */
    val cookieHeader: String
        get() = cookies.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .joinToString("; ") { (name, value) -> "$name=$value" }

    fun withDomain(host: String): Session = copy(domain = host)

    fun withCookies(newCookies: Map<String, String>): Session =
        copy(cookies = cookies + newCookies)

    fun mergeCookies(cookieHeaderValues: List<String>): Session {
        if (cookieHeaderValues.isEmpty()) return this
        val parsed = cookieHeaderValues
            .flatMap { it.split(';') }
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val name = part.substring(0, index).trim()
                val value = part.substring(index + 1).trim()
                if (name.isEmpty() || value.isEmpty()) null else name to value
            }
            .toMap()
        return withCookies(parsed)
    }

    companion object {
        const val USER_ID_COOKIE = "remix_userid"
        const val USER_KEY_COOKIE = "remix_userkey"
        const val USER_KEY_COOKIE_ALT = "remix_user_key"
    }
}
