package com.qgming.zlib.core.data.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.data.crypto.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "zlib_session")

interface SessionStore {
    suspend fun readVault(): SessionVault
    suspend fun saveVault(vault: SessionVault)
    suspend fun clear()
}

@Singleton
class DataStoreSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    cipher: SecretCipher,
    private val dispatchers: AppDispatchers,
) : SessionStore {
    private val dataStore = context.sessionDataStore
    private val codec = SessionVaultCodec(cipher)
    override suspend fun readVault(): SessionVault = withContext(dispatchers.io) {
        val encoded = dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.first()[VAULT_BLOB]
        val decoded = codec.decodeForMigration(encoded)
        if (decoded.needsRewrite) {
            try {
                val replacement = codec.encode(decoded.vault)
                dataStore.edit { if (it[VAULT_BLOB] == encoded) it[VAULT_BLOB] = replacement }
            } catch (_: IOException) {
                Unit
            } catch (_: GeneralSecurityException) {
                Unit
            }
        }
        decoded.vault
    }
    override suspend fun saveVault(vault: SessionVault) = withContext(dispatchers.io) {
        val encoded = codec.encode(vault)
        dataStore.edit { it[VAULT_BLOB] = encoded; it.remove(SESSION_BLOB) }
        Unit
    }
    override suspend fun clear() = withContext(dispatchers.io) {
        dataStore.edit { it.remove(VAULT_BLOB); it.remove(SESSION_BLOB) }
        Unit
    }
    companion object {
        private val SESSION_BLOB = stringPreferencesKey("session_blob")
        private val VAULT_BLOB = stringPreferencesKey("accounts_blob")
    }
}
