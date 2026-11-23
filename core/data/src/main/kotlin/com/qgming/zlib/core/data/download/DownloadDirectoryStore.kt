package com.qgming.zlib.core.data.download

import com.qgming.zlib.core.common.appText
import com.qgming.zlib.core.common.R as TextR

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.SafeLog
import com.qgming.zlib.core.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class DownloadDirectoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val dispatchers: AppDispatchers,
) {
    @Volatile
    private var cached: Uri? = null

    @Volatile
    private var loaded = false

    private val loadLock = Mutex()

    suspend fun ensureLoaded(): Uri? = loadLock.withLock {
        if (loaded) return@withLock cached
        val fromSettings = withContext(dispatchers.io) {
            settingsStore.read().downloadTreeUri?.let(Uri::parse)
        }
        cached = fromSettings
        loaded = true
        cached
    }

    fun currentUri(): Uri? = cached

    /**
     * Validates the tree, its persisted read/write grant and its directory flags without creating
     * a probe file, then saves it. On any failure the previous cached URI and setting stay intact.
     */
    suspend fun setTree(uri: Uri): AppResult<Unit> = loadLock.withLock {
        withContext(dispatchers.io) {
            try {
                if (!DocumentsContract.isTreeUri(uri)) {
                    return@withContext failure(appText(TextR.string.text_invalid_download_folder_choose_it_again))
                }
                val hadFullGrant = hasPersistedAccess(uri)
                if (!hadFullGrant) {
                    try {
                        context.contentResolver.takePersistableUriPermission(uri, PERSIST_FLAGS)
                    } catch (t: SecurityException) {
                        return@withContext AppResult.Failure(
                            AppError.Storage(IOException(appText(TextR.string.text_cannot_retain_folder_permission_choose_the_folder_again), t)),
                        )
                    }
                }
                val problem = accessProblem(uri)
                if (problem != null) {
                    if (!hadFullGrant) {
                        runCatching { context.contentResolver.releasePersistableUriPermission(uri, PERSIST_FLAGS) }
                    }
                    return@withContext failure(problem)
                }
                // Settings first: a failed write must not replace the old cached directory.
                settingsStore.setDownloadTreeUri(uri.toString())
                cached = uri
                loaded = true
                AppResult.Success(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                AppResult.Failure(AppError.Storage(t))
            }
        }
    }

    fun tree(): DocumentFile? = cached?.let { DocumentFile.fromTreeUri(context, it) }

    /** Read-only health check for the saved directory, used for the re-authorization entry. */
    suspend fun currentAccessible(): Boolean = withContext(dispatchers.io) {
        ensureLoaded() ?: return@withContext false
        val current = cached ?: return@withContext false
        if (!hasPersistedAccess(current)) return@withContext false
        val tree = runCatching { DocumentFile.fromTreeUri(context, current) }.getOrNull()
            ?: return@withContext false
        tree.isDirectory && tree.canRead() && tree.canWrite()
    }

    fun currentName(): String? = runCatching { tree()?.name }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Removes only this task's own temporary file. Legacy title-based `.part` files have no
     * trustworthy owner and are deliberately left untouched (ADR-060).
     */
    suspend fun deletePart(ref: String): Unit = withContext(dispatchers.io) {
        val partName = DownloadFileNames.partName(ref)
        runCatching {
            tree()?.findFile(partName)?.takeIf { it.isFile }?.delete()
        }.onFailure { SafeLog.w("Download", "temporary file cleanup unavailable") }
        Unit
    }

    suspend fun deleteFinal(fileName: String): Unit = withContext(dispatchers.io) {
        tree()?.findFile(fileName)?.delete()
        Unit
    }

    private fun hasPersistedAccess(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun accessProblem(uri: Uri): String? {
        if (!hasPersistedAccess(uri)) return appText(TextR.string.text_persistent_folder_access_is_missing_choose_the_folder_again)
        val tree = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
            ?: return appText(TextR.string.text_cannot_open_the_download_folder_choose_it_again)
        if (!tree.isDirectory) return appText(TextR.string.text_the_selected_location_is_not_a_folder)
        if (!tree.canRead() || !tree.canWrite()) return appText(TextR.string.text_the_folder_cannot_be_read_or_written_choose_another_folder)
        return null
    }

    private fun failure(message: String): AppResult<Unit> =
        AppResult.Failure(AppError.Storage(IOException(message)))

    private companion object {
        val PERSIST_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
