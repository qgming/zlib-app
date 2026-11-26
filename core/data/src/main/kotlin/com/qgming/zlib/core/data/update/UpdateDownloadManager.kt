package com.qgming.zlib.core.data.update

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import com.qgming.zlib.core.common.AppResult
import com.qgming.zlib.core.data.download.DownloadDirectoryStore
import com.qgming.zlib.core.data.download.DownloadRepository
import com.qgming.zlib.core.model.AppRelease
import com.qgming.zlib.core.model.apkFor
import com.qgming.zlib.core.network.ReleaseApkDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class UpdateDownloadPhase { Idle, Starting, Queued, Downloading, Verifying, Saving, Ready, Failed }
enum class UpdateDownloadFailure { Directory, Asset, Transfer, Interrupted, Verification, Storage }
data class UpdateDownloadState(
    val phase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
    val release: AppRelease? = null,
    val written: Long = 0,
    val total: Long = 0,
    val savedUri: String? = null,
    val failure: UpdateDownloadFailure? = null,
) {
    val busy: Boolean get() = phase in setOf(UpdateDownloadPhase.Starting, UpdateDownloadPhase.Queued, UpdateDownloadPhase.Downloading, UpdateDownloadPhase.Verifying, UpdateDownloadPhase.Saving)
}

@Singleton
class UpdateDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directory: DownloadDirectoryStore,
    private val bookDownloads: DownloadRepository,
    private val files: UpdateApkStore,
) {
    private val downloader = ReleaseApkDownloader()
    private val attempts = UpdateDownloadAttempts()
    val state = attempts.state
    private val fileAccess = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restored = false

    suspend fun restoreReady(): Unit = withContext(Dispatchers.IO) {
        fileAccess.withLock {
            if (restored) return@withLock
            val saved = files.restoreReady()
            restored = true
            if (saved != null) attempts.restore(saved.first, saved.second)
        }
    }

    suspend fun setDirectory(uri: Uri): Boolean = bookDownloads.setDownloadDirectory(uri) is AppResult.Success

    suspend fun start(release: AppRelease) {
        val id = attempts.begin(release) ?: return
        try {
            if (!directory.currentAccessible()) {
                attempts.fail(id, UpdateDownloadFailure.Directory)
                return
            }
            if (release.apkFor(Build.SUPPORTED_ABIS.toList()) == null) {
                attempts.fail(id, UpdateDownloadFailure.Asset)
                return
            }
            currentCoroutineContext().ensureActive()
            if (!attempts.change(id) { it.copy(phase = UpdateDownloadPhase.Queued) }) return
            ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadService::class.java).putExtra(UpdateDownloadService.EXTRA_ATTEMPT, id))
        } catch (cancellation: CancellationException) {
            attempts.interrupt(id)
            throw cancellation
        } catch (_: ForegroundServiceStartNotAllowedException) {
            attempts.fail(id, UpdateDownloadFailure.Interrupted)
        } catch (_: Exception) {
            attempts.fail(id, UpdateDownloadFailure.Transfer)
        }
    }

    internal fun isQueued(id: Long): Boolean = attempts.isPhase(id, UpdateDownloadPhase.Queued)

    internal fun isReady(id: Long): Boolean = attempts.isPhase(id, UpdateDownloadPhase.Ready)

    internal fun isBusy(id: Long): Boolean = attempts.isBusy(id)

    internal suspend fun run(id: Long) {
        val worker = currentCoroutineContext().job
        val release = attempts.claim(id, worker) ?: return
        try {
            withContext(Dispatchers.IO) {
                fileAccess.withLock {
                    if (attempts.isBusy(id)) transfer(id, release)
                }
            }
        } catch (cancellation: CancellationException) {
            attempts.fail(id, UpdateDownloadFailure.Interrupted)
            throw cancellation
        } catch (_: Exception) {
            attempts.fail(id, UpdateDownloadFailure.Transfer)
        } finally {
            attempts.release(id)
        }
    }

    private suspend fun transfer(id: Long, release: AppRelease) {
        val asset = release.apkFor(Build.SUPPORTED_ABIS.toList()) ?: run {
            attempts.fail(id, UpdateDownloadFailure.Asset)
            return
        }
        val target = files.temporary()
        var saved: Uri? = null
        var published = false
        var failure = UpdateDownloadFailure.Transfer
        try {
            if (!attempts.change(id) { it.copy(total = asset.size) }) return
            downloader.download(asset, target) { written, total ->
                attempts.change(id) { it.copy(written = written, total = total) }
            }
            currentCoroutineContext().ensureActive()
            failure = UpdateDownloadFailure.Verification
            if (!attempts.change(id) { it.copy(phase = UpdateDownloadPhase.Verifying) }) return
            files.validate(target, release)
            currentCoroutineContext().ensureActive()
            failure = UpdateDownloadFailure.Storage
            if (!attempts.change(id) { it.copy(phase = UpdateDownloadPhase.Saving) }) return
            val destination = files.save(target, release)
            saved = destination
            currentCoroutineContext().ensureActive()
            if (!attempts.isBusy(id)) return
            files.publishReady(target, release, destination)
            published = attempts.change(id) { it.copy(phase = UpdateDownloadPhase.Ready, savedUri = destination.toString()) }
            if (!published) files.discardReady()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            attempts.fail(id, failure)
        } finally {
            if (!published) saved?.let(files::discardSaved)
            target.delete()
        }
    }

    internal fun interrupted(id: Long) = attempts.interrupt(id)
    fun cancel() {
        val wasReady = state.value.phase == UpdateDownloadPhase.Ready
        attempts.cancel()
        if (wasReady) {
            cleanupScope.launch {
                fileAccess.withLock {
                    if (state.value.phase == UpdateDownloadPhase.Idle) files.discardReady()
                }
            }
        }
    }

    fun installerFile(): File? = files.ready().takeIf { state.value.phase == UpdateDownloadPhase.Ready && it.isFile }
}
