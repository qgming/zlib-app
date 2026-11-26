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
        get() = isCredentialValue(remixUserId) && isCredentialValue(remixUserKey) && domain.isNotBlank()

    val cookieHeader: String
        get() = if (isValid) "$USER_ID_COOKIE=$remixUserId; $USER_KEY_COOKIE=$remixUserKey" else ""

    fun withDomain(host: String): Session = copy(domain = host)

    fun withAuthenticationCookies(): Session = copy(
        cookies = if (isValid) mapOf(USER_ID_COOKIE to remixUserId, USER_KEY_COOKIE to remixUserKey) else emptyMap(),
    )

    companion object {
        const val USER_ID_COOKIE = "remix_userid"
        const val USER_KEY_COOKIE = "remix_userkey"
        const val USER_KEY_COOKIE_ALT = "remix_user_key"

        fun isCredentialValue(value: String): Boolean =
            value.length in 1..1024 && value.all { it in '!'..'~' && it !in "\",;\\" }
    }
}
