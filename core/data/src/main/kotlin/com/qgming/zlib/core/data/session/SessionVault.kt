package com.qgming.zlib.core.data.session

import com.qgming.zlib.core.model.Account
import com.qgming.zlib.core.model.Quota
import com.qgming.zlib.core.model.Session
import com.qgming.zlib.core.data.crypto.SecretCipher
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class SessionEntry(val session: Session, val profile: Account? = null, val expired: Boolean = false)
data class SessionVault(
    val entries: List<SessionEntry> = emptyList(),
    val activeId: String? = null,
    val autoSwitch: Boolean = false,
) {
    val current: Session? get() = entries.firstOrNull { it.session.remixUserId == activeId && !it.expired }?.session
}

/** Only encrypted multi-account state is persisted. */
internal class SessionVaultCodec(private val cipher: SecretCipher) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    fun encode(vault: SessionVault): String = Base64.getEncoder().encodeToString(
        cipher.encrypt(json.encodeToString(StoredVault.from(vault)).toByteArray(Charsets.UTF_8)),
    )
    fun decode(encoded: String?): SessionVault = decodeForMigration(encoded).vault

    fun decodeForMigration(encoded: String?): DecodedVault = runCatching {
        if (encoded.isNullOrBlank()) return DecodedVault()
        val plain = cipher.decrypt(Base64.getDecoder().decode(encoded)) ?: return DecodedVault()
        val stored = json.decodeFromString<StoredVault>(plain.toString(Charsets.UTF_8))
        DecodedVault(stored.toVault(), stored.entries.any { it.cookies != null })
    }.getOrDefault(DecodedVault())

    data class DecodedVault(val vault: SessionVault = SessionVault(), val needsRewrite: Boolean = false)
}

@Serializable
private data class StoredVault(val entries: List<StoredSession>, val activeId: String? = null, val autoSwitch: Boolean = false) {
    fun toVault() = SessionVault(
        entries.mapNotNull { row -> runCatching { SessionEntry(row.toSession(), row.profile?.toAccount(), row.expired) }.getOrNull() }
            .distinctBy { it.session.remixUserId }, activeId, autoSwitch,
    )
    companion object {
        fun from(vault: SessionVault) = StoredVault(
            vault.entries.map { StoredSession.from(it) }, vault.activeId, vault.autoSwitch,
        )
    }
}

@Serializable
private data class StoredSession(
    @SerialName("user_id") val userId: String,
    @SerialName("user_key") val userKey: String,
    val domain: String,
    val cookies: Map<String, String>? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    val expired: Boolean = false,
    val profile: StoredProfile? = null,
) {
    fun toSession() = Session(userId, userKey, domain, createdAtMillis = createdAt).withAuthenticationCookies().also { require(it.isValid) }
    companion object {
        fun from(entry: SessionEntry) = with(entry.session) {
            StoredSession(remixUserId, remixUserKey, domain, createdAt = createdAtMillis, expired = entry.expired, profile = entry.profile?.let(StoredProfile::from))
        }
    }
}

@Serializable
private data class StoredProfile(
    val id: String, val name: String, val email: String, val premium: Boolean, val confirmed: Boolean,
    val amount: Int? = null, val allowed: Int? = null, val remaining: Int? = null, val membershipKnown: Boolean = true,
) {
    fun toAccount() = Account(id, name, email, premium, confirmed, Quota(amount, allowed, remaining), membershipKnown)
    companion object {
        fun from(account: Account) = with(account) {
            StoredProfile(id, name, email, isPremium, confirmed, quota.amount, quota.allowed, quota.remaining, membershipKnown)
        }
    }
}
