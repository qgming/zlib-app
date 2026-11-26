package com.qgming.zlib.core.data.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qgming.zlib.core.common.SafeLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.downloadJournal by preferencesDataStore(
    name = "zlib_download_journal",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Serializable
internal enum class PublicationStep { Rename, Copy, Complete }

@Serializable
internal data class PendingDownloadPublication(
    val taskId: String,
    val taskCreatedAt: Long,
    val treeUri: String,
    val partUri: String,
    val targetName: String,
    val totalBytes: Long,
    val sha256: String,
    val targetUri: String? = null,
    val step: PublicationStep = PublicationStep.Rename,
)

@Serializable
internal data class DownloadContinuation(val partUri: String, val representation: DownloadRepresentation)

@Singleton
class DownloadJournal internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.downloadJournal)

    internal val publications: Flow<List<PendingDownloadPublication>> = store.data
        .catch { error ->
            if (error is IOException) {
                SafeLog.w("Download", "download journal unreadable")
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            preferences.asMap().mapNotNull { (key, value) ->
                if (key.name.startsWith(PUBLICATION) && value is String) decodePublication(value) else null
            }
        }

    internal suspend fun pending(treeUri: String, taskId: String, createdAt: Long): PendingDownloadPublication? =
        safePreferences()[key(PUBLICATION, treeUri, "$taskId:$createdAt")]?.let(::decodePublication)

    internal suspend fun update(publication: PendingDownloadPublication) {
        store.edit { preferences ->
            preferences[publicationKey(publication)] = Json.encodeToString(publication)
            preferences.remove(key(CONTINUATION, publication.treeUri, publication.taskId))
        }
    }

    internal suspend fun finish(publication: PendingDownloadPublication) {
        store.edit { preferences ->
            val key = publicationKey(publication)
            val raw = preferences[key] ?: return@edit
            val decoded = decodePublication(raw)
            if (decoded == null || decoded == publication) {
                preferences.remove(key)
            }
        }
    }

    internal suspend fun continuation(treeUri: String, taskId: String, partUri: String): DownloadRepresentation? =
        safePreferences()[key(CONTINUATION, treeUri, taskId)]
            ?.let(::decodeContinuation)
            ?.takeIf { it.partUri == partUri }?.representation

    private suspend fun safePreferences(): Preferences = try {
        store.data.first()
    } catch (error: IOException) {
        SafeLog.w("Download", "download journal read failed")
        emptyPreferences()
    }

    private fun decodePublication(raw: String): PendingDownloadPublication? =
        runCatching { Json.decodeFromString<PendingDownloadPublication>(raw) }.getOrNull()

    private fun decodeContinuation(raw: String): DownloadContinuation? =
        runCatching { Json.decodeFromString<DownloadContinuation>(raw) }.getOrNull()

    internal suspend fun setContinuation(treeUri: String, taskId: String, value: DownloadContinuation?) {
        store.edit { preferences ->
            val key = key(CONTINUATION, treeUri, taskId)
            if (value == null) preferences.remove(key) else preferences[key] = Json.encodeToString(value)
        }
    }

    private fun key(prefix: String, treeUri: String, taskId: String): Preferences.Key<String> =
        stringPreferencesKey(prefix + downloadSha256("$treeUri\u0000$taskId"))

    private fun publicationKey(publication: PendingDownloadPublication): Preferences.Key<String> =
        key(PUBLICATION, publication.treeUri, "${publication.taskId}:${publication.taskCreatedAt}")

    private companion object {
        const val PUBLICATION = "publication_"
        const val CONTINUATION = "continuation_"
    }
}
