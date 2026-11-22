package com.qgming.zlib.core.model

/** Download quota. [known] is false when the server did not report limits. */
data class Quota(
    val amount: Int? = null,
    val allowed: Int? = null,
    val remaining: Int? = null,
) {
    val known: Boolean get() = allowed != null

    companion object {
        fun from(amount: Int?, allowed: Int?): Quota {
            val remaining = if (allowed == null) null else (allowed - (amount ?: 0)).coerceAtLeast(0)
            return Quota(amount = amount, allowed = allowed, remaining = remaining)
        }
    }
}

data class Account(
    val id: String,
    val name: String,
    val email: String,
    val isPremium: Boolean,
    val confirmed: Boolean,
    val quota: Quota,
    val membershipKnown: Boolean = true,
)

data class AccountEntry(val id: String, val profile: Account?, val current: Boolean, val expired: Boolean)
