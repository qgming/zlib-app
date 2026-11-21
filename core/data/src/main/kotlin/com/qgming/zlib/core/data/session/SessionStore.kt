package com.qgming.zlib.core.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.model.Session
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "zlib_session")

interface SessionStore {
    val session: Flow<Session?>
    suspend fun read(): Session?
    suspend fun save(session: Session)
    suspend fun clear()
}

@Singleton
class DataStoreSessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: com.qgming.zlib.core.data.crypto.SecretCipher,
    private val dispatchers: AppDispatchers,
) : SessionStore {

    private val dataStore = context.sessionDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override val session: Flow<Session?> = dataStore.data
        .catch { exception -> if (exception is IOException) emit(emptyPreferences()) else throw exception }
        .map { preferences -> decode(preferences[SESSION_BLOB]) }

    override suspend fun read(): Session? = withContext(dispatchers.io) {
        session.first()
    }

    override suspend fun save(session: Session) {
        withContext(dispatchers.io) {
            val plaintext = json.encodeToString(StoredSession.from(session)).toByteArray(Charsets.UTF_8)
            val encrypted = cipher.encrypt(plaintext)
            val encoded = Base64.getEncoder().encodeToString(encrypted)
            dataStore.edit { it[SESSION_BLOB] = encoded }
        }
    }

    override suspend fun clear() {
        withContext(dispatchers.io) {
            dataStore.edit { it.remove(SESSION_BLOB) }
        }
    }

    private fun decode(encoded: String?): Session? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val encrypted = Base64.getDecoder().decode(encoded)
            val plaintext = cipher.decrypt(encrypted) ?: return null
            val stored = json.decodeFromString<StoredSession>(plaintext.toString(Charsets.UTF_8))
            stored.toSession()
        }.getOrNull()
    }

    companion object {
        private val SESSION_BLOB = stringPreferencesKey("session_blob")
    }
}

@Serializable
private data class StoredSession(
    @SerialName("user_id") val userId: String,
    @SerialName("user_key") val userKey: String,
    val domain: String,
    val cookies: Map<String, String> = emptyMap(),
    @SerialName("created_at") val createdAt: Long = 0L,
) {
    fun toSession(): Session? {
        val session = Session(
            remixUserId = userId,
            remixUserKey = userKey,
            domain = domain,
            cookies = cookies,
            createdAtMillis = createdAt,
        )
        return session.takeIf { it.isValid }
    }

    companion object {
        fun from(session: Session) = StoredSession(
            userId = session.remixUserId,
            userKey = session.remixUserKey,
            domain = session.domain,
            cookies = session.cookies,
            createdAt = session.createdAtMillis,
        )
    }
}
