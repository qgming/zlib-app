package com.qgming.zlib.core.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qgming.zlib.core.common.AppDispatchers
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.common.AppError
import com.qgming.zlib.core.data.settings.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
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

    suspend fun ensureLoaded(): Uri? {
        if (loaded) return cached
        cached = withContext(dispatchers.io) {
            settingsStore.read().downloadTreeUri?.let(Uri::parse)
        }
        loaded = true
        return cached
    }

    fun currentUri(): Uri? = cached

    suspend fun setTree(uri: Uri): AppResult<Unit> = withContext(dispatchers.io) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            cached = uri
            loaded = true
            settingsStore.setDownloadTreeUri(uri.toString())
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            AppResult.Failure(AppError.Storage(t))
        }
    }

    fun tree(): DocumentFile? = cached?.let { DocumentFile.fromTreeUri(context, it) }

    suspend fun deletePart(fileName: String): Unit = withContext(dispatchers.io) {
        tree()?.findFile("$fileName.part")?.delete()
        Unit
    }

    suspend fun deleteFinal(fileName: String): Unit = withContext(dispatchers.io) {
        tree()?.findFile(fileName)?.delete()
        Unit
    }
}
